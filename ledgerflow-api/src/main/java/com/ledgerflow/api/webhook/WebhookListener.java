package com.ledgerflow.api.webhook;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.listener.Visibility;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code webhook-delivery}. Returning normally deletes the message; throwing leaves it on
 * the queue. Retries use the message's own visibility timeout as the backoff timer, so there is no
 * scheduler or retry table: SQS redelivers when the timeout expires and moves the message to the
 * DLQ once it has been received {@code maxReceiveCount} times.
 */
@Component
public class WebhookListener {

    private final WebhookDispatcher dispatcher;

    public WebhookListener(WebhookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @SqsListener(value = "${ledgerflow.webhooks.queue}", maxConcurrentMessages = "10")
    public void onPaymentEvent(String body,
                               @Header(SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT) String receiveCount,
                               Visibility visibility) {
        WebhookDispatcher.Outcome outcome = dispatcher.dispatch(body, Integer.parseInt(receiveCount));
        if (outcome instanceof WebhookDispatcher.Outcome.RetryLater retry) {
            visibility.changeTo((int) retry.delay().toSeconds());
            throw new WebhookDeliveryFailedException(retry.reason());
        }
    }

    /** Thrown only to tell the listener container not to delete the message. */
    static class WebhookDeliveryFailedException extends RuntimeException {
        WebhookDeliveryFailedException(String reason) {
            super("webhook delivery failed, will retry: " + reason, null, false, false);
        }
    }
}
