package com.ledgerflow.mockbank;

import com.ledgerflow.common.bank.CollectRequest;
import com.ledgerflow.common.bank.CollectResponse;
import com.ledgerflow.common.bank.CollectStatus;
import com.ledgerflow.common.settlement.SettlementCsv;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Service;

/**
 * In-memory bank. Collects are keyed by LedgerFlow's payment id, so retries are idempotent:
 * asking twice never collects twice.
 */
@Service
public class CollectService {

    private static final List<String> DECLINE_REASONS =
            List.of("INSUFFICIENT_BALANCE", "RISK_DECLINED", "CUSTOMER_REJECTED");

    private final Map<UUID, BankTransaction> transactions = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final ScenarioPicker picker;
    private final ChaosProperties chaos;
    private final RandomGenerator random;
    private final Clock clock;
    private final Sleeper sleeper;

    public CollectService(ScenarioPicker picker, ChaosProperties chaos, RandomGenerator random, Clock clock,
                          Sleeper sleeper) {
        this.picker = picker;
        this.chaos = chaos;
        this.random = random;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public CollectResponse collect(CollectRequest request) {
        validate(request);
        sleeper.sleep(normalDelay());
        int attempt = attempts.computeIfAbsent(request.paymentId(), id -> new AtomicInteger()).incrementAndGet();

        boolean[] createdNow = {false};
        // computeIfAbsent is atomic per key: two concurrent retries create exactly one transaction.
        BankTransaction txn = transactions.computeIfAbsent(request.paymentId(), id -> {
            BankTransaction created = create(request, picker.pick(request.payerVpa()), attempt);
            createdNow[0] = true;
            return created;
        });

        if (!createdNow[0] && (txn.amount() != request.amount() || !txn.payerVpa().equals(request.payerVpa()))) {
            throw new ConflictingCollectException(request.paymentId());
        }
        if (createdNow[0] && txn.hangOnFirstResponse()) {
            sleeper.sleep(chaos.hang());
        }
        return txn.viewAt(clock.instant());
    }

    public Optional<CollectResponse> status(UUID paymentId) {
        return Optional.ofNullable(transactions.get(paymentId)).map(t -> t.viewAt(clock.instant()));
    }

    /** Collects approved (as of now) that were created on {@code date} in the business time zone. */
    public List<BankTransaction> approvedOn(LocalDate date) {
        Instant now = clock.instant();
        return transactions.values().stream()
                .filter(t -> t.statusAt(now) == CollectStatus.APPROVED)
                .filter(t -> LocalDate.ofInstant(t.createdAt(), SettlementCsv.BUSINESS_ZONE).equals(date))
                .sorted(Comparator.comparing(BankTransaction::createdAt))
                .toList();
    }

    private BankTransaction create(CollectRequest request, Scenario scenario, int attempt) {
        Instant now = clock.instant();
        return switch (scenario) {
            case ERROR -> throw new BankUnavailableException();
            case FLAKY -> {
                if (attempt == 1) {
                    throw new BankUnavailableException();
                }
                yield transaction(request, CollectStatus.APPROVED, now, now, false);
            }
            case APPROVE -> transaction(request, CollectStatus.APPROVED, now, now, false);
            case TIMEOUT -> transaction(request, CollectStatus.APPROVED, now, now, true);
            case DECLINE -> transaction(request, CollectStatus.DECLINED, now, now, false);
            case PENDING -> transaction(request, CollectStatus.APPROVED, now, now.plus(chaos.pendingResolveAfter()), false);
            case PENDING_DECLINE ->
                    transaction(request, CollectStatus.DECLINED, now, now.plus(chaos.pendingResolveAfter()), false);
        };
    }

    private BankTransaction transaction(CollectRequest request, CollectStatus finalStatus, Instant now,
                                        Instant resolveAt, boolean hang) {
        String utr = finalStatus == CollectStatus.APPROVED ? utr() : null;
        String reason = finalStatus == CollectStatus.DECLINED
                ? DECLINE_REASONS.get(random.nextInt(DECLINE_REASONS.size()))
                : null;
        return new BankTransaction(request.paymentId(), request.payerVpa(), request.amount(), finalStatus, utr, reason,
                now, resolveAt, hang);
    }

    /** 12-digit UTR, the reference UPI gives every successful transaction. */
    private String utr() {
        return String.format("%012d", random.nextLong(1_000_000_000_000L));
    }

    private Duration normalDelay() {
        long min = chaos.minDelay().toMillis();
        long max = chaos.maxDelay().toMillis();
        return Duration.ofMillis(max <= min ? min : random.nextLong(min, max + 1));
    }

    private static void validate(CollectRequest request) {
        if (request.paymentId() == null || request.payerVpa() == null || request.payerVpa().isBlank()
                || request.amount() <= 0) {
            throw new IllegalArgumentException("paymentId, payerVpa and a positive amount are required");
        }
    }
}
