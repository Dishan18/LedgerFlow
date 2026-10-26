package com.ledgerflow.common.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook signatures: {@code X-LedgerFlow-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256>}.
 *
 * <p>The MAC covers {@code "<t>.<raw body>"}. Signing the timestamp along with the body lets a
 * merchant reject old deliveries replayed by an attacker; signing the exact raw bytes means the
 * merchant must verify <em>before</em> parsing JSON. This is the same scheme Stripe uses.
 */
public final class WebhookSignature {

    public static final String HEADER = "X-LedgerFlow-Signature";
    public static final String EVENT_ID_HEADER = "X-LedgerFlow-Event-Id";
    public static final String EVENT_TYPE_HEADER = "X-LedgerFlow-Event-Type";

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSignature() {
    }

    public static String sign(String secret, Instant timestamp, String body) {
        long t = timestamp.getEpochSecond();
        return "t=" + t + ",v1=" + hmacHex(secret, t + "." + body);
    }

    /**
     * Checks a signature header the way a merchant should.
     *
     * @param tolerance how old a delivery may be before it is treated as a replay
     */
    public static boolean verify(String secret, String header, String body, Instant now, Duration tolerance) {
        if (header == null) {
            return false;
        }
        Long t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t == null || v1 == null) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(t), now).abs().compareTo(tolerance) > 0) {
            return false;
        }
        byte[] expected = hmacHex(secret, t + "." + body).getBytes(StandardCharsets.US_ASCII);
        // Constant-time comparison: a plain equals() leaks how many leading characters matched.
        return MessageDigest.isEqual(expected, v1.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
