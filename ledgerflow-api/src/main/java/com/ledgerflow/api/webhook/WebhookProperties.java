package com.ledgerflow.api.webhook;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param queue           SQS queue fed by the {@code payment-events} topic
 * @param signingSecretId Secrets Manager id of the HMAC signing secret
 * @param secretCacheTtl  how long the secret is cached; bounds how fast a rotation takes effect
 * @param connectTimeout  connecting to the merchant
 * @param readTimeout     waiting for the merchant's answer
 * @param maxAttempts     must equal the queue's redrive {@code maxReceiveCount}; after this many
 *                        failed deliveries the message goes to the DLQ
 * @param initialBackoff  wait before the 2nd attempt; doubles each attempt (SQS visibility timeout)
 * @param maxBackoff      cap on that wait
 */
@Validated
@ConfigurationProperties(prefix = "ledgerflow.webhooks")
public record WebhookProperties(
        @NotBlank String queue,
        @NotBlank String signingSecretId,
        @NotNull Duration secretCacheTtl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Min(1) int maxAttempts,
        @NotNull Duration initialBackoff,
        @NotNull Duration maxBackoff) {
}
