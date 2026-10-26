package com.ledgerflow.mockbank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Simulated PSP/bank. Only a health endpoint for now; the UPI-style API arrives in Phase 3. */
@SpringBootApplication
public class MockBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockBankApplication.class, args);
    }
}
