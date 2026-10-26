package com.ledgerflow.api.bank;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param connectTimeout time allowed to open a TCP connection
 * @param readTimeout    time allowed for the bank to answer one attempt
 * @param maxRetries     extra attempts after the first (so 2 means up to 3 calls)
 * @param initialBackoff wait before the first retry; doubles each time, plus jitter
 * @param maxBackoff     cap on the wait between retries
 */
@Validated
@ConfigurationProperties(prefix = "ledgerflow.bank")
public record BankProperties(
        @NotNull URI baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Min(0) int maxRetries,
        @NotNull Duration initialBackoff,
        @NotNull Duration maxBackoff) {
}
