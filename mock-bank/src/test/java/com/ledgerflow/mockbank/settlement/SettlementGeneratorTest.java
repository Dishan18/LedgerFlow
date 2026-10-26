package com.ledgerflow.mockbank.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.common.bank.CollectRequest;
import com.ledgerflow.common.settlement.SettlementCsv;
import com.ledgerflow.mockbank.ChaosProperties;
import com.ledgerflow.mockbank.CollectService;
import com.ledgerflow.mockbank.ScenarioPicker;
import com.ledgerflow.mockbank.Sleeper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SettlementGeneratorTest {

    // 10:00 IST on 27 Oct 2026
    private static final Instant NOW = Instant.parse("2026-10-27T04:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    private final Map<String, String> store = new HashMap<>();
    private final List<UUID> approved = new ArrayList<>();
    private CollectService collects;
    private SettlementGenerator generator;

    @BeforeEach
    void setUp() {
        RandomGenerator random = new Random();
        ChaosProperties calm = new ChaosProperties(Duration.ZERO, Duration.ZERO, 0, 0, 0, 0, 1, Duration.ZERO,
                Duration.ofSeconds(15));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        collects = new CollectService(new ScenarioPicker(calm, random), calm, random, clock, new Sleeper());
        generator = new SettlementGenerator(collects, (key, content, type) -> store.put(key, content),
                new SettlementProperties("bucket", "settlements/", "trigger"), random, JsonMapper.builder().build());

        for (int i = 0; i < 20; i++) {
            UUID id = UUID.randomUUID();
            collects.collect(new CollectRequest(id, "c" + i + "@upi", 100 + i));
            approved.add(id);
        }
        collects.collect(new CollectRequest(UUID.randomUUID(), "x+decline@upi", 5)); // never settled
        collects.collect(new CollectRequest(UUID.randomUUID(), "x+pending@upi", 5)); // not yet settled
    }

    @Test
    void cleanFileListsExactlyTheApprovedCollects() {
        SettlementGenerator.Result result = generator.generate(TODAY, Discrepancies.none());

        List<SettlementCsv.Row> rows = SettlementCsv.parse(store.get("settlements/2026-10-27/settlement.csv"));
        assertThat(rows).extracting(SettlementCsv.Row::paymentId).containsExactlyInAnyOrderElementsOf(approved);
        assertThat(rows).allSatisfy(r -> assertThat(r.utr()).matches("\\d{12}"));
        assertThat(result.injected().total()).isZero();
    }

    @Test
    void otherDaysAreEmpty() {
        generator.generate(TODAY.minusDays(1), Discrepancies.none());

        assertThat(SettlementCsv.parse(store.get("settlements/2026-10-26/settlement.csv"))).isEmpty();
    }

    @Test
    void plantedDiscrepanciesHitDistinctRealCollectsAndAreRecorded() {
        SettlementGenerator.Result result = generator.generate(TODAY, new Discrepancies(3, 2, 4, 5));

        SettlementGenerator.Injected injected = result.injected();
        assertThat(injected.dropped()).hasSize(3);
        assertThat(injected.duplicated()).hasSize(2);
        assertThat(injected.wrongAmount()).hasSize(4);
        assertThat(injected.phantom()).hasSize(5);
        Set<UUID> victims = new HashSet<>();
        victims.addAll(injected.dropped());
        victims.addAll(injected.duplicated());
        victims.addAll(injected.wrongAmount());
        assertThat(victims).hasSize(9).allMatch(approved::contains);

        List<SettlementCsv.Row> rows = SettlementCsv.parse(store.get("settlements/2026-10-27/settlement.csv"));
        assertThat(rows).hasSize(20 - 3 + 2 + 5);
        assertThat(rows).extracting(SettlementCsv.Row::paymentId).doesNotContainAnyElementsOf(injected.dropped());
        assertThat(store).containsKey("settlements/2026-10-27/injected-discrepancies.json");
    }

    @Test
    void triggerWithoutDateSettlesYesterdayInIst() {
        SettlementTriggerListener listener = new SettlementTriggerListener(generator, JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-10-26T20:00:00Z"), ZoneOffset.UTC)); // 01:30 IST on 27 Oct

        assertThat(listener.businessDate("{}")).isEqualTo(TODAY.minusDays(1));
        assertThat(listener.businessDate("")).isEqualTo(TODAY.minusDays(1));
        assertThat(listener.businessDate("{\"businessDate\":\"2026-01-15\"}")).isEqualTo(LocalDate.of(2026, 1, 15));
    }
}
