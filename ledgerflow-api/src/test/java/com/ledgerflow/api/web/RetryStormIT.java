package com.ledgerflow.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.ledgerflow.api.idempotency.IdempotencyService;
import com.ledgerflow.api.ledger.AccountType;
import com.ledgerflow.api.ledger.EntryType;
import com.ledgerflow.api.ledger.JournalEntry;
import com.ledgerflow.api.ledger.JournalEntryRepository;
import com.ledgerflow.api.support.AbstractIntegrationTest;
import com.ledgerflow.api.support.ConcurrentRunner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Phase 2 retry-storm proof, over real HTTP: clients that retry aggressively (same key, or
 * buggy clients that send fresh keys) must never cause a duplicate charge.
 */
class RetryStormIT extends AbstractIntegrationTest {

    private static final int STORM = 200;

    record Reply(int status, String body, boolean replayed) {
        String id() {
            return JsonPath.read(body, "$.id");
        }
    }

    @Autowired
    JournalEntryRepository journalEntries;

    @Autowired
    JdbcClient jdbc;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private UUID merchant;
    private String token;
    private String customer;

    @BeforeEach
    void setUp() {
        merchant = data.merchant(200);
        token = data.bearerToken(merchant);
        customer = data.customer(1_000_000);
    }

    @Test
    void createStormWithOneKeyCreatesExactlyOnePayment() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"customerId\":\"" + customer + "\",\"amount\":10000}";

        List<Reply> replies = storm(STORM, i -> post("/v1/payments", key, body));

        Set<Integer> statuses = statuses(replies);
        List<Reply> created = replies.stream().filter(r -> r.status() == 201).toList();
        report("create, same key", replies);
        assertThat(statuses).isSubsetOf(201, 409);
        assertThat(created).isNotEmpty();
        assertThat(created.stream().map(Reply::id).distinct()).hasSize(1);
        assertThat(created.stream().filter(r -> !r.replayed())).hasSize(1);
        assertThat(paymentsFor(customer)).isEqualTo(1);

        // Once the storm has passed, a late retry gets the stored response.
        Reply late = post("/v1/payments", key, body);
        assertThat(late.status()).isEqualTo(201);
        assertThat(late.replayed()).isTrue();
        assertThat(late.id()).isEqualTo(created.getFirst().id());
    }

    @Test
    void captureStormChargesTheCustomerExactlyOnce() throws Exception {
        String paymentId = post("/v1/payments", UUID.randomUUID().toString(),
                "{\"customerId\":\"" + customer + "\",\"amount\":25000}").id();
        String sharedKey = UUID.randomUUID().toString();

        // Half the requests are proper retries (same key), half come from a buggy client that
        // generates a new key per attempt. Idempotency stops the first kind; the payment row lock
        // plus the state machine stops the second.
        List<Reply> replies = storm(STORM, i -> post("/v1/payments/" + paymentId + "/capture",
                i % 2 == 0 ? sharedKey : UUID.randomUUID().toString(), null));

        report("capture, mixed keys", replies);
        assertThat(statuses(replies)).isSubsetOf(200, 409);
        assertThat(replies.stream().filter(r -> r.status() == 200 && !r.replayed())).hasSize(1);
        assertThat(captureEntries(paymentId)).isEqualTo(1);
        assertThat(data.balance(AccountType.CUSTOMER, customer)).isEqualTo(1_000_000 - 25_000);
        assertThat(audit.globalImbalance()).isZero();
    }

    @Test
    void refundStormNeverRefundsMoreThanWasCaptured() throws Exception {
        String paymentId = post("/v1/payments", UUID.randomUUID().toString(),
                "{\"customerId\":\"" + customer + "\",\"amount\":10000}").id();
        assertThat(post("/v1/payments/" + paymentId + "/capture", UUID.randomUUID().toString(), null).status())
                .isEqualTo(200);

        // 300 x Rs 1 refunds against a Rs 100 payment: exactly 100 can succeed.
        List<Reply> replies = storm(300, i -> post("/v1/payments/" + paymentId + "/refunds",
                UUID.randomUUID().toString(), "{\"amount\":100}"));

        report("refund, unique keys", replies);
        assertThat(statuses(replies)).isSubsetOf(201, 409, 422);
        assertThat(replies.stream().filter(r -> r.status() == 201)).hasSize(100);
        assertThat(refundedAmount(paymentId)).isEqualTo(10_000);
        assertThat(data.balance(AccountType.CUSTOMER, customer)).isEqualTo(1_000_000);
        assertThat(audit.accountsWithDriftedBalance()).isEmpty();
    }

    @FunctionalInterface
    interface Request {
        Reply send(int attempt) throws Exception;
    }

    private static List<Reply> storm(int size, Request request) throws Exception {
        List<Callable<Reply>> tasks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int attempt = i;
            tasks.add(() -> request.send(attempt));
        }
        return ConcurrentRunner.runAll(tasks).outcomes();
    }

    private Reply post(String path, String idempotencyKey, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey);
        if (json == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        boolean replayed = response.headers().firstValue(IdempotencyService.REPLAY_HEADER).isPresent();
        return new Reply(response.statusCode(), response.body(), replayed);
    }

    private static Set<Integer> statuses(List<Reply> replies) {
        return replies.stream().map(Reply::status).collect(Collectors.toSet());
    }

    private long paymentsFor(String customerId) {
        return jdbc.sql("SELECT count(*) FROM payment WHERE customer_id = :c").param("c", customerId)
                .query(Long.class).single();
    }

    private long captureEntries(String paymentId) {
        return journalEntries.findByPaymentIdOrderByCreatedAt(UUID.fromString(paymentId)).stream()
                .map(JournalEntry::getEntryType)
                .filter(EntryType.CAPTURE::equals)
                .count();
    }

    private long refundedAmount(String paymentId) {
        return jdbc.sql("SELECT refunded_amount FROM payment WHERE id = :id").param("id", UUID.fromString(paymentId))
                .query(Long.class).single();
    }

    private static void report(String scenario, List<Reply> replies) {
        String breakdown = replies.stream()
                .collect(Collectors.groupingBy(r -> r.status() + (r.replayed() ? " (replay)" : ""), Collectors.counting()))
                .toString();
        System.out.printf("[proof] retry storm, %s: %d requests -> %s%n", scenario, replies.size(), breakdown);
    }
}
