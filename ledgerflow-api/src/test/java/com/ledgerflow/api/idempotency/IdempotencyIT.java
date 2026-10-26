package com.ledgerflow.api.idempotency;

import static com.ledgerflow.api.support.TestData.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ledgerflow.api.ledger.AccountType;
import com.ledgerflow.api.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

class IdempotencyIT extends AbstractIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    IdempotencyRepository repository;

    @Autowired
    IdempotencyCleanupJob cleanupJob;

    private UUID merchant;
    private String customer;

    @BeforeEach
    void setUp() {
        merchant = data.merchant(200);
        customer = data.customer(100_000);
    }

    @Test
    void sameKeySameBodyReplaysTheOriginalResponse() throws Exception {
        String key = UUID.randomUUID().toString();

        String first = create(key, 1_000)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyService.REPLAY_HEADER))
                .andReturn().getResponse().getContentAsString();

        String second = create(key, 1_000)
                .andExpect(status().isCreated())
                .andExpect(header().string(IdempotencyService.REPLAY_HEADER, "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat(paymentCountFor(customer)).isEqualTo(1);
    }

    @Test
    void replayedCaptureDoesNotChargeTwice() throws Exception {
        String body = create(UUID.randomUUID().toString(), 4_000).andReturn().getResponse().getContentAsString();
        String paymentId = JsonPath.read(body, "$.id");
        String key = UUID.randomUUID().toString();

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/v1/payments/{id}/capture", paymentId).with(as(merchant)).header("Idempotency-Key", key))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CAPTURED"));
        }

        assertThat(data.balance(AccountType.CUSTOMER, customer)).isEqualTo(96_000);
    }

    @Test
    void sameKeyDifferentBodyIs422() throws Exception {
        String key = UUID.randomUUID().toString();
        create(key, 1_000).andExpect(status().isCreated());

        create(key, 2_000)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(paymentCountFor(customer)).isEqualTo(1);
    }

    @Test
    void sameKeyWhileInFlightIs409() throws Exception {
        String key = UUID.randomUUID().toString();
        String canonicalBody = "{\"customerId\":\"" + customer + "\",\"amount\":1000}";
        // Simulate a first request that has claimed the key and is still running.
        repository.claim(merchant + ":" + key, RequestHasher.hash("POST", "/v1/payments", canonicalBody));

        create(key, 1_000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_IN_USE"));
        assertThat(paymentCountFor(customer)).isZero();
    }

    @Test
    void failedRequestReleasesTheKeySoTheClientCanRetry() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = create(UUID.randomUUID().toString(), 1_000).andReturn().getResponse().getContentAsString();
        String paymentId = JsonPath.read(body, "$.id");

        // First attempt fails (refund before capture), second with the same key after capturing succeeds.
        mvc.perform(post("/v1/payments/{id}/refunds", paymentId).with(as(merchant)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/v1/payments/{id}/capture", paymentId).with(as(merchant))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/payments/{id}/refunds", paymentId).with(as(merchant)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100}"))
                .andExpect(status().isCreated());
    }

    @Test
    void sameKeyFromDifferentMerchantsDoesNotCollide() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID otherMerchant = data.merchant(100);

        create(key, 1_000).andExpect(status().isCreated());
        mvc.perform(post("/v1/payments").with(as(otherMerchant))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"amount\":1000}"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyService.REPLAY_HEADER));
    }

    @Test
    void cleanupDeletesKeysOlderThan24Hours() {
        String oldKey = "old-" + UUID.randomUUID();
        String freshKey = "fresh-" + UUID.randomUUID();
        repository.claim(oldKey, "h");
        repository.claim(freshKey, "h");
        jdbc.sql("UPDATE idempotency_key SET created_at = :ts WHERE key = :key")
                .param("ts", java.sql.Timestamp.from(Instant.now().minus(25, ChronoUnit.HOURS)))
                .param("key", oldKey)
                .update();

        cleanupJob.purgeExpiredKeys();

        assertThat(repository.find(oldKey)).isEmpty();
        assertThat(repository.find(freshKey)).isPresent();
    }

    private ResultActions create(String key, long amount) throws Exception {
        return create(key, customer, amount);
    }

    private ResultActions create(String key, String customerId, long amount) throws Exception {
        // Deliberately odd whitespace and key order: the hash is over the parsed request, not raw bytes.
        return mvc.perform(post("/v1/payments").with(as(merchant))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"amount\": " + amount + ",  \"customerId\": \"" + customerId + "\" }"));
    }

    private long paymentCountFor(String customerId) {
        return jdbc.sql("SELECT count(*) FROM payment WHERE customer_id = :c").param("c", customerId)
                .query(Long.class).single();
    }
}
