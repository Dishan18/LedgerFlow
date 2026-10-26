package com.ledgerflow.mockbank;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How unreliable the bank is. Rates are probabilities in [0, 1], applied to each new collect
 * whose VPA carries no {@code +scenario} tag (see {@link Scenario}).
 *
 * @param minDelay            fastest response
 * @param maxDelay            slowest normal response
 * @param errorRate           respond 503 without recording anything (bank down)
 * @param timeoutRate         record the outcome, then hang for {@code hang} (response lost)
 * @param declineRate         decline the collect
 * @param pendingRate         leave the collect PENDING until {@code pendingResolveAfter}
 * @param pendingApproveRate  share of PENDING collects that end up APPROVED
 * @param hang                how long a "timeout" hangs; longer than any sane client timeout
 * @param pendingResolveAfter how long a PENDING collect stays pending
 */
@ConfigurationProperties(prefix = "mockbank.chaos")
public record ChaosProperties(
        Duration minDelay,
        Duration maxDelay,
        double errorRate,
        double timeoutRate,
        double declineRate,
        double pendingRate,
        double pendingApproveRate,
        Duration hang,
        Duration pendingResolveAfter) {
}
