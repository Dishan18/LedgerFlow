package com.ledgerflow.common.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementCsvTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 27);

    @Test
    void roundTrips() {
        List<SettlementCsv.Row> rows = List.of(
                new SettlementCsv.Row(DATE, "123456789012", UUID.randomUUID(), "alice@upi", 25_000),
                new SettlementCsv.Row(DATE, "000000000001", UUID.randomUUID(), "bob@upi", 1));

        assertThat(SettlementCsv.parse(SettlementCsv.write(rows))).isEqualTo(rows);
    }

    @Test
    void emptyFileHasOnlyTheHeader() {
        assertThat(SettlementCsv.write(List.of())).isEqualTo(SettlementCsv.HEADER + "\n");
        assertThat(SettlementCsv.parse(SettlementCsv.HEADER + "\r\n")).isEmpty();
    }

    @Test
    void rejectsWrongHeaderAndMalformedRows() {
        assertThatThrownBy(() -> SettlementCsv.parse("a,b,c\n")).hasMessageContaining("header");
        assertThatThrownBy(() -> SettlementCsv.parse(SettlementCsv.HEADER + "\n2026-10-27,1,not-a-uuid,a@upi,5\n"))
                .hasMessageContaining("line 2");
        assertThatThrownBy(() -> SettlementCsv.parse(SettlementCsv.HEADER + "\n2026-10-27,1,2\n"))
                .hasMessageContaining("expected 5 fields");
    }

    @Test
    void keyIsPerBusinessDate() {
        assertThat(SettlementCsv.key("settlements/", DATE)).isEqualTo("settlements/2026-10-27/settlement.csv");
        assertThat(SettlementCsv.dateFromKey("settlements/2026-10-27/settlement.csv")).isEqualTo(DATE);
        assertThatThrownBy(() -> SettlementCsv.dateFromKey("settlements/2026-10-27/other.csv"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
