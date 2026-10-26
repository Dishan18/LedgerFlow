package com.ledgerflow.mockbank.settlement;

/** Where settlement files go. S3 in every environment; an in-memory fake in unit tests. */
public interface SettlementStore {

    void put(String key, String content, String contentType);
}
