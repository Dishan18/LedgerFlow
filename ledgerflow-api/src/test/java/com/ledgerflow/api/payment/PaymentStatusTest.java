package com.ledgerflow.api.payment;

import static com.ledgerflow.api.payment.PaymentStatus.AUTHORIZED;
import static com.ledgerflow.api.payment.PaymentStatus.CAPTURED;
import static com.ledgerflow.api.payment.PaymentStatus.CREATED;
import static com.ledgerflow.api.payment.PaymentStatus.FAILED;
import static com.ledgerflow.api.payment.PaymentStatus.PARTIALLY_REFUNDED;
import static com.ledgerflow.api.payment.PaymentStatus.PENDING_CONFIRMATION;
import static com.ledgerflow.api.payment.PaymentStatus.REFUNDED;
import static com.ledgerflow.api.payment.PaymentStatus.SETTLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentStatusTest {

    @Test
    void happyPathIsAllowed() {
        assertThat(CREATED.canTransitionTo(AUTHORIZED)).isTrue();
        assertThat(AUTHORIZED.canTransitionTo(CAPTURED)).isTrue();
        assertThat(CAPTURED.canTransitionTo(SETTLED)).isTrue();
    }

    @Test
    void pendingConfirmationResolvesEitherWay() {
        assertThat(CREATED.canTransitionTo(PENDING_CONFIRMATION)).isTrue();
        assertThat(PENDING_CONFIRMATION.canTransitionTo(AUTHORIZED)).isTrue();
        assertThat(PENDING_CONFIRMATION.canTransitionTo(FAILED)).isTrue();
    }

    @Test
    void cannotSkipAuthorization() {
        assertThat(CREATED.canTransitionTo(CAPTURED)).isFalse();
    }

    @Test
    void terminalStatesHaveNoExits() {
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(REFUNDED.isTerminal()).isTrue();
        assertThat(SETTLED.isTerminal()).isFalse();
        for (PaymentStatus target : PaymentStatus.values()) {
            assertThat(FAILED.canTransitionTo(target)).isFalse();
            assertThat(REFUNDED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void settledPaymentsCanStillBeRefunded() {
        assertThat(SETTLED.canTransitionTo(PARTIALLY_REFUNDED)).isTrue();
        assertThat(SETTLED.canTransitionTo(REFUNDED)).isTrue();
    }

    @Test
    void paymentRejectsIllegalTransition() {
        Payment payment = Payment.create(UUID.randomUUID(), "cust", 1000, 20);

        assertThatThrownBy(payment::capture)
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("CREATED -> CAPTURED");
        assertThat(payment.getStatus()).isEqualTo(CREATED);
    }
}
