package com.ledgerflow.api.webhook;

import com.ledgerflow.api.merchant.Merchant;
import com.ledgerflow.api.merchant.MerchantRepository;
import com.ledgerflow.api.outbox.PaymentEventPublisher.PaymentEvent;
import com.ledgerflow.api.webhook.WebhookDeliveryRepository.Delivery;
import com.ledgerflow.api.webhook.WebhookDeliveryRepository.Status;
import com.ledgerflow.common.webhook.WebhookSignature;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers one payment event to its merchant. Pure decision logic plus one HTTP call; the SQS
 * plumbing (acknowledging, changing visibility) lives in {@link WebhookListener}.
 *
 * <p>Delivery is at least once. We skip events already delivered, but if the merchant's 2xx is
 * lost on the way back we will send again, so merchants must deduplicate by the event id header.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    /** What the listener should do with the SQS message. */
    public sealed interface Outcome {
        /** Delete the message. */
        record Done(Status status) implements Outcome {
        }

        /** Keep the message and make it visible again after {@code delay}. */
        record RetryLater(Duration delay, String reason) implements Outcome {
        }
    }

    private final ObjectMapper objectMapper;
    private final MerchantRepository merchants;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookSigningKeyProvider signingKey;
    private final WebhookProperties properties;
    private final HttpClient http;
    private final Clock clock;
    private final MeterRegistry meters;

    public WebhookDispatcher(ObjectMapper objectMapper,
                             MerchantRepository merchants,
                             WebhookDeliveryRepository deliveries,
                             WebhookSigningKeyProvider signingKey,
                             WebhookProperties properties,
                             Clock clock,
                             MeterRegistry meters) {
        this.objectMapper = objectMapper;
        this.merchants = merchants;
        this.deliveries = deliveries;
        this.signingKey = signingKey;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    /**
     * @param body         the event JSON exactly as published (this is also what gets signed)
     * @param receiveCount how many times SQS has handed out this message, including now
     */
    public Outcome dispatch(String body, int receiveCount) {
        PaymentEvent event = objectMapper.readValue(body, PaymentEvent.class);
        Delivery delivery = deliveries.findOrCreate(event.eventId(), event.merchantId(), event.eventType());
        if (delivery.status() != Status.PENDING) {
            meters.counter("ledgerflow.webhooks.duplicates").increment();
            return new Outcome.Done(delivery.status()); // already handled: SNS/SQS or the relay sent it twice
        }

        String url = merchants.findById(event.merchantId()).map(Merchant::getWebhookUrl).orElse(null);
        if (url == null || url.isBlank()) {
            deliveries.markSkipped(event.eventId(), clock.instant());
            return new Outcome.Done(Status.SKIPPED);
        }

        Integer statusCode = null;
        String error;
        try {
            HttpResponse<Void> response = http.send(request(url, event, body), HttpResponse.BodyHandlers.discarding());
            statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                deliveries.recordAttempt(event.eventId(), Status.DELIVERED, statusCode, null, clock.instant());
                meters.counter("ledgerflow.webhooks.delivered", "attempt", String.valueOf(Math.min(receiveCount, 5)))
                        .increment();
                return new Outcome.Done(Status.DELIVERED);
            }
            error = "HTTP " + statusCode;
        } catch (IOException e) {
            error = e.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        }

        if (receiveCount >= properties.maxAttempts()) {
            // This was the last allowed receive; SQS moves the message to the DLQ on the next one.
            deliveries.recordAttempt(event.eventId(), Status.DEAD_LETTERED, statusCode, error, clock.instant());
            meters.counter("ledgerflow.webhooks.dead_lettered").increment();
            log.warn("Webhook {} to merchant {} failed {} times, sending to DLQ: {}",
                    event.eventId(), event.merchantId(), receiveCount, error);
            return new Outcome.RetryLater(Duration.ZERO, error);
        }
        deliveries.recordAttempt(event.eventId(), Status.PENDING, statusCode, error, clock.instant());
        meters.counter("ledgerflow.webhooks.failed_attempts").increment();
        return new Outcome.RetryLater(backoff(receiveCount), error);
    }

    private HttpRequest request(String url, PaymentEvent event, String body) {
        Instant now = clock.instant();
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(properties.readTimeout())
                .header("Content-Type", "application/json")
                .header(WebhookSignature.HEADER, WebhookSignature.sign(signingKey.currentSecret(), now, body))
                .header(WebhookSignature.EVENT_ID_HEADER, event.eventId().toString())
                .header(WebhookSignature.EVENT_TYPE_HEADER, event.eventType())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /** initialBackoff x 2^(attempt-1), capped. Attempt 1 failed -> wait initialBackoff. */
    private Duration backoff(int receiveCount) {
        Duration delay = properties.initialBackoff().multipliedBy(1L << Math.min(receiveCount - 1, 16));
        return delay.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : delay;
    }
}
