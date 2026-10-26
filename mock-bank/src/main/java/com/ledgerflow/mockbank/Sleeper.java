package com.ledgerflow.mockbank;

import java.time.Duration;
import org.springframework.stereotype.Component;

/** Wraps Thread.sleep so tests can make the bank fast without changing its behaviour. */
@Component
public class Sleeper {

    public void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
