package com.ledgerflow.api.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Fires tasks as close to simultaneously as possible: every task waits on one start gate, so
 * none of them gets a head start while the others are still being submitted.
 */
public final class ConcurrentRunner {

    public record Result<T>(List<T> outcomes, Duration elapsed) {
    }

    private ConcurrentRunner() {
    }

    public static <T> Result<T> runAll(List<Callable<T>> tasks) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        // One virtual thread per task: 1,000 blocked threads cost almost nothing. The real limit on
        // parallelism is the database connection pool, which is exactly what we want to stress.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return task.call();
                }));
            }
            long start = System.nanoTime();
            startGate.countDown();
            List<T> outcomes = new ArrayList<>(tasks.size());
            for (Future<T> future : futures) {
                outcomes.add(future.get());
            }
            return new Result<>(outcomes, Duration.ofNanos(System.nanoTime() - start));
        }
    }
}
