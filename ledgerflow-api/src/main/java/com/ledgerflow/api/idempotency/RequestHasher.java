package com.ledgerflow.api.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fingerprints a request so a reused Idempotency-Key with a different request can be detected.
 *
 * <p>The body passed in is the <em>parsed</em> request object re-serialized by Jackson, not the raw
 * bytes, so whitespace or key-order differences in what the client sent don't count as "different".
 */
public final class RequestHasher {

    private RequestHasher() {
    }

    public static String hash(String method, String path, String canonicalBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = method + ' ' + path + '\n' + (canonicalBody == null ? "" : canonicalBody);
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
