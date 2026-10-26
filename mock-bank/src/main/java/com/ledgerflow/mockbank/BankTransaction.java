package com.ledgerflow.mockbank;

import com.ledgerflow.common.bank.CollectResponse;
import com.ledgerflow.common.bank.CollectStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * The bank's record of one collect. A PENDING collect already knows its final outcome; it just
 * doesn't reveal it until {@code resolveAt}.
 */
public record BankTransaction(
        UUID paymentId,
        String payerVpa,
        long amount,
        CollectStatus finalStatus,
        String utr,
        String declineReason,
        Instant createdAt,
        Instant resolveAt,
        boolean hangOnFirstResponse) {

    public CollectStatus statusAt(Instant now) {
        return now.isBefore(resolveAt) ? CollectStatus.PENDING : finalStatus;
    }

    public CollectResponse viewAt(Instant now) {
        CollectStatus status = statusAt(now);
        return new CollectResponse(
                paymentId,
                status,
                status == CollectStatus.APPROVED ? utr : null,
                status == CollectStatus.DECLINED ? declineReason : null);
    }
}
