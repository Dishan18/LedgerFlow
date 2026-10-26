package com.ledgerflow.mockbank.settlement;

import com.ledgerflow.common.settlement.SettlementCsv;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Daily trigger. EventBridge Scheduler drops a message on the trigger queue every night; the
 * message may name a business date, otherwise we settle yesterday (IST).
 */
@Component
public class SettlementTriggerListener {

    private final SettlementGenerator generator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SettlementTriggerListener(SettlementGenerator generator, ObjectMapper objectMapper, Clock clock) {
        this.generator = generator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @SqsListener("${mockbank.settlement.trigger-queue}")
    public void onTrigger(String body) {
        generator.generate(businessDate(body), Discrepancies.none());
    }

    LocalDate businessDate(String body) {
        if (body != null && !body.isBlank()) {
            JsonNode date = objectMapper.readTree(body).get("businessDate");
            if (date != null && !date.isNull()) {
                return LocalDate.parse(date.asString());
            }
        }
        return LocalDate.now(clock.withZone(SettlementCsv.BUSINESS_ZONE)).minusDays(1);
    }
}
