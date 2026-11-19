package io.kitchensink.backfill.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SourceDataSourceConfiguration {
    @Bean(defaultCandidate = false)
    @ConfigurationProperties("kitchensink.backfill.source")
    HikariDataSource memberSource() {
        return new HikariDataSource();
    }
}
