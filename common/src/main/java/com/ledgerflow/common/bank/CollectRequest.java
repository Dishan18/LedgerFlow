package com.ledgerflow.common.bank;

import java.util.UUID;

/**
 * UPI-style collect request: "please debit {@code payerVpa} by {@code amount} paise".
 *
 * @param paymentId LedgerFlow's payment id. The bank treats it as an idempotency key, so a
 *                  retried request never collects twice.
 */
public record CollectRequest(UUID paymentId, String payerVpa, long amount) {
}
