package com.ledgerflow.api.payment;

import com.ledgerflow.api.bank.AuthorizationResult;
import com.ledgerflow.api.bank.BankGateway;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Follows up payments the bank hasn't given a final answer for.
 *
 * <p>Several app instances can run this at once. Each round <em>claims</em> due payments with
 * {@code FOR UPDATE SKIP LOCKED} and pushes their next check time forward by a lease, in one
 * auto-committed statement. Other instances skip rows that are locked or leased, so a payment is
 * checked by one instance at a time, and no database lock is held while we wait on the bank.
 * If an instance dies mid-check, the lease expires and another instance picks the payment up.
 */
@Component
public class PaymentStatusPoller {

    private static final Logger log = LoggerFactory.getLogger(PaymentStatusPoller.class);

    private final JdbcClient jdbc;
    private final BankGateway bank;
    private final PaymentRepository payments;
    private final PaymentConfirmationService confirmation;
    private final TransactionTemplate tx;
    private final StatusPollerProperties properties;
    private final Clock clock;

    public PaymentStatusPoller(JdbcClient jdbc,
                               BankGateway bank,
                               PaymentRepository payments,
                               PaymentConfirmationService confirmation,
                               TransactionTemplate tx,
                               StatusPollerProperties properties,
                               Clock clock) {
        this.jdbc = jdbc;
        this.bank = bank;
        this.payments = payments;
        this.confirmation = confirmation;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs one round. Returns how many payments were checked.
     *
     * <p>The bank calls in a batch run in parallel on virtual threads: each one is mostly waiting on
     * the network, and one at a time a batch of 50 slow answers would take far longer than the
     * polling interval. Each check is independent and records its result in its own transaction.
     */
    public int pollOnce() {
        List<UUID> due = claimDue(clock.instant());
        try (ExecutorService checks = Executors.newVirtualThreadPerTaskExecutor()) {
            for (UUID paymentId : due) {
                checks.submit(() -> {
                    try {
                        check(paymentId);
                    } catch (RuntimeException e) {
                        // Leave it; the lease expires and a later round tries again.
                        log.error("Status check failed for payment {}", paymentId, e);
                    }
                });
            }
        } // waits for every check to finish
        return due.size();
    }

    private List<UUID> claimDue(Instant now) {
        return jdbc.sql("""
                        UPDATE payment
                           SET next_status_check_at = :leaseUntil
                         WHERE id IN (SELECT id
                                        FROM payment
                                       WHERE status IN ('CREATED', 'PENDING_CONFIRMATION')
                                         AND next_status_check_at <= :now
                                       ORDER BY next_status_check_at
                                       LIMIT :batch
                                         FOR UPDATE SKIP LOCKED)
                        RETURNING id
                        """)
                .param("now", Timestamp.from(now))
                .param("leaseUntil", Timestamp.from(now.plus(properties.lease())))
                .param("batch", properties.batchSize())
                .query(UUID.class)
                .list();
    }

    private void check(UUID paymentId) {
        // Network call first, outside any transaction; then a short transaction to record the answer.
        AuthorizationResult result = bank.checkStatus(paymentId);
        tx.executeWithoutResult(status -> payments.lockById(paymentId)
                .ifPresent(payment -> confirmation.applyStatusCheck(payment, result)));
    }
}
