package com.ledgerflow.api.support;

import java.time.Duration;
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
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Boots the whole app against real PostgreSQL and LocalStack (SNS, SQS, Secrets Manager) in
 * Docker. Containers start once per JVM and are shared by every integration test class (and by
 * Spring's cached context).
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
                "ledgerflow.status-poller.enabled=false",
                // Fast event pipeline: relay often, retry webhooks after 1s instead of minutes.
                "ledgerflow.outbox.poll-interval=200ms",
                "ledgerflow.webhooks.initial-backoff=1s",
                "ledgerflow.webhooks.max-backoff=1s"
        })
@AutoConfigureMockMvc
@Import({TestData.class, LedgerAudit.class})
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    /** Same image and init script as docker-compose.yml, so tests run against the same topology. */
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.14.0"))
            .withEnv("SERVICES", "sns,sqs,s3,secretsmanager")
            .withEnv("AWS_DEFAULT_REGION", "ap-south-1")
            .withEnv("SQS_ENDPOINT_STRATEGY", "path")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../infra/localstack/init-aws.sh", 0755),
                    "/etc/localstack/init/ready.d/init-aws.sh")
            .waitingFor(Wait.forLogMessage(".*LedgerFlow LocalStack resources ready.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    /** Must match the secret created by init-aws.sh. */
    protected static final String WEBHOOK_SECRET = "whsec_local_dev_secret_change_me";

    protected static final FakeBank BANK = FakeBank.start();

    static {
        Startables.deepStart(POSTGRES, LOCALSTACK).join();
    }

    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        registry.add("ledgerflow.bank.base-url", BANK::baseUrl);
        registry.add("spring.cloud.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.region.static", () -> "ap-south-1");
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
        registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
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
