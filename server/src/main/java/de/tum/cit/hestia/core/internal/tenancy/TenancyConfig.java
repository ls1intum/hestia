package de.tum.cit.hestia.core.internal.tenancy;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two layers that keep organisations apart: Hibernate's tenant filter on every
 * {@code core.OrgScoped} entity, and the organisation on every database connection for row-level
 * security.
 */
@Configuration(proxyBeanMethods = false)
class TenancyConfig {

    @Bean
    HibernatePropertiesCustomizer orgTenantResolver() {
        return properties -> properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, new OrgTenantResolver());
    }

    /** As Spring Boot would build the pool from {@code spring.datasource.*}, but org-aware. */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    OrgAwareDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(OrgAwareDataSource.class).build();
    }
}
