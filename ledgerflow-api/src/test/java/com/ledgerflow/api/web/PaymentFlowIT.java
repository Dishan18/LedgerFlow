package com.ledgerflow.api.web;

import static com.ledgerflow.api.support.TestData.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ledgerflow.api.ledger.AccountType;
import com.ledgerflow.api.ledger.EntryType;
import com.ledgerflow.api.ledger.JournalEntry;
import com.ledgerflow.api.ledger.JournalEntryRepository;
import com.ledgerflow.api.outbox.OutboxEvent;
import com.ledgerflow.api.outbox.OutboxRepository;
import com.ledgerflow.api.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class PaymentFlowIT extends AbstractIntegrationTest {

    @Autowired
    JournalEntryRepository journalEntries;

    @Autowired
    OutboxRepository outbox;

    private UUID merchant;
    private String customer;

    @BeforeEach
    void setUp() {
        merchant = data.merchant(200); // 2% fee
        customer = data.customer(100_000); // Rs 1,000
    }

    @Test
    void createCaptureAndRefundMovesMoneyThroughTheLedger() throws Exception {
        String paymentId = createPayment(10_000)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/v1/payments/")))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.feeAmount").value(200))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(paymentId, "$.id"));

        capture(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));

        assertThat(data.balance(AccountType.CUSTOMER, customer)).isEqualTo(90_000);
        assertThat(data.balance(AccountType.MERCHANT_RECEIVABLE, merchant.toString())).isEqualTo(9_800);

        refund(id, 2_500).andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(2_500));
        mvc.perform(get("/v1/payments/{id}", id).with(as(merchant)))
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(2_500));

        refund(id, 7_500).andExpect(status().isCreated());
        mvc.perform(get("/v1/payments/{id}", id).with(as(merchant)))
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        // Everything is back where it started, including the full fee.
        assertThat(data.balance(AccountType.CUSTOMER, customer)).isEqualTo(100_000);
        assertThat(data.balance(AccountType.MERCHANT_RECEIVABLE, merchant.toString())).isZero();

        List<JournalEntry> entries = journalEntries.findByPaymentIdOrderByCreatedAt(id);
        assertThat(entries).extracting(JournalEntry::getEntryType)
                .containsExactly(EntryType.CAPTURE, EntryType.REFUND, EntryType.REFUND);
        UUID captureId = entries.getFirst().getId();
        assertThat(entries.subList(1, 3)).allSatisfy(e -> assertThat(e.getReversesEntryId()).isEqualTo(captureId));

        assertThat(outbox.findByAggregateIdOrderByCreatedAt(id)).extracting(OutboxEvent::getEventType)
                .containsExactly("payment.created", "payment.authorized", "payment.captured",
                        "payment.refunded", "payment.refunded");
    }

    @Test
    void refundCannotExceedCapturedAmount() throws Exception {
        UUID id = capturedPayment(5_000);
        refund(id, 3_000).andExpect(status().isCreated());

        refund(id, 2_001)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_CAPTURED"));
    }

    @Test
    void cannotRefundBeforeCapture() throws Exception {
        UUID id = UUID.fromString(JsonPath.read(createPayment(5_000).andReturn().getResponse().getContentAsString(), "$.id"));

        refund(id, 100)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void cannotCaptureTwice() throws Exception {
        UUID id = capturedPayment(5_000);

        capture(id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        assertThat(data.balance(AccountType.CUSTOMER, customer)).isEqualTo(95_000);
    }

    @Test
    void captureWithInsufficientFundsChangesNothing() throws Exception {
        String poorCustomer = data.customer(1_000);
        String body = mvc.perform(post("/v1/payments").with(as(merchant))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + poorCustomer + "\",\"amount\":5000}"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

        capture(id)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        // The whole transaction rolled back: status, ledger and outbox are untouched.
        mvc.perform(get("/v1/payments/{id}", id).with(as(merchant))).andExpect(jsonPath("$.status").value("AUTHORIZED"));
        assertThat(data.balance(AccountType.CUSTOMER, poorCustomer)).isEqualTo(1_000);
        assertThat(journalEntries.findByPaymentIdOrderByCreatedAt(id)).isEmpty();
    }

    @Test
    void merchantsCannotSeeEachOthersPayments() throws Exception {
        UUID id = capturedPayment(1_000);
        UUID otherMerchant = data.merchant(100);

        mvc.perform(get("/v1/payments/{id}", id).with(as(otherMerchant)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void unknownCustomerIsRejected() throws Exception {
        mvc.perform(post("/v1/payments").with(as(merchant))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"nobody\",\"amount\":100}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CUSTOMER_ACCOUNT_NOT_FOUND"));
    }

    @Test
    void invalidBodyIsRejected() throws Exception {
        mvc.perform(post("/v1/payments").with(as(merchant))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"amount\":-5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestsWithoutTokenAreUnauthorized() throws Exception {
        mvc.perform(get("/v1/payments/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void postWithoutIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/v1/payments").with(as(merchant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"amount\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    private UUID capturedPayment(long amount) throws Exception {
        String body = createPayment(amount).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        capture(id).andExpect(status().isOk());
        return id;
    }

    private ResultActions createPayment(long amount) throws Exception {
        return mvc.perform(post("/v1/payments").with(as(merchant))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + customer + "\",\"amount\":" + amount + "}"));
    }

    private ResultActions capture(UUID id) throws Exception {
        return mvc.perform(post("/v1/payments/{id}/capture", id).with(as(merchant))
                .header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private ResultActions refund(UUID id, long amount) throws Exception {
        return mvc.perform(post("/v1/payments/{id}/refunds", id).with(as(merchant))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + "}"));
    }
}
