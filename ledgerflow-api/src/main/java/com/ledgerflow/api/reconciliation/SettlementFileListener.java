package com.ledgerflow.api.reconciliation;

import io.awspring.cloud.sqs.annotation.SqsListener;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reacts to S3 "object created" notifications for settlement files. A failure leaves the message
 * on the queue; after 3 receives it goes to the settlement-files DLQ for a human to look at.
 */
@Component
public class SettlementFileListener {

    private static final Logger log = LoggerFactory.getLogger(SettlementFileListener.class);

    private final ReconciliationService reconciliation;
    private final ObjectMapper objectMapper;

    public SettlementFileListener(ReconciliationService reconciliation, ObjectMapper objectMapper) {
        this.reconciliation = reconciliation;
        this.objectMapper = objectMapper;
    }

    @SqsListener(value = "${ledgerflow.reconciliation.queue}", maxConcurrentMessages = "1", maxMessagesPerPoll = "1")
    public void onS3Event(String body) {
        JsonNode records = objectMapper.readTree(body).get("Records");
        if (records == null) {
            log.info("Ignoring S3 message without records (e.g. s3:TestEvent)");
            return;
        }
        for (JsonNode record : records) {
            String bucket = record.at("/s3/bucket/name").asString();
            // Keys in S3 event notifications are URL-encoded.
            String key = URLDecoder.decode(record.at("/s3/object/key").asString(), StandardCharsets.UTF_8);
            reconciliation.reconcileFile(bucket, key);
        }
    }
}
