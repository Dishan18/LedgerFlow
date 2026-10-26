package com.ledgerflow.api.payment;

import static com.ledgerflow.api.support.TestData.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ledgerflow.api.ledger.JournalEntryRepository;
import com.ledgerflow.api.outbox.OutboxEvent;
import com.ledgerflow.api.outbox.OutboxRepository;
import com.ledgerflow.api.support.AbstractIntegrationTest;
import com.ledgerflow.api.support.ConcurrentRunner;
import com.ledgerflow.api.support.FakeBank;
import com.ledgerflow.common.bank.CollectStatus;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Phase 3: LedgerFlow against a bank that is slow, down or undecided. */
class UnreliableBankIT extends AbstractIntegrationTest {

    @Autowired
    PaymentStatusPoller poller;

    @Autowired
    PaymentRepository payments;

    @Autowired
    JournalEntryRepository journalEntries;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    JdbcClient jdbc;

    private UUID merchant;
    private String customer;

    @BeforeEach
    void setUp() {
        merchant = data.merchant(200);
        customer = data.customer(100_000);
    }

    @Test
    void lostBankResponseIsConfirmedLaterByThePoller() throws Exception {
        BANK.onCollect(FakeBank.approveAfter(Duration.ofSeconds(1))); // > 300ms read timeout

        UUID id = create(5_000).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andReturn().getResponse().getContentAsString().transform(UnreliableBankIT::id);

        // Three attempts, all with the same payment id, and the bank collected exactly once.
        assertThat(BANK.collectAttempts(id)).isEqualTo(3);
        assertThat(BANK.knows(id)).isTrue();

        makeDue(id);
        poller.pollOnce();

        Payment payment = payments.findById(id).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getBankReference()).isNotBlank();
        assertThat(payment.getNextStatusCheckAt()).isNull();
        assertThat(eventTypes(id)).containsExactly(
                "payment.created", "payment.pending_confirmation", "payment.authorized");

        capture(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    void declinedPaymentFailsWithoutTouchingTheLedger() throws Exception {
        BANK.onCollect(FakeBank.decline("INSUFFICIENT_BALANCE"));

        UUID id = create(5_000).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("INSUFFICIENT_BALANCE"))
                .andReturn().getResponse().getContentAsString().transform(UnreliableBankIT::id);

        assertThat(BANK.collectAttempts(id)).isEqualTo(1);
        assertThat(journalEntries.findByPaymentIdOrderByCreatedAt(id)).isEmpty();
        capture(id).andExpect(status().isConflict());
    }

    @Test
    void briefOutageIsRiddenOutByRetries() throws Exception {
        BANK.onCollect(FakeBank.flaky(1));

        UUID id = create(5_000).andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andReturn().getResponse().getContentAsString().transform(UnreliableBankIT::id);

        assertThat(BANK.collectAttempts(id)).isEqualTo(2);
    }

    @Test
    void bankThatNeverHeardOfThePaymentFailsItAfterTheGracePeriod() throws Exception {
        BANK.onCollect(FakeBank.failWith(503));
        UUID id = create(5_000).andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andReturn().getResponse().getContentAsString().transform(UnreliableBankIT::id);

        makeDue(id);
        poller.pollOnce();
        assertThat(statusOf(id)).isEqualTo(PaymentStatus.PENDING_CONFIRMATION); // still within grace

        age(id, Duration.ofMinutes(3));
        makeDue(id);
        poller.pollOnce();

        Payment payment = payments.findById(id).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("BANK_HAS_NO_RECORD");
    }

    @Test
    void pendingCollectIsFollowedUpWithBackoffUntilTheBankDecides() throws Exception {
        BANK.onCollect(FakeBank.pending());
        UUID id = create(5_000).andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andReturn().getResponse().getContentAsString().transform(UnreliableBankIT::id);

        makeDue(id);
        poller.pollOnce();
        Duration firstGap = gapUntilNextCheck(id);
        makeDue(id);
        poller.pollOnce();
        Duration secondGap = gapUntilNextCheck(id);

        assertThat(statusOf(id)).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
        assertThat(secondGap).isGreaterThan(firstGap); // exponential backoff per payment

        BANK.resolve(id, CollectStatus.DECLINED, "CUSTOMER_REJECTED");
        makeDue(id);
        poller.pollOnce();

        Payment payment = payments.findById(id).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("CUSTOMER_REJECTED");
        assertThat(payment.getStatusCheckAttempts()).isEqualTo(3);
    }

    @Test
    void paymentPendingForTooLongIsFailed() throws Exception {
        BANK.onCollect(FakeBank.pending());
        UUID id = create(5_000).andReturn().getResponse().getContentAsString().transform(UnreliableBankIT::id);

        age(id, Duration.ofMinutes(31));
        makeDue(id);
        poller.pollOnce();

        assertThat(payments.findById(id).orElseThrow().getFailureReason()).isEqualTo("CONFIRMATION_TIMEOUT");
    }

    @Test
    void paymentOrphanedInCreatedIsRecovered() {
        // Simulates the app dying between committing the payment and hearing from the bank.
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO payment (id, merchant_id, customer_id, amount, fee_amount, status, next_status_check_at)
                        VALUES (:id, :merchant, :customer, 5000, 100, 'CREATED', :due)
                        """)
                .param("id", id).param("merchant", merchant).param("customer", customer)
                .param("due", Timestamp.from(Instant.now().minusSeconds(1))).update();
        BANK.resolve(id, CollectStatus.APPROVED, "998877665544");

        poller.pollOnce();

        Payment payment = payments.findById(id).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getBankReference()).isEqualTo("998877665544");
    }

    @Test
    void concurrentPollersCheckEachPaymentOnlyOnce() throws Exception {
        BANK.onCollect(FakeBank.pending());
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(create(1_000).andReturn().getResponse().getContentAsString().transform(UnreliableBankIT::id));
        }
        ids.forEach(this::makeDue);

        List<Callable<Integer>> pollers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pollers.add(poller::pollOnce);
        }
        ConcurrentRunner.runAll(pollers);

        assertThat(ids).allSatisfy(id -> assertThat(BANK.statusChecks(id)).isEqualTo(1));
    }

    private ResultActions create(long amount) throws Exception {
        return mvc.perform(post("/v1/payments").with(as(merchant))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + customer + "\",\"amount\":" + amount + "}"));
    }

    private ResultActions capture(UUID id) throws Exception {
        return mvc.perform(post("/v1/payments/{id}/capture", id).with(as(merchant))
                .header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private static UUID id(String json) {
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private PaymentStatus statusOf(UUID id) {
        return payments.findById(id).orElseThrow().getStatus();
    }

    private List<String> eventTypes(UUID id) {
        return outbox.findByAggregateIdOrderByCreatedAt(id).stream().map(OutboxEvent::getEventType).toList();
    }

    /** Uses the JVM clock like the app does; the database container's clock may drift from it. */
    private void makeDue(UUID id) {
        jdbc.sql("UPDATE payment SET next_status_check_at = :due WHERE id = :id")
                .param("due", Timestamp.from(Instant.now().minusSeconds(1))).param("id", id).update();
    }

    private void age(UUID id, Duration age) {
        jdbc.sql("UPDATE payment SET created_at = :at WHERE id = :id")
                .param("at", Timestamp.from(Instant.now().minus(age))).param("id", id).update();
    }

    private Duration gapUntilNextCheck(UUID id) {
        Instant next = payments.findById(id).orElseThrow().getNextStatusCheckAt();
        return Duration.between(Instant.now(), next);
    }
}
