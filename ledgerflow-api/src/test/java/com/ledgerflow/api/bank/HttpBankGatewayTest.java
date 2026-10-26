package com.ledgerflow.api.bank;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.api.support.FakeBank;
import com.ledgerflow.common.bank.CollectStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The bank client against a scripted HTTP bank: timeouts, retries and outcome mapping. */
class HttpBankGatewayTest {

    private static final FakeBank BANK = FakeBank.start();
    private static final Duration READ_TIMEOUT = Duration.ofMillis(200);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private HttpBankGateway gateway;
    private final UUID paymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        BANK.reset();
        gateway = gatewayFor(URI.create(BANK.baseUrl()));
    }

    @Test
    void approvedCollectIsApprovedFirstTime() {
        AuthorizationResult result = gateway.authorize(paymentId, "alice@upi", 1_000);

        assertThat(result).isInstanceOf(AuthorizationResult.Approved.class);
        assertThat(BANK.collectAttempts(paymentId)).isEqualTo(1);
        assertThat(meters.counter("ledgerflow.bank.calls", "operation", "authorize", "outcome", "approved").count())
                .isEqualTo(1);
    }

    @Test
    void transientOutageIsRetriedUntilItSucceeds() {
        BANK.onCollect(FakeBank.flaky(2));

        AuthorizationResult result = gateway.authorize(paymentId, "alice@upi", 1_000);

        assertThat(result).isInstanceOf(AuthorizationResult.Approved.class);
        assertThat(BANK.collectAttempts(paymentId)).isEqualTo(3);
    }

    @Test
    void lostResponseIsRecoveredByRetryingTheSameCollect() {
        BANK.onCollect(FakeBank.approveFirstAnswerLost(READ_TIMEOUT.multipliedBy(3)));

        AuthorizationResult result = gateway.authorize(paymentId, "alice@upi", 1_000);

        // Attempt 1 was collected but its answer was lost; attempt 2 got the stored answer back.
        assertThat(result).isInstanceOf(AuthorizationResult.Approved.class);
        assertThat(BANK.collectAttempts(paymentId)).isEqualTo(2);
    }

    @Test
    void lostResponsesBecomePendingAfterAllRetries() {
        BANK.onCollect(FakeBank.approveAfter(READ_TIMEOUT.multipliedBy(3)));

        AuthorizationResult result = gateway.authorize(paymentId, "alice@upi", 1_000);

        // Every attempt timed out, but the bank recorded the collect on the first one. The same
        // payment id on each retry meant it was never collected twice.
        assertThat(result).isEqualTo(new AuthorizationResult.Pending("BANK_UNREACHABLE"));
        assertThat(BANK.collectAttempts(paymentId)).isEqualTo(3);
        assertThat(BANK.knows(paymentId)).isTrue();
    }

    @Test
    void permanentOutageBecomesPending() {
        BANK.onCollect(FakeBank.failWith(503));

        assertThat(gateway.authorize(paymentId, "alice@upi", 1_000))
                .isEqualTo(new AuthorizationResult.Pending("BANK_UNREACHABLE"));
        assertThat(BANK.collectAttempts(paymentId)).isEqualTo(3);
    }

    @Test
    void declineIsFinalAndNotRetried() {
        BANK.onCollect(FakeBank.decline("INSUFFICIENT_BALANCE"));

        assertThat(gateway.authorize(paymentId, "alice@upi", 1_000))
                .isEqualTo(new AuthorizationResult.Declined("INSUFFICIENT_BALANCE"));
        assertThat(BANK.collectAttempts(paymentId)).isEqualTo(1);
    }

    @Test
    void clientErrorIsNotRetried() {
        BANK.onCollect(FakeBank.failWith(409));

        assertThat(gateway.authorize(paymentId, "alice@upi", 1_000))
                .isEqualTo(new AuthorizationResult.Declined("BANK_REJECTED_409"));
        assertThat(BANK.collectAttempts(paymentId)).isEqualTo(1);
    }

    @Test
    void unreachableBankBecomesPending() {
        HttpBankGateway nowhere = gatewayFor(URI.create("http://localhost:1"));

        assertThat(nowhere.authorize(paymentId, "alice@upi", 1_000))
                .isEqualTo(new AuthorizationResult.Pending("BANK_UNREACHABLE"));
    }

    @Test
    void bankPendingIsPending() {
        BANK.onCollect(FakeBank.pending());

        assertThat(gateway.authorize(paymentId, "alice@upi", 1_000))
                .isEqualTo(new AuthorizationResult.Pending("BANK_PENDING"));
    }

    @Test
    void statusCheckMapsEveryAnswer() {
        assertThat(gateway.checkStatus(paymentId)).isInstanceOf(AuthorizationResult.NotFound.class);

        BANK.resolve(paymentId, CollectStatus.PENDING, null);
        assertThat(gateway.checkStatus(paymentId)).isInstanceOf(AuthorizationResult.Pending.class);

        BANK.resolve(paymentId, CollectStatus.APPROVED, "123456789012");
        assertThat(gateway.checkStatus(paymentId)).isEqualTo(new AuthorizationResult.Approved("123456789012"));

        BANK.resolve(paymentId, CollectStatus.DECLINED, "CUSTOMER_REJECTED");
        assertThat(gateway.checkStatus(paymentId)).isEqualTo(new AuthorizationResult.Declined("CUSTOMER_REJECTED"));
        assertThat(BANK.statusChecks(paymentId)).isEqualTo(4);
    }

    private HttpBankGateway gatewayFor(URI baseUrl) {
        BankProperties properties = new BankProperties(
                baseUrl, Duration.ofMillis(200), READ_TIMEOUT, 2, Duration.ofMillis(10), Duration.ofMillis(50));
        return new HttpBankGateway(properties, meters);
    }
}
