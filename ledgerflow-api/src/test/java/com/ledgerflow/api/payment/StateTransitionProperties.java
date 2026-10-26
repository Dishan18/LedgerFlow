package com.ledgerflow.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;

/** Property: illegal transitions always fail, and terminal states are final. */
class StateTransitionProperties {

    @Property
    void illegalTransitionsAlwaysFail(@ForAll PaymentStatus from, @ForAll PaymentStatus to) {
        Assume.that(!from.canTransitionTo(to));
        Payment payment = paymentIn(from);

        assertThatThrownBy(() -> payment.transitionTo(to)).isInstanceOf(IllegalStateTransitionException.class);
        assertThat(payment.getStatus()).isEqualTo(from);
    }

    @Property
    void legalTransitionsAlwaysSucceed(@ForAll PaymentStatus from, @ForAll PaymentStatus to) {
        Assume.that(from.canTransitionTo(to));
        Payment payment = paymentIn(from);

        payment.transitionTo(to);

        assertThat(payment.getStatus()).isEqualTo(to);
    }

    @Property
    void randomWalksNeverLeaveATerminalState(@ForAll @Size(max = 40) List<PaymentStatus> attempts) {
        Payment payment = Payment.create(UUID.randomUUID(), "cust", 1_000, 0);

        for (PaymentStatus target : attempts) {
            PaymentStatus before = payment.getStatus();
            try {
                payment.transitionTo(target);
                assertThat(before.isTerminal()).isFalse();
                assertThat(before.canTransitionTo(target)).isTrue();
            } catch (IllegalStateTransitionException e) {
                assertThat(payment.getStatus()).isEqualTo(before);
            }
        }
    }

    /** Builds a payment in the given state by walking a legal path from CREATED (shortest path, BFS). */
    static Payment paymentIn(PaymentStatus state) {
        Payment payment = Payment.create(UUID.randomUUID(), "cust", 1_000, 0);
        for (PaymentStatus step : pathTo(state)) {
            payment.transitionTo(step);
        }
        return payment;
    }

    private static List<PaymentStatus> pathTo(PaymentStatus target) {
        Map<PaymentStatus, PaymentStatus> previous = new EnumMap<>(PaymentStatus.class);
        Deque<PaymentStatus> queue = new ArrayDeque<>(List.of(PaymentStatus.CREATED));
        previous.put(PaymentStatus.CREATED, PaymentStatus.CREATED);
        while (!queue.isEmpty()) {
            PaymentStatus current = queue.poll();
            for (PaymentStatus next : current.allowedTransitions()) {
                if (previous.putIfAbsent(next, current) == null) {
                    queue.add(next);
                }
            }
        }
        Deque<PaymentStatus> path = new ArrayDeque<>();
        for (PaymentStatus s = target; s != PaymentStatus.CREATED; s = previous.get(s)) {
            path.addFirst(s);
        }
        return List.copyOf(path);
    }
}
