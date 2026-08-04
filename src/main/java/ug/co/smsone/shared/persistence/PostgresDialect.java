package ug.co.smsone.shared.persistence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The template's default {@link DbDialect}. Registered via {@code @ConditionalOnMissingBean}, so a
 * product targeting another RDBMS supplies its own {@code DbDialect} bean and that one wins — no
 * change to this file.
 */
public class PostgresDialect implements DbDialect {

    @Override
    public String skipLocked() {
        return "for update skip locked";
    }

    @Configuration(proxyBeanMethods = false)
    static class DialectConfiguration {

        @Bean
        @ConditionalOnMissingBean(DbDialect.class)
        DbDialect dbDialect() {
            return new PostgresDialect();
        }
    }
}
