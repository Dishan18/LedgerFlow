package com.ledgerflow.api.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Waits for asynchronous work (relay, SQS, webhooks) without fixed sleeps. */
public final class Eventually {

    private Eventually() {
    }

    public static void await(String what, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out after " + timeout + " waiting for: " + what);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for: " + what);
            }
        }
    }
}
