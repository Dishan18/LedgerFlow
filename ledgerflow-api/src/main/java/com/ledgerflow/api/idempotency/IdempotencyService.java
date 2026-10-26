package com.ledgerflow.api.idempotency;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs a POST at most once per Idempotency-Key.
 *
 * <pre>
 * claim key (INSERT ... ON CONFLICT DO NOTHING, before any work)
 *   ├─ claimed      → run action → store response → return it
 *   │                 action throws → release key → rethrow (client may retry)
 *   └─ already there → different request hash → 422
 *                      still IN_PROGRESS      → 409
 *                      COMPLETED              → replay stored response
 * </pre>
 */
@Service
public class IdempotencyService {

    public static final String REPLAY_HEADER = "Idempotent-Replayed";

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param merchantId the caller; keys are namespaced per merchant so two merchants that happen to
     *                   pick the same key never see each other's responses
     * @param request    the parsed request body, or {@code null} for body-less POSTs
     */
    public ResponseEntity<?> execute(UUID merchantId,
                                     String idempotencyKey,
                                     String method,
                                     String path,
                                     Object request,
                                     Supplier<ResponseEntity<?>> action) {
        String key = merchantId + ":" + idempotencyKey;
        String canonicalBody = request == null ? "" : objectMapper.writeValueAsString(request);
        String requestHash = RequestHasher.hash(method, path, canonicalBody);

        if (!repository.claim(key, requestHash)) {
            return replayOrReject(key, idempotencyKey, requestHash);
        }

        ResponseEntity<?> response;
        try {
            response = action.get();
        } catch (RuntimeException e) {
            repository.release(key);
            throw e;
        }
        repository.complete(key, response.getStatusCode().value(), objectMapper.writeValueAsString(response.getBody()));
        return response;
    }

    private ResponseEntity<?> replayOrReject(String key, String clientKey, String requestHash) {
        // The row can vanish between our failed INSERT and this SELECT if the original request
        // failed and released it. Treat that like "in progress" and let the client retry.
        IdempotencyRecord existing = repository.find(key)
                .orElseThrow(() -> new IdempotencyKeyInUseException(clientKey));

        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(clientKey);
        }
        if (existing.status() == IdempotencyRecord.Status.IN_PROGRESS) {
            throw new IdempotencyKeyInUseException(clientKey);
        }
        return ResponseEntity.status(HttpStatusCode.valueOf(existing.responseCode()))
                .contentType(MediaType.APPLICATION_JSON)
                .header(REPLAY_HEADER, "true")
                .body(existing.responseBody());
    }
}
