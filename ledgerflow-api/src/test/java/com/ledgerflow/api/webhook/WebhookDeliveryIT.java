package com.ledgerflow.api.webhook;

import static com.ledgerflow.api.support.TestData.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ledgerflow.api.outbox.OutboxEvent;
import com.ledgerflow.api.outbox.OutboxProperties;
import com.ledgerflow.api.outbox.OutboxRepository;
import com.ledgerflow.api.support.AbstractIntegrationTest;
import com.ledgerflow.api.support.Eventually;
import com.ledgerflow.api.support.MerchantReceiver;
import com.ledgerflow.api.webhook.WebhookDeliveryRepository.Status;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Phase 4: the full event pipeline against LocalStack.
 * payment change -> outbox row -> relay -> SNS payment-events -> SQS webhook-delivery -> dispatcher
 * -> signed POST to the merchant (or, after 5 failures, the DLQ).
 */
class WebhookDeliveryIT extends AbstractIntegrationTest {

    private static final Duration PIPELINE_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    OutboxRepository outbox;

    @Autowired
    WebhookDeliveryRepository deliveries;

    @Autowired
    SnsClient sns;

    @Autowired
    SqsAsyncClient sqs;

    @Autowired
    OutboxProperties outboxProperties;

    @Autowired
    MeterRegistry meters;

    private MerchantReceiver receiver;
    private UUID merchant;
    private String customer;

    @BeforeEach
    void setUp() {
        receiver = MerchantReceiver.start(WEBHOOK_SECRET);
        merchant = data.merchant(200, receiver.url());
        customer = data.customer(100_000);
    }

    @AfterEach
    void tearDown() {
        receiver.stop();
    }

    @Test
    void everyPaymentEventReachesTheMerchantSigned() throws Exception {
        UUID paymentId = createPayment();
        capture(paymentId);

        Eventually.await("3 webhooks", PIPELINE_TIMEOUT, () -> receiver.received().size() >= 3);

        assertThat(receiver.received()).extracting(MerchantReceiver.Received::eventType)
                .containsExactlyInAnyOrder("payment.created", "payment.authorized", "payment.captured");
        assertThat(receiver.received()).allSatisfy(r -> {
            assertThat(r.signatureValid()).as("signature of " + r.eventType()).isTrue();
            assertThat(r.body()).contains(paymentId.toString());
        });
        assertThat(outbox.findByAggregateIdOrderByCreatedAt(paymentId))
                .allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull());
        assertThat(receiver.received()).allSatisfy(r ->
                assertThat(deliveries.find(r.eventId()).orElseThrow().status()).isEqualTo(Status.DELIVERED));
    }

    @Test
    void failedDeliveriesAreRetriedUntilTheMerchantAccepts() throws Exception {
        receiver.failFirst(2);

        createPayment();

        Eventually.await("2 webhooks delivered", PIPELINE_TIMEOUT, () -> receiver.received().size() >= 2);
        int totalAttempts = receiver.received().stream()
                .mapToInt(r -> deliveries.find(r.eventId()).orElseThrow().attempts()).sum();
        assertThat(totalAttempts).isEqualTo(4); // 2 failures + 2 successes, spread over the 2 events
        assertThat(receiver.received()).allSatisfy(r -> assertThat(r.signatureValid()).isTrue());
    }

    @Test
    void merchantThatAlwaysFailsEndsUpInTheDeadLetterQueue() throws Exception {
        receiver.failFirst(Integer.MAX_VALUE);

        UUID paymentId = createPayment();
        Set<UUID> eventIds = outbox.findByAggregateIdOrderByCreatedAt(paymentId).stream()
                .map(OutboxEvent::getId).collect(Collectors.toSet());

        Eventually.await("both events dead-lettered", Duration.ofSeconds(60), () -> eventIds.stream()
                .allMatch(id -> deliveries.find(id).map(d -> d.status() == Status.DEAD_LETTERED).orElse(false)));
        assertThat(eventIds).allSatisfy(id -> assertThat(deliveries.find(id).orElseThrow().attempts()).isEqualTo(5));

        String dlqUrl = sqs.getQueueUrl(b -> b.queueName("webhook-delivery-dlq")).join().queueUrl();
        Set<String> inDlq = new HashSet<>();
        Eventually.await("both events in the DLQ", Duration.ofSeconds(30), () -> {
            List<Message> messages = sqs.receiveMessage(b -> b.queueUrl(dlqUrl).maxNumberOfMessages(10).waitTimeSeconds(1))
                    .join().messages();
            for (Message m : messages) {
                inDlq.add(JsonPath.read(m.body(), "$.eventId"));
            }
            return eventIds.stream().map(UUID::toString).allMatch(inDlq::contains);
        });
        assertThat(receiver.received()).isEmpty();
    }

    @Test
    void duplicatePublishIsDeliveredOnce() throws Exception {
        UUID paymentId = createPayment();
        Eventually.await("2 webhooks", PIPELINE_TIMEOUT, () -> receiver.received().size() >= 2);
        OutboxEvent created = outbox.findByAggregateIdOrderByCreatedAt(paymentId).getFirst();

        double duplicatesBefore = meters.counter("ledgerflow.webhooks.duplicates").count();

        // Simulate the relay crashing after publishing but before marking the row: same event again.
        sns.publish(b -> b.topicArn(outboxProperties.topicArn()).message(created.getPayload()));

        // In a full test run the queue may still hold hundreds of events from earlier test classes,
        // and the duplicate waits its turn behind them.
        Eventually.await("duplicate reached the consumer", Duration.ofSeconds(120),
                () -> meters.counter("ledgerflow.webhooks.duplicates").count() > duplicatesBefore);
        long deliveriesOfCreated = receiver.received().stream().filter(r -> r.eventId().equals(created.getId())).count();
        assertThat(deliveriesOfCreated).isEqualTo(1);
        assertThat(deliveries.find(created.getId()).orElseThrow().attempts()).isEqualTo(1);
    }

    @Test
    void merchantWithoutWebhookUrlIsSkipped() throws Exception {
        UUID silentMerchant = data.merchant(200);
        String body = mvc.perform(post("/v1/payments").with(as(silentMerchant))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"amount\":1000}"))
                .andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(JsonPath.read(body, "$.id"));
        UUID eventId = outbox.findByAggregateIdOrderByCreatedAt(paymentId).getFirst().getId();

        Eventually.await("event skipped", PIPELINE_TIMEOUT, () -> deliveries.find(eventId)
                .map(d -> d.status() == Status.SKIPPED).orElse(false));
    }

    private UUID createPayment() throws Exception {
        String body = mvc.perform(post("/v1/payments").with(as(merchant))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"amount\":1000}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void capture(UUID paymentId) throws Exception {
        mvc.perform(post("/v1/payments/{id}/capture", paymentId).with(as(merchant))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
    }
}
