package com.ledgerflow.api.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ledgerflow")
public record LedgerflowProperties(Security security, Idempotency idempotency) {

    /**
     * @param jwtSecret HMAC key for verifying merchant JWTs (HS256). At least 32 bytes.
     *                  Comes from AWS Secrets Manager in Phase 6.
     */
    public record Security(@NotBlank @Size(min = 32) String jwtSecret) {
    }

    public record Idempotency(@NotNull Duration retention, @NotBlank String cleanupCron) {
    }
}
