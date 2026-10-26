package com.ledgerflow.mockbank.settlement;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param bucket       S3 bucket shared with LedgerFlow
 * @param prefix       key prefix of settlement files; LedgerFlow is notified for {@code *.csv} under it
 * @param triggerQueue SQS queue EventBridge Scheduler sends the daily "generate settlement" message to
 */
@ConfigurationProperties(prefix = "mockbank.settlement")
public record SettlementProperties(String bucket, String prefix, String triggerQueue) {
}
