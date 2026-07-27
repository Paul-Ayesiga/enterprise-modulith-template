package ug.co.smsone.analytics.internal;

import jakarta.annotation.PreDestroy;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import ug.co.smsone.analytics.AnalyticsEngine;
import ug.co.smsone.analytics.AnalyticsException;

/**
 * Embedded DuckDB engine. One guarded connection owns the durable mart file (DuckDB is
 * single-process; the lock serializes statements); ephemeral queries get their own throwaway
 * in-memory database. Both apply the thread/memory caps so analytics can never starve the JVM.
 */
@Component
class DuckDbAnalyticsEngine implements AnalyticsEngine {

    private static final Logger log = LoggerFactory.getLogger(DuckDbAnalyticsEngine.class);
    private static final int INSERT_BATCH_SIZE = 500;

    private final AnalyticsProperties properties;
    private final DataSource postgres;
    private final ReentrantLock lock = new ReentrantLock();
    private Connection durable;

    DuckDbAnalyticsEngine(AnalyticsProperties properties, DataSource postgres) {
        this.properties = properties;
        this.postgres = postgres;
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
        try (Connection ephemeral = open(null);
                PreparedStatement statement = ephemeral.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                return rows(resultSet);
            }
        } catch (SQLException e) {
            throw new AnalyticsException("Ephemeral analytics query failed", e);
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
        String qualified = quoteIdentifier(martTable);
        lock.lock();
        try (Connection source = postgres.getConnection();
                Statement sourceStatement = source.createStatement()) {
            sourceStatement.setFetchSize(1000);
            try (ResultSet rows = sourceStatement.executeQuery(sourceSql)) {
                ResultSetMetaData meta = rows.getMetaData();
                recreateMart(qualified, meta);
                return insertAll(qualified, rows, meta);
            }
        } catch (SQLException e) {
            throw new AnalyticsException("Materializing mart '" + martTable + "' failed", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Path exportParquet(String selectSql, String fileName) {
        Path target = Path.of(properties.snapshotDir()).resolve(fileName).toAbsolutePath();
        lock.lock();
        try (Statement statement = durableConnection().createStatement()) {
            Files.createDirectories(target.getParent());
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

    // --- internals (callers hold the lock where required) ---

    private Connection durableConnection() throws SQLException {
        if (durable == null || durable.isClosed()) {
            durable = open(properties.databasePath());
            log.info("Analytics database open at {} (threads={}, memory_limit={})",
                    properties.databasePath(), properties.threads(), properties.memoryLimit());
        }
        return durable;
    }

    /** Opens a capped DuckDB connection; a null path means in-memory. */
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
        }
        return connection;
    }

    private void recreateMart(String qualified, ResultSetMetaData meta) throws SQLException {
        StringJoiner columns = new StringJoiner(", ");
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(quoteIdentifier(meta.getColumnLabel(i)) + " " + duckType(meta.getColumnType(i)));
        }
        try (Statement statement = durableConnection().createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + qualified);
            statement.execute("CREATE TABLE " + qualified + " (" + columns + ")");
        }
    }

    private long insertAll(String qualified, ResultSet rows, ResultSetMetaData meta) throws SQLException {
        int columnCount = meta.getColumnCount();
        String placeholders = String.join(", ", java.util.Collections.nCopies(columnCount, "?"));
        long total = 0;
        try (PreparedStatement insert = durableConnection()
                .prepareStatement("INSERT INTO " + qualified + " VALUES (" + placeholders + ")")) {
            int pending = 0;
            while (rows.next()) {
                for (int i = 1; i <= columnCount; i++) {
                    bindColumn(insert, i, rows, meta.getColumnType(i));
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

    private static void bindColumn(PreparedStatement insert, int index, ResultSet rows, int sqlType)
            throws SQLException {
        Object raw = rows.getObject(index);
        if (raw == null) {
            insert.setNull(index, Types.NULL);
            return;
        }
        switch (sqlType) {
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT ->
                    insert.setLong(index, rows.getLong(index));
            case Types.DOUBLE, Types.FLOAT, Types.REAL, Types.NUMERIC, Types.DECIMAL ->
                    insert.setDouble(index, rows.getDouble(index));
            case Types.BOOLEAN, Types.BIT -> insert.setBoolean(index, rows.getBoolean(index));
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
                    insert.setTimestamp(index, rows.getTimestamp(index));
            default -> insert.setString(index, rows.getString(index));
        }
    }

    private static String duckType(int sqlType) {
        return switch (sqlType) {
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT -> "BIGINT";
            case Types.DOUBLE, Types.FLOAT, Types.REAL, Types.NUMERIC, Types.DECIMAL -> "DOUBLE";
            case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP";
            default -> "VARCHAR";
        };
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
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
