package com.ledgerflow.api.webhook;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * Reads the webhook signing secret from AWS Secrets Manager and caches it briefly, so we don't
 * call Secrets Manager per webhook but still pick up a rotated secret within the TTL.
 */
@Component
public class WebhookSigningKeyProvider {

    private record Cached(String secret, Instant expiresAt) {
    }

    private final SecretsManagerClient secretsManager;
    private final WebhookProperties properties;
    private final Clock clock;
    private volatile Cached cached;

    public WebhookSigningKeyProvider(SecretsManagerClient secretsManager, WebhookProperties properties, Clock clock) {
        this.secretsManager = secretsManager;
        this.properties = properties;
        this.clock = clock;
    }

    public String currentSecret() {
        Cached current = cached;
        Instant now = clock.instant();
        if (current == null || now.isAfter(current.expiresAt())) {
            String secret = secretsManager.getSecretValue(GetSecretValueRequest.builder()
                    .secretId(properties.signingSecretId())
                    .build()).secretString();
            current = new Cached(secret, now.plus(properties.secretCacheTtl()));
            cached = current;
        }
        return current.secret();
    }
}
