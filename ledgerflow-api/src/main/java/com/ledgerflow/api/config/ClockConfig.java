package com.ledgerflow.api.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Injected instead of calling Instant.now() so time-based logic can be tested. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
