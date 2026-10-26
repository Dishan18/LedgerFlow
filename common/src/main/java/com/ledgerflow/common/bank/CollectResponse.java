package com.ledgerflow.common.bank;

import java.util.UUID;

/**
 * The bank's view of a collect.
 *
 * @param bankReference UTR (unique transaction reference), set once APPROVED
 * @param reason        why it was DECLINED, otherwise null
 */
public record CollectResponse(UUID paymentId, CollectStatus status, String bankReference, String reason) {
}
