package com.ledgerflow.api.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestHasherTest {

    @Test
    void sameRequestSameHash() {
        assertThat(RequestHasher.hash("POST", "/v1/payments", "{\"a\":1}"))
                .isEqualTo(RequestHasher.hash("POST", "/v1/payments", "{\"a\":1}"))
                .hasSize(64);
    }

    @Test
    void bodyPathAndMethodAllMatter() {
        String base = RequestHasher.hash("POST", "/v1/payments", "{\"a\":1}");

        assertThat(RequestHasher.hash("POST", "/v1/payments", "{\"a\":2}")).isNotEqualTo(base);
        assertThat(RequestHasher.hash("POST", "/v1/other", "{\"a\":1}")).isNotEqualTo(base);
        assertThat(RequestHasher.hash("PUT", "/v1/payments", "{\"a\":1}")).isNotEqualTo(base);
    }

    @Test
    void nullBodyEqualsEmptyBody() {
        assertThat(RequestHasher.hash("POST", "/x", null)).isEqualTo(RequestHasher.hash("POST", "/x", ""));
    }
}
