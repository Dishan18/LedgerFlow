package com.ledgerflow.api.payment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param enabled           run the poller on a schedule (tests switch it off and call it directly)
 * @param interval          pause between polling rounds
 * @param batchSize         payments claimed per round
 * @param firstCheckAfter   first follow-up after the bank leaves a payment pending; doubles per attempt
 * @param maxCheckInterval  cap on the gap between follow-ups of one payment
 * @param stuckCreatedAfter a payment still CREATED this long after creation lost its request thread
 * @param notFoundGrace     how long "bank has no record" is tolerated before failing the payment
 * @param maxPendingAge     give up and fail the payment after this; reconciliation catches stragglers
 * @param lease             how long a claimed payment is hidden from other poller instances
 */
@Validated
@ConfigurationProperties(prefix = "ledgerflow.status-poller")
public record StatusPollerProperties(
        boolean enabled,
        @NotNull Duration interval,
        @Min(1) int batchSize,
        @NotNull Duration firstCheckAfter,
        @NotNull Duration maxCheckInterval,
        @NotNull Duration stuckCreatedAfter,
        @NotNull Duration notFoundGrace,
        @NotNull Duration maxPendingAge,
        @NotNull Duration lease) {
}
