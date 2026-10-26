package com.ledgerflow.api.reconciliation;

import com.ledgerflow.api.outbox.PaymentEventPublisher;
import com.ledgerflow.api.payment.Payment;
import com.ledgerflow.api.payment.PaymentRepository;
import com.ledgerflow.api.payment.PaymentStatus;
import com.ledgerflow.common.settlement.SettlementCsv;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Reconciles one settlement file: read it from S3, load LedgerFlow's side, match, then store the
 * report, mark matched payments settled and write a JSON copy of the report back to S3.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final S3Client s3;
    private final PaymentRepository payments;
    private final ReconciliationReportRepository reports;
    private final PaymentEventPublisher events;
    private final ReconciliationProperties properties;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MeterRegistry meters;

    public ReconciliationService(S3Client s3,
                                 PaymentRepository payments,
                                 ReconciliationReportRepository reports,
                                 PaymentEventPublisher events,
                                 ReconciliationProperties properties,
                                 TransactionTemplate tx,
                                 ObjectMapper objectMapper,
                                 Clock clock,
                                 MeterRegistry meters) {
        this.s3 = s3;
        this.payments = payments;
        this.reports = reports;
        this.events = events;
        this.properties = properties;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.meters = meters;
    }

    public ReconciliationReport reconcileFile(String bucket, String key) {
        LocalDate businessDate = SettlementCsv.dateFromKey(key);
        String csv = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .asString(StandardCharsets.UTF_8);
        List<SettlementCsv.Row> rows = SettlementCsv.parse(csv);

        Reconciler.Outcome outcome = tx.execute(status -> {
            Map<UUID, Reconciler.LedgerPayment> ledgerById = new HashMap<>();
            Set<UUID> collectedOnDate = new LinkedHashSet<>();
            Instant from = businessDate.atStartOfDay(SettlementCsv.BUSINESS_ZONE).toInstant();
            Instant to = businessDate.plusDays(1).atStartOfDay(SettlementCsv.BUSINESS_ZONE).toInstant();
            for (Payment p : payments.findCollectedBetween(from, to)) {
                ledgerById.put(p.getId(), toLedger(p));
                collectedOnDate.add(p.getId());
            }
            // Bank rows may reference payments from another day (a collect just before midnight)
            // or that LedgerFlow considers failed; look those up directly.
            Set<UUID> others = new LinkedHashSet<>();
            for (SettlementCsv.Row row : rows) {
                if (!ledgerById.containsKey(row.paymentId())) {
                    others.add(row.paymentId());
                }
            }
            for (Payment p : payments.findAllById(others)) {
                ledgerById.put(p.getId(), toLedger(p));
            }

            Reconciler.Outcome result = Reconciler.reconcile(UUID.randomUUID(), businessDate, key, clock.instant(),
                    rows, ledgerById, collectedOnDate);
            reports.save(result.report(), objectMapper.writeValueAsString(result.report()));
            settle(result.matched());
            return result;
        });

        ReconciliationReport report = outcome.report();
        s3.putObject(PutObjectRequest.builder()
                        .bucket(properties.reportsBucket())
                        .key(properties.reportsPrefix() + businessDate + ".json")
                        .contentType("application/json")
                        .build(),
                RequestBody.fromString(objectMapper.writeValueAsString(report)));
        meters.counter("ledgerflow.reconciliation.discrepancies").increment(report.discrepancies().size());
        log.info("Reconciled {}: {}", key, report.summary());
        return report;
    }

    private void settle(List<UUID> matched) {
        Instant now = clock.instant();
        for (UUID paymentId : matched) {
            payments.lockById(paymentId).ifPresent(payment -> {
                if (payment.markSettled(now)) {
                    events.publish("payment.settled", payment);
                }
            });
        }
    }

    private static Reconciler.LedgerPayment toLedger(Payment p) {
        boolean collected = p.getBankReference() != null
                && p.getStatus() != PaymentStatus.FAILED
                && !p.getStatus().isAwaitingBank();
        return new Reconciler.LedgerPayment(p.getId(), p.getAmount(), p.getStatus().name(), collected);
    }
}
