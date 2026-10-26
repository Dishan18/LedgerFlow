package com.ledgerflow.mockbank;

import java.time.Clock;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MockBankConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RandomGenerator random() {
        return RandomGenerator.of("L64X128MixRandom");
    }
}
