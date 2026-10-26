package com.ledgerflow.api.reconciliation;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param queue         SQS queue receiving S3 "object created" events for settlement files
 * @param reportsBucket bucket the JSON copy of each report is written to
 * @param reportsPrefix key prefix for those copies
 */
@Validated
@ConfigurationProperties(prefix = "ledgerflow.reconciliation")
public record ReconciliationProperties(
        @NotBlank String queue,
        @NotBlank String reportsBucket,
        @NotBlank String reportsPrefix) {
}
