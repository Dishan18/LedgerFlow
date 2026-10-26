package com.ledgerflow.api.web;

public class InvalidMerchantTokenException extends RuntimeException {

    public InvalidMerchantTokenException() {
        super("token subject is not a merchant id");
    }
}
