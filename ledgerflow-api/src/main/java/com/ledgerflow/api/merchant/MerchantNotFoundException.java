package com.ledgerflow.api.merchant;

import java.util.UUID;

public class MerchantNotFoundException extends RuntimeException {

    public MerchantNotFoundException(UUID merchantId) {
        super("merchant " + merchantId + " is not onboarded");
    }
}
