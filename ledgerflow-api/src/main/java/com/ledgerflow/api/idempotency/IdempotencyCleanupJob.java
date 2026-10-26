package com.ledgerflow.api.idempotency;

import com.ledgerflow.api.config.LedgerflowProperties;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes idempotency keys older than the retention window (24h by default). */
@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyRepository repository;
    private final LedgerflowProperties properties;
    private final Clock clock;

    public IdempotencyCleanupJob(IdempotencyRepository repository, LedgerflowProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${ledgerflow.idempotency.cleanup-cron}")
    public int purgeExpiredKeys() {
        Instant cutoff = clock.instant().minus(properties.idempotency().retention());
        int deleted = repository.deleteOlderThan(cutoff);
        log.info("Purged {} idempotency keys created before {}", deleted, cutoff);
        return deleted;
    }
}
