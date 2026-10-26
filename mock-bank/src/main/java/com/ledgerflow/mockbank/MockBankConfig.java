package com.ledgerflow.mockbank;

import java.time.Clock;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MockBankConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** java.util.Random: thread-safe and in java.base (the slim JRE image has no jdk.random module). */
    @Bean
    RandomGenerator random() {
        return new Random();
    }
}
