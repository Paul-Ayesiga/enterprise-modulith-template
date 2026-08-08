package ug.co.smsone.shared.persistence;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

/**
 * Two DataSource beans, and which one a caller gets is the whole point (ADR 0010 §3.1).
 *
 * <ul>
 *   <li><strong>{@code dataSource} — {@code @Primary}, the routing one.</strong> Everything that
 *       injects a {@code DataSource}, a {@code JdbcTemplate} or an {@code EntityManager} gets this,
 *       which is how one mechanism covers Hibernate, the queues, the purge jobs, ShedLock and the
 *       Modulith event registry alike.
 *   <li><strong>{@code poolDataSource} — the raw Hikari pool, {@code @FlywayDataSource}, not
 *       primary.</strong> Flyway must never migrate through the router: it runs with no tenant
 *       pinned, so the router would put it in {@code no_tenant} and Flyway would fail with "Unable to
 *       determine current schema as search_path is empty" — a message that names this exact mistake.
 * </ul>
 *
 * <p>Declaring a {@code DataSource} bean switches Boot's own {@code DataSourceAutoConfiguration} pool
 * off, so the pool below has to be built the way Boot builds it. The part worth reading twice is the
 * order: <strong>{@link JdbcConnectionDetails} first, {@code spring.datasource.*} only as the
 * fallback.</strong> That bean is how Testcontainers' {@code @ServiceConnection} and the Docker
 * Compose support hand us a URL nobody wrote in a file; consulting the properties first would quietly
 * point every integration test at whatever {@code POSTGRES_HOST} happens to be.
 */
@Configuration(proxyBeanMethods = false)
class TenantRoutingDataSourceConfig {

    @Bean
    @FlywayDataSource
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource poolDataSource(
            DataSourceProperties properties, ObjectProvider<JdbcConnectionDetails> connectionDetails) {
        JdbcConnectionDetails details = connectionDetails.getIfAvailable();
        DataSourceBuilder<?> builder = details != null
                ? DataSourceBuilder.create(properties.getClassLoader())
                        .driverClassName(details.getDriverClassName())
                        .url(details.getJdbcUrl())
                        .username(details.getUsername())
                        .password(details.getPassword())
                : properties.initializeDataSourceBuilder();
        HikariDataSource pool = builder.type(HikariDataSource.class).build();
        if (StringUtils.hasText(properties.getName())) {
            pool.setPoolName(properties.getName());
        }
        return pool;
    }

    @Bean
    @Primary
    DataSource dataSource(HikariDataSource poolDataSource) {
        return new TenantRoutingDataSource(poolDataSource);
    }
}
