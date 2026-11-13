package com.example.Ledger.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@TestPropertySource(properties = "ledger.outbox.dispatcher.enabled=false")
public abstract class PostgresIntegrationTestSupport {

    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("ledger_test")
            .withUsername("ledger_user")
            .withPassword("ledger_pass");

    static {
        POSTGRESQL.start();
    }

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }
}