package com.ledgerflow.api.bank;

import com.ledgerflow.common.bank.BankApi;
import com.ledgerflow.common.bank.CollectRequest;
import com.ledgerflow.common.bank.CollectResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Talks to mock-bank over HTTP with a timeout on every call and bounded retries.
 *
 * <p>Retrying a collect is only safe because the bank deduplicates by payment id: if attempt 1
 * actually succeeded but its response was lost, attempt 2 gets the same collect back instead of
 * charging again. Without that guarantee we could not retry timeouts at all.
 */
@Component
public class HttpBankGateway implements BankGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpBankGateway.class);

    private final RestClient restClient;
    private final RetryTemplate retryTemplate;
    private final MeterRegistry meters;

    public HttpBankGateway(BankProperties properties, MeterRegistry meters) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();

        // Retry only what might succeed next time: I/O problems (timeouts, refused connections)
        // and 5xx. A 4xx means our request is wrong and will be wrong again.
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(properties.maxRetries())
                .delay(properties.initialBackoff())
                .multiplier(2)
                .jitter(properties.initialBackoff().dividedBy(2))
                .maxDelay(properties.maxBackoff())
                .includes(ResourceAccessException.class, HttpServerErrorException.class)
                .build();
        this.retryTemplate = new RetryTemplate(policy);
        this.meters = meters;
    }

    @Override
    public AuthorizationResult authorize(UUID paymentId, String payerVpa, long amount) {
        AuthorizationResult result;
        try {
            CollectResponse response = retryTemplate.execute(() -> restClient.post()
                    .uri(BankApi.COLLECTS)
                    .body(new CollectRequest(paymentId, payerVpa, amount))
                    .retrieve()
                    .body(CollectResponse.class));
            result = toResult(response);
        } catch (RetryException e) {
            result = fromFailure(paymentId, e.getLastException());
        }
        record("authorize", result);
        return result;
    }

    @Override
    public AuthorizationResult checkStatus(UUID paymentId) {
        // No retries here: the status poller calls again later on its own schedule.
        AuthorizationResult result;
        try {
            CollectResponse response = restClient.get()
                    .uri(BankApi.COLLECT_BY_PAYMENT, paymentId)
                    .retrieve()
                    .body(CollectResponse.class);
            result = toResult(response);
        } catch (HttpClientErrorException.NotFound e) {
            result = new AuthorizationResult.NotFound();
        } catch (RuntimeException e) {
            result = fromFailure(paymentId, e);
        }
        record("status", result);
        return result;
    }

    private static AuthorizationResult toResult(CollectResponse response) {
        return switch (response.status()) {
            case APPROVED -> new AuthorizationResult.Approved(response.bankReference());
            case DECLINED -> new AuthorizationResult.Declined(response.reason());
            case PENDING -> new AuthorizationResult.Pending("BANK_PENDING");
        };
    }

    private static AuthorizationResult fromFailure(UUID paymentId, Throwable failure) {
        if (failure instanceof HttpClientErrorException clientError) {
            // The bank understood and refused this request; repeating it will not help.
            log.warn("Bank rejected collect {} with {}", paymentId, clientError.getStatusCode());
            return new AuthorizationResult.Declined("BANK_REJECTED_" + clientError.getStatusCode().value());
        }
        // Timeout, outage, unreadable response: the collect may or may not have happened.
        log.warn("No definite answer from bank for {}: {}", paymentId, failure.toString());
        return new AuthorizationResult.Pending("BANK_UNREACHABLE");
    }

    private void record(String operation, AuthorizationResult result) {
        meters.counter("ledgerflow.bank.calls",
                "operation", operation,
                "outcome", result.getClass().getSimpleName().toLowerCase(Locale.ROOT)).increment();
    }
}
