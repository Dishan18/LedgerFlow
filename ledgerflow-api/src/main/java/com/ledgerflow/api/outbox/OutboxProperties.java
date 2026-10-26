package com.ledgerflow.api.outbox;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param enabled      run the relay on a schedule
 * @param pollInterval pause between relay rounds
 * @param batchSize    rows published per round
 * @param topicArn     the SNS topic {@code payment-events}
 */
@Validated
@ConfigurationProperties(prefix = "ledgerflow.outbox")
public record OutboxProperties(
        boolean enabled,
        @NotNull Duration pollInterval,
        @Min(1) int batchSize,
        @NotBlank String topicArn) {
}
