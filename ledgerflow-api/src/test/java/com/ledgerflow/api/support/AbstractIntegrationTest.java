package com.ledgerflow.api.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the whole app against a real PostgreSQL in Docker. The container is started once per
 * JVM and shared by every integration test class (and by Spring's cached context).
 *
 * <p>The app listens on a real random port, so tests can use either MockMvc (fast, in-process)
 * or a real HTTP client (needed when many requests must truly run in parallel).
 *
 * <p>Tests never clean tables between runs: journal tables are append-only by design, so each
 * test creates its own merchant and customers with fresh ids instead.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Enough connections for the concurrency tests to put real contention on the database.
                "spring.datasource.hikari.maximum-pool-size=50",
                // Retry storms open hundreds of TCP connections at once. Tomcat's default accept queue
                // (100) makes Windows refuse the overflow outright instead of queueing it like Linux.
                "server.tomcat.accept-count=1000",
                // Fast bank timeouts and backoff so failure scenarios run in milliseconds.
                "ledgerflow.bank.connect-timeout=300ms",
                "ledgerflow.bank.read-timeout=300ms",
                "ledgerflow.bank.initial-backoff=10ms",
                "ledgerflow.bank.max-backoff=50ms",
                "ledgerflow.status-poller.first-check-after=1s",
                // Tests drive the poller by hand so nothing changes state behind their back.
                "ledgerflow.status-poller.enabled=false"
        })
@AutoConfigureMockMvc
@Import({TestData.class, LedgerAudit.class})
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    protected static final FakeBank BANK = FakeBank.start();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void bankUrl(DynamicPropertyRegistry registry) {
        registry.add("ledgerflow.bank.base-url", BANK::baseUrl);
    }

    @BeforeEach
    void resetBank() {
        BANK.reset();
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected TestData data;

    @Autowired
    protected LedgerAudit audit;

    @LocalServerPort
    protected int port;
}
