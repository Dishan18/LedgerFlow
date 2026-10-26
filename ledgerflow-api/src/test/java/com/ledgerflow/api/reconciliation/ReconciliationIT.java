package com.ledgerflow.api.reconciliation;

import static com.ledgerflow.api.support.TestData.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ledgerflow.api.outbox.OutboxEvent;
import com.ledgerflow.api.outbox.OutboxRepository;
import com.ledgerflow.api.payment.Payment;
import com.ledgerflow.api.payment.PaymentRepository;
import com.ledgerflow.api.payment.PaymentStatus;
import com.ledgerflow.api.support.AbstractIntegrationTest;
import com.ledgerflow.api.support.Eventually;
import com.ledgerflow.common.settlement.SettlementCsv;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** Phase 5: settlement file lands in S3 -> S3 event -> SQS -> reconciliation report, API and S3 copy. */
class ReconciliationIT extends AbstractIntegrationTest {

    private static final String BUCKET = "ledgerflow-settlements";

    @Autowired
    S3Client s3;

    @Autowired
    PaymentRepository payments;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    JdbcClient jdbc;

    private UUID merchant;
    private String customer;
    private LocalDate businessDate;

    @BeforeEach
    void setUp() {
        merchant = data.merchant(200);
        customer = data.customer(1_000_000);
        // A business date no other test uses, so its payments are exactly the ones created here.
        businessDate = LocalDate.of(2001, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(9_000));
    }

    @Test
    void settlementFileIsReconciledEndToEnd() throws Exception {
        List<Payment> ps = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID id = createPayment(1_000L * (i + 1));
            if (i < 2) {
                capture(id);
            }
            backdate(id);
            ps.add(payments.findById(id).orElseThrow());
        }
        UUID phantom = UUID.randomUUID();
        List<SettlementCsv.Row> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rows.add(row(ps.get(i), ps.get(i).getAmount()));       // 0-3 match (0, 1 are captured)
        }
        rows.add(row(ps.get(0), ps.get(0).getAmount()));           // 0 listed twice
        rows.add(row(ps.get(4), ps.get(4).getAmount() + 7));       // 4 amount mismatch
        rows.add(new SettlementCsv.Row(businessDate, "999999999999", phantom, "ghost@upi", 4_242)); // unknown
        // 5 is missing from the file

        s3.putObject(b -> b.bucket(BUCKET).key(SettlementCsv.key("settlements/", businessDate)),
                RequestBody.fromString(SettlementCsv.write(rows)));

        Eventually.await("report for " + businessDate, Duration.ofSeconds(30), () -> {
            try {
                return mvc.perform(get("/v1/reconciliation/{date}", businessDate).with(ops()))
                        .andReturn().getResponse().getStatus() == 200;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        mvc.perform(get("/v1/reconciliation/{date}", businessDate).with(ops()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.bankRows").value(7))
                .andExpect(jsonPath("$.summary.ledgerPayments").value(6))
                .andExpect(jsonPath("$.summary.matched").value(4))
                .andExpect(jsonPath("$.summary.duplicates").value(1))
                .andExpect(jsonPath("$.summary.amountMismatches").value(1))
                .andExpect(jsonPath("$.summary.missingInLedger").value(1))
                .andExpect(jsonPath("$.summary.missingInBank").value(1))
                .andExpect(jsonPath("$.discrepancies[?(@.type=='MISSING_IN_LEDGER')].paymentId").value(phantom.toString()))
                .andExpect(jsonPath("$.discrepancies[?(@.type=='MISSING_IN_BANK')].paymentId")
                        .value(ps.get(5).getId().toString()));

        // Matched captured payments are now SETTLED and announced; matched uncaptured ones just get settledAt.
        assertThat(payments.findById(ps.get(0).getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(payments.findById(ps.get(1).getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(payments.findById(ps.get(2).getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payments.findById(ps.get(2).getId()).orElseThrow().getSettledAt()).isNotNull();
        assertThat(payments.findById(ps.get(5).getId()).orElseThrow().getSettledAt()).isNull();
        assertThat(outbox.findByAggregateIdOrderByCreatedAt(ps.get(0).getId()))
                .extracting(OutboxEvent::getEventType).contains("payment.settled");

        String copy = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("reports/" + businessDate + ".json")).asUtf8String();
        assertThat((Integer) JsonPath.read(copy, "$.summary.matched")).isEqualTo(4);
    }

    @Test
    void settledPaymentCanStillBeRefunded() throws Exception {
        UUID id = createPayment(5_000);
        capture(id);
        backdate(id);
        Payment p = payments.findById(id).orElseThrow();
        s3.putObject(b -> b.bucket(BUCKET).key(SettlementCsv.key("settlements/", businessDate)),
                RequestBody.fromString(SettlementCsv.write(List.of(row(p, p.getAmount())))));
        Eventually.await("settled", Duration.ofSeconds(30),
                () -> payments.findById(id).orElseThrow().getStatus() == PaymentStatus.SETTLED);

        mvc.perform(post("/v1/payments/{id}/refunds", id).with(as(merchant))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":2000}"))
                .andExpect(status().isCreated());
        Payment refunded = payments.findById(id).orElseThrow();
        assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(refunded.getSettledAt()).isNotNull();
    }

    @Test
    void merchantsCannotReadReconciliation() throws Exception {
        mvc.perform(get("/v1/reconciliation/{date}", businessDate).with(as(merchant)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/reconciliation/{date}", businessDate).with(ops()))
                .andExpect(status().isNotFound());
    }

    private RequestPostProcessor ops() {
        return jwt().jwt(j -> j.subject("ops-user")).authorities(new SimpleGrantedAuthority("SCOPE_ops"));
    }

    private UUID createPayment(long amount) throws Exception {
        String body = mvc.perform(post("/v1/payments").with(as(merchant))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"amount\":" + amount + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void capture(UUID id) throws Exception {
        mvc.perform(post("/v1/payments/{id}/capture", id).with(as(merchant))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
    }

    /** Moves the payment to noon IST on the test's business date. */
    private void backdate(UUID id) {
        jdbc.sql("UPDATE payment SET created_at = :at WHERE id = :id")
                .param("at", Timestamp.from(businessDate.atTime(LocalTime.NOON).atZone(SettlementCsv.BUSINESS_ZONE).toInstant()))
                .param("id", id)
                .update();
    }

    private SettlementCsv.Row row(Payment p, long amount) {
        return new SettlementCsv.Row(businessDate, p.getBankReference(), p.getId(), p.getCustomerId(), amount);
    }
}
