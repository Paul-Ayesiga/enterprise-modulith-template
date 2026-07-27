package ug.co.smsone.analytics.internal;

import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import ug.co.smsone.analytics.AnalyticsEngine;
import ug.co.smsone.analytics.AnalyticsException;

/**
 * Embedded DuckDB engine. The durable mart file is shared by all in-process connections (one
 * native instance); the lock serializes statements on the primary connection, while
 * materialization runs on its own connection so long refreshes never block KPI reads. All
 * connections pin {@code TimeZone='UTC'} — marts and Parquet snapshots are UTC by contract, so
 * day-bucket KPIs are identical on every host.
 */
@Component
class DuckDbAnalyticsEngine implements AnalyticsEngine {

    private static final Logger log = LoggerFactory.getLogger(DuckDbAnalyticsEngine.class);
    private static final int INSERT_BATCH_SIZE = 500;
    private static final int SOURCE_FETCH_SIZE = 1000;
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,200}$");

    private final AnalyticsProperties properties;
    private final DataSource postgres;
    private final ReentrantLock lock = new ReentrantLock();
    private final Semaphore ephemeralPermits;
    private Connection durable;

    DuckDbAnalyticsEngine(AnalyticsProperties properties, DataSource postgres) {
        this.properties = properties;
        this.postgres = postgres;
        this.ephemeralPermits = new Semaphore(properties.maxEphemeralConcurrency());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AnalyticsProperties.class)
    static class PropertiesRegistrar {
    }

    @Override
    public List<Map<String, Object>> query(String sql, Object... params) {
        lock.lock();
        try (PreparedStatement statement = durableConnection().prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                return rows(resultSet);
            }
        } catch (SQLException e) {
            throw new AnalyticsException("Analytics query failed", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Map<String, Object>> queryEphemeral(String sql, Object... params) {
        // each call is its own in-memory instance with its own caps — bound how many exist at once
        if (!acquireEphemeralPermit()) {
            throw new AnalyticsException("Too many concurrent ephemeral analytics queries", null);
        }
        try (Connection ephemeral = open(null);
                PreparedStatement statement = ephemeral.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                return rows(resultSet);
            }
        } catch (SQLException e) {
            throw new AnalyticsException("Ephemeral analytics query failed", e);
        } finally {
            ephemeralPermits.release();
        }
    }

    @Override
    public void execute(String sql, Object... params) {
        lock.lock();
        try (PreparedStatement statement = durableConnection().prepareStatement(sql)) {
            bind(statement, params);
            statement.execute();
        } catch (SQLException e) {
            throw new AnalyticsException("Analytics statement failed", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long materializeFromPostgres(String sourceSql, String martTable) {
        String mart = quoteIdentifier(martTable);
        String staging = quoteIdentifier(martTable + "__staging");
        // own DuckDB connection (same shared instance): a long refresh must not block KPI reads,
        // and a failed refresh must leave the previous mart untouched (staging + atomic swap)
        try (Connection duck = open(properties.databasePath());
                Connection source = postgres.getConnection()) {
            boolean sourceAutoCommit = source.getAutoCommit();
            source.setAutoCommit(false); // pgjdbc streams with a cursor ONLY when autocommit is off
            try (Statement sourceStatement = source.createStatement(
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                sourceStatement.setFetchSize(SOURCE_FETCH_SIZE);
                long total;
                try (ResultSet sourceRows = sourceStatement.executeQuery(sourceSql)) {
                    ResultSetMetaData meta = sourceRows.getMetaData();
                    recreate(duck, staging, meta);
                    total = insertAll(duck, staging, sourceRows, meta);
                }
                swap(duck, staging, mart);
                return total;
            } finally {
                source.rollback(); // ends the read transaction; nothing was written
                source.setAutoCommit(sourceAutoCommit);
            }
        } catch (SQLException e) {
            throw new AnalyticsException("Materializing mart '" + martTable + "' failed "
                    + "(previous mart, if any, is untouched)", e);
        }
    }

    @Override
    public Path exportParquet(String selectSql, String fileName) {
        if (!SAFE_FILE_NAME.matcher(fileName).matches()) {
            throw new AnalyticsException("Snapshot file name must match " + SAFE_FILE_NAME, null);
        }
        Path snapshotDir = Path.of(properties.snapshotDir()).toAbsolutePath().normalize();
        Path target = snapshotDir.resolve(fileName).normalize();
        if (!target.startsWith(snapshotDir)) {
            throw new AnalyticsException("Snapshot path escapes the snapshot directory", null);
        }
        lock.lock();
        try (Statement statement = durableConnection().createStatement()) {
            Files.createDirectories(snapshotDir);
            statement.execute("COPY (" + selectSql + ") TO '"
                    + target.toString().replace("'", "''") + "' (FORMAT PARQUET)");
            return target;
        } catch (Exception e) {
            throw new AnalyticsException("Parquet export to " + fileName + " failed", e);
        } finally {
            lock.unlock();
        }
    }

    @PreDestroy
    void close() {
        lock.lock();
        try {
            if (durable != null) {
                durable.close();
                durable = null;
            }
        } catch (SQLException e) {
            log.warn("Closing the analytics database failed: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    // --- internals ---

    private Connection durableConnection() throws SQLException {
        if (durable == null || durable.isClosed()) {
            durable = open(properties.databasePath());
            log.info("Analytics database open at {} (threads={}, memory_limit={}, TimeZone=UTC)",
                    properties.databasePath(), properties.threads(), properties.memoryLimit());
        }
        return durable;
    }

    /**
     * Opens a capped DuckDB connection; a null path means an isolated in-memory instance.
     * threads/memory_limit are GLOBAL per DB instance (verified on 1.5.5): the durable file
     * instance is capped once for all its connections; every ephemeral instance is capped
     * separately — hence the concurrency permit bounding total footprint.
     */
    private Connection open(String path) throws SQLException {
        String url = path == null ? "jdbc:duckdb:" : "jdbc:duckdb:" + path;
        if (path != null) {
            try {
                Path parent = Path.of(path).toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (java.io.IOException e) {
                throw new SQLException("Cannot create analytics database directory", e);
            }
        }
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET threads = " + properties.threads());
            statement.execute("SET memory_limit = '" + properties.memoryLimit() + "'");
            statement.execute("SET TimeZone = 'UTC'"); // deterministic day buckets everywhere
            return connection;
        } catch (SQLException e) {
            connection.close(); // never leak a native instance on failed initialization
            throw e;
        }
    }

    private boolean acquireEphemeralPermit() {
        try {
            return ephemeralPermits.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void recreate(Connection duck, String table, ResultSetMetaData meta) throws SQLException {
        StringJoiner columns = new StringJoiner(", ");
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(quoteIdentifier(meta.getColumnLabel(i)) + " " + duckType(meta, i));
        }
        try (Statement statement = duck.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + table);
            statement.execute("CREATE TABLE " + table + " (" + columns + ")");
        }
    }

    /** Atomically replaces the mart with the fully-built staging table. */
    private void swap(Connection duck, String staging, String mart) throws SQLException {
        try (Statement statement = duck.createStatement()) {
            statement.execute("BEGIN TRANSACTION");
            statement.execute("DROP TABLE IF EXISTS " + mart);
            statement.execute("ALTER TABLE " + staging + " RENAME TO " + unquote(mart));
            statement.execute("COMMIT");
        }
    }

    private long insertAll(Connection duck, String table, ResultSet rows, ResultSetMetaData meta)
            throws SQLException {
        int columnCount = meta.getColumnCount();
        String placeholders = String.join(", ", Collections.nCopies(columnCount, "?"));
        long total = 0;
        try (PreparedStatement insert = duck.prepareStatement(
                "INSERT INTO " + table + " VALUES (" + placeholders + ")")) {
            int pending = 0;
            while (rows.next()) {
                for (int i = 1; i <= columnCount; i++) {
                    bindColumn(insert, i, rows, meta.getColumnType(i), isTimestampTz(meta, i));
                }
                insert.addBatch();
                total++;
                if (++pending == INSERT_BATCH_SIZE) {
                    insert.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                insert.executeBatch();
            }
        }
        return total;
    }

    /** pgjdbc reports timestamptz as plain Types.TIMESTAMP — the type NAME is the reliable signal. */
    private static boolean isTimestampTz(ResultSetMetaData meta, int column) throws SQLException {
        int type = meta.getColumnType(column);
        if (type == Types.TIMESTAMP_WITH_TIMEZONE) {
            return true;
        }
        return type == Types.TIMESTAMP && "timestamptz".equalsIgnoreCase(meta.getColumnTypeName(column));
    }

    private static void bindColumn(PreparedStatement insert, int index, ResultSet rows, int sqlType,
            boolean timestampTz) throws SQLException {
        if (rows.getObject(index) == null) {
            insert.setNull(index, Types.NULL);
            return;
        }
        if (timestampTz) {
            // instant-preserving: OffsetDateTime -> TIMESTAMPTZ (naive setTimestamp would store
            // JVM-local wall clock and shift day buckets per host)
            insert.setObject(index, rows.getObject(index, OffsetDateTime.class));
            return;
        }
        switch (sqlType) {
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT ->
                    insert.setLong(index, rows.getLong(index));
            // exact decimals stay exact — DOUBLE would silently corrupt monetary KPIs
            case Types.NUMERIC, Types.DECIMAL -> insert.setBigDecimal(index, rows.getBigDecimal(index));
            case Types.DOUBLE, Types.FLOAT, Types.REAL -> insert.setDouble(index, rows.getDouble(index));
            case Types.BOOLEAN, Types.BIT -> insert.setBoolean(index, rows.getBoolean(index));
            case Types.TIMESTAMP -> insert.setObject(index, rows.getObject(index, LocalDateTime.class));
            default -> insert.setString(index, rows.getString(index));
        }
    }

    private static String duckType(ResultSetMetaData meta, int column) throws SQLException {
        if (isTimestampTz(meta, column)) {
            return "TIMESTAMPTZ";
        }
        return switch (meta.getColumnType(column)) {
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT -> "BIGINT";
            case Types.NUMERIC, Types.DECIMAL -> decimalType(meta.getPrecision(column), meta.getScale(column));
            case Types.DOUBLE, Types.FLOAT, Types.REAL -> "DOUBLE";
            case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
            case Types.TIMESTAMP -> "TIMESTAMP";
            default -> "VARCHAR";
        };
    }

    private static String decimalType(int precision, int scale) {
        if (precision < 1 || precision > 38 || scale < 0 || scale > precision) {
            return "DECIMAL(38, 9)"; // unreported precision (e.g. unconstrained numeric)
        }
        return "DECIMAL(" + precision + ", " + scale + ")";
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String unquote(String quoted) {
        return quoted.substring(1, quoted.length() - 1).replace("\"\"", "\"");
    }

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    private static List<Map<String, Object>> rows(ResultSet resultSet) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = LinkedHashMap.newLinkedHashMap(meta.getColumnCount());
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                row.put(meta.getColumnLabel(i), resultSet.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }
}
