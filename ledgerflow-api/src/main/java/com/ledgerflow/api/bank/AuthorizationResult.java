package com.ledgerflow.api.bank;

public sealed interface AuthorizationResult {

    record Approved(String bankReference) implements AuthorizationResult {
    }

    record Declined(String reason) implements AuthorizationResult {
    }
}
