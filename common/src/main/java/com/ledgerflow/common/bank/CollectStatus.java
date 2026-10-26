package com.ledgerflow.common.bank;

public enum CollectStatus {
    /** Money collected. Final. */
    APPROVED,
    /** Not collected (insufficient balance, risk, customer rejected). Final. */
    DECLINED,
    /** Waiting for the customer to approve in their UPI app. Ask again later. */
    PENDING
}
