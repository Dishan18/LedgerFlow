package com.ledgerflow.common.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    private static final String SECRET = "whsec_test";
    private static final String BODY = "{\"eventId\":\"e1\",\"amount\":100}";
    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    @Test
    void signatureRoundTrips() {
        String header = WebhookSignature.sign(SECRET, NOW, BODY);

        assertThat(header).matches("t=\\d+,v1=[0-9a-f]{64}");
        assertThat(WebhookSignature.verify(SECRET, header, BODY, NOW, TOLERANCE)).isTrue();
    }

    @Test
    void matchesAnIndependentlyComputedVector() {
        // HMAC-SHA256("whsec_test", "1793095200." + BODY), computed with Python's hmac module.
        assertThat(WebhookSignature.sign(SECRET, NOW, BODY))
                .isEqualTo("t=1793095200,v1=80100acd6f5c91388232f730e0a7a7e78c3ce88006d3da14b97e4126a9c1795f");
    }

    @Test
    void tamperedBodyFails() {
        String header = WebhookSignature.sign(SECRET, NOW, BODY);

        assertThat(WebhookSignature.verify(SECRET, header, BODY.replace("100", "999"), NOW, TOLERANCE)).isFalse();
    }

    @Test
    void wrongSecretFails() {
        String header = WebhookSignature.sign("other", NOW, BODY);

        assertThat(WebhookSignature.verify(SECRET, header, BODY, NOW, TOLERANCE)).isFalse();
    }

    @Test
    void replayedOldDeliveryFails() {
        String header = WebhookSignature.sign(SECRET, NOW, BODY);

        assertThat(WebhookSignature.verify(SECRET, header, BODY, NOW.plus(Duration.ofMinutes(6)), TOLERANCE)).isFalse();
    }

    @Test
    void malformedHeadersFail() {
        assertThat(WebhookSignature.verify(SECRET, null, BODY, NOW, TOLERANCE)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, "garbage", BODY, NOW, TOLERANCE)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, "t=abc,v1=00", BODY, NOW, TOLERANCE)).isFalse();
    }
}
