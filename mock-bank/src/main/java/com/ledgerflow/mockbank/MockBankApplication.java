package com.ledgerflow.mockbank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Simulated PSP/bank with a UPI-style collect API. Deliberately unreliable: random delays,
 * outages, lost responses and pending outcomes, tuned by {@link ChaosProperties}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MockBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockBankApplication.class, args);
    }
}
