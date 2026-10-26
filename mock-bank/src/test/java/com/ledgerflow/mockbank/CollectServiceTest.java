package com.ledgerflow.mockbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.common.bank.CollectRequest;
import com.ledgerflow.common.bank.CollectResponse;
import com.ledgerflow.common.bank.CollectStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class CollectServiceTest {

    private static final Duration HANG = Duration.ofSeconds(10);
    private static final Duration RESOLVE_AFTER = Duration.ofSeconds(15);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
    private final List<Duration> sleeps = new ArrayList<>();
    private final UUID paymentId = UUID.randomUUID();

    @Test
    void approvesWithA12DigitUtr() {
        CollectResponse response = service(calm()).collect(new CollectRequest(paymentId, "alice+approve@upi", 500));

        assertThat(response.status()).isEqualTo(CollectStatus.APPROVED);
        assertThat(response.bankReference()).matches("\\d{12}");
    }

    @Test
    void retryWithSamePaymentIdReturnsTheSameCollect() {
        CollectService bank = service(calm());
        CollectResponse first = bank.collect(new CollectRequest(paymentId, "alice@upi", 500));
        CollectResponse second = bank.collect(new CollectRequest(paymentId, "alice@upi", 500));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void retryWithDifferentAmountIsRejected() {
        CollectService bank = service(calm());
        bank.collect(new CollectRequest(paymentId, "alice@upi", 500));

        assertThatThrownBy(() -> bank.collect(new CollectRequest(paymentId, "alice@upi", 501)))
                .isInstanceOf(ConflictingCollectException.class);
    }

    @Test
    void outageRecordsNothing() {
        CollectService bank = service(calm());

        assertThatThrownBy(() -> bank.collect(new CollectRequest(paymentId, "alice+error@upi", 500)))
                .isInstanceOf(BankUnavailableException.class);
        assertThat(bank.status(paymentId)).isEmpty();
    }

    @Test
    void flakyFailsOnceThenApproves() {
        CollectService bank = service(calm());
        CollectRequest request = new CollectRequest(paymentId, "alice+flaky@upi", 500);

        assertThatThrownBy(() -> bank.collect(request)).isInstanceOf(BankUnavailableException.class);
        assertThat(bank.collect(request).status()).isEqualTo(CollectStatus.APPROVED);
    }

    @Test
    void timeoutRecordsTheCollectThenHangs() {
        CollectService bank = service(calm());

        bank.collect(new CollectRequest(paymentId, "alice+timeout@upi", 500));

        assertThat(sleeps).contains(HANG);
        assertThat(bank.status(paymentId).orElseThrow().status()).isEqualTo(CollectStatus.APPROVED);
    }

    @Test
    void pendingResolvesOnlyAfterTheConfiguredDelay() {
        CollectService bank = service(calm());
        bank.collect(new CollectRequest(paymentId, "alice+pending-decline@upi", 500));

        assertThat(bank.status(paymentId).orElseThrow().status()).isEqualTo(CollectStatus.PENDING);

        clock.advance(RESOLVE_AFTER);
        CollectResponse resolved = bank.status(paymentId).orElseThrow();
        assertThat(resolved.status()).isEqualTo(CollectStatus.DECLINED);
        assertThat(resolved.reason()).isNotBlank();
    }

    @Test
    void untaggedVpaFollowsConfiguredRates() {
        assertThatThrownBy(() -> service(chaos(1.0, 0, 0, 0)).collect(new CollectRequest(paymentId, "bob@upi", 1)))
                .isInstanceOf(BankUnavailableException.class);
        assertThat(service(chaos(0, 0, 1.0, 0)).collect(new CollectRequest(UUID.randomUUID(), "bob@upi", 1)).status())
                .isEqualTo(CollectStatus.DECLINED);
        assertThat(service(chaos(0, 0, 0, 1.0)).collect(new CollectRequest(UUID.randomUUID(), "bob@upi", 1)).status())
                .isEqualTo(CollectStatus.PENDING);
    }

    @Test
    void concurrentRetriesCreateOneCollect() throws Exception {
        CollectService bank = service(calm());
        List<Callable<CollectResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            calls.add(() -> bank.collect(new CollectRequest(paymentId, "alice@upi", 500)));
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<String> utrs = new ArrayList<>();
            for (Future<CollectResponse> f : pool.invokeAll(calls)) {
                utrs.add(f.get().bankReference());
            }
            assertThat(utrs).containsOnly(utrs.getFirst());
        }
    }

    @Test
    void rejectsInvalidRequests() {
        assertThatThrownBy(() -> service(calm()).collect(new CollectRequest(paymentId, " ", 5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(calm()).collect(new CollectRequest(paymentId, "a@upi", 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CollectService service(ChaosProperties chaos) {
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        Sleeper recordingSleeper = new Sleeper() {
            @Override
            public synchronized void sleep(Duration duration) {
                sleeps.add(duration);
            }
        };
        return new CollectService(new ScenarioPicker(chaos, random), chaos, random, clock, recordingSleeper);
    }

    private static ChaosProperties calm() {
        return chaos(0, 0, 0, 0);
    }

    private static ChaosProperties chaos(double error, double timeout, double decline, double pending) {
        return new ChaosProperties(Duration.ZERO, Duration.ZERO, error, timeout, decline, pending, 0.5, HANG,
                RESOLVE_AFTER);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
