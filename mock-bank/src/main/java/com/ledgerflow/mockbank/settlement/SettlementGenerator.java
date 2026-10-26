package com.ledgerflow.mockbank.settlement;

import com.ledgerflow.common.settlement.SettlementCsv;
import com.ledgerflow.mockbank.BankTransaction;
import com.ledgerflow.mockbank.CollectService;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the day's settlement file to S3, optionally with planted mistakes. The list of planted
 * mistakes goes to a separate JSON file next to it: LedgerFlow never reads it, it's the answer key
 * used to measure what share of mistakes reconciliation catches.
 */
@Service
public class SettlementGenerator {

    private static final Logger log = LoggerFactory.getLogger(SettlementGenerator.class);

    /** What was planted, by payment id. */
    public record Injected(List<UUID> dropped, List<UUID> duplicated, List<UUID> wrongAmount, List<UUID> phantom) {
        public int total() {
            return dropped.size() + duplicated.size() + wrongAmount.size() + phantom.size();
        }
    }

    public record Result(LocalDate businessDate, String key, int rows, Injected injected) {
    }

    private final CollectService collects;
    private final SettlementStore store;
    private final SettlementProperties properties;
    private final RandomGenerator random;
    private final ObjectMapper objectMapper;

    public SettlementGenerator(CollectService collects, SettlementStore store, SettlementProperties properties,
                               RandomGenerator random, ObjectMapper objectMapper) {
        this.collects = collects;
        this.store = store;
        this.properties = properties;
        this.random = random;
        this.objectMapper = objectMapper;
    }

    public Result generate(LocalDate date, Discrepancies plant) {
        List<SettlementCsv.Row> rows = new ArrayList<>();
        for (BankTransaction t : collects.approvedOn(date)) {
            rows.add(new SettlementCsv.Row(date, t.utr(), t.paymentId(), t.payerVpa(), t.amount()));
        }

        // Each real row is the victim of at most one planted mistake, so the answer key is unambiguous.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            order.add(i);
        }
        Collections.shuffle(order, random);
        Deque<Integer> victims = new ArrayDeque<>(order);

        List<UUID> wrongAmount = new ArrayList<>();
        for (int i = 0; i < plant.wrongAmount() && !victims.isEmpty(); i++) {
            int idx = victims.pop();
            SettlementCsv.Row row = rows.get(idx);
            rows.set(idx, new SettlementCsv.Row(date, row.utr(), row.paymentId(), row.payerVpa(),
                    row.amount() + 1 + random.nextInt(1_000)));
            wrongAmount.add(row.paymentId());
        }
        List<UUID> duplicated = new ArrayList<>();
        List<SettlementCsv.Row> extra = new ArrayList<>();
        for (int i = 0; i < plant.duplicate() && !victims.isEmpty(); i++) {
            SettlementCsv.Row row = rows.get(victims.pop());
            extra.add(row);
            duplicated.add(row.paymentId());
        }
        List<Integer> dropIdx = new ArrayList<>();
        List<UUID> dropped = new ArrayList<>();
        for (int i = 0; i < plant.drop() && !victims.isEmpty(); i++) {
            int idx = victims.pop();
            dropIdx.add(idx);
            dropped.add(rows.get(idx).paymentId());
        }
        dropIdx.sort(Collections.reverseOrder());
        for (int idx : dropIdx) {
            rows.remove(idx);
        }
        rows.addAll(extra);
        List<UUID> phantom = new ArrayList<>();
        for (int i = 0; i < plant.phantom(); i++) {
            UUID id = UUID.randomUUID();
            rows.add(new SettlementCsv.Row(date, String.format("%012d", random.nextLong(1_000_000_000_000L)), id,
                    "phantom@upi", 100 + random.nextInt(100_000)));
            phantom.add(id);
        }

        Injected injected = new Injected(dropped, duplicated, wrongAmount, phantom);
        String key = SettlementCsv.key(properties.prefix(), date);
        // Answer key first: by the time LedgerFlow hears about the CSV, the key is already there.
        store.put(properties.prefix() + date + "/injected-discrepancies.json",
                objectMapper.writeValueAsString(injected), "application/json");
        store.put(key, SettlementCsv.write(rows), "text/csv");
        log.info("Wrote settlement {} with {} rows and {} planted discrepancies", key, rows.size(), injected.total());
        return new Result(date, key, rows.size(), injected);
    }
}
