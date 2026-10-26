package com.ledgerflow.api.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the whole app against a real PostgreSQL in Docker. The container is started once per
 * JVM and shared by every integration test class (and by Spring's cached context).
 *
 * <p>Tests never clean tables between runs: journal tables are append-only by design, so each
 * test creates its own merchant and customers with fresh ids instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected TestData data;
}
