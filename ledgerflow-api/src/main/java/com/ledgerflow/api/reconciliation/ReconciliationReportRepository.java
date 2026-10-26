package com.ledgerflow.api.reconciliation;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationReportRepository {

    private final JdbcClient jdbc;

    public ReconciliationReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(ReconciliationReport report, String json) {
        ReconciliationReport.Summary s = report.summary();
        jdbc.sql("""
                        INSERT INTO reconciliation_report
                            (id, business_date, source_key, bank_rows, ledger_payments, matched, missing_in_bank,
                             missing_in_ledger, duplicates, amount_mismatches, report, created_at)
                        VALUES (:id, :date, :key, :bankRows, :ledgerPayments, :matched, :missingInBank,
                                :missingInLedger, :duplicates, :amountMismatches, CAST(:report AS jsonb), :createdAt)
                        """)
                .param("id", report.id())
                .param("date", Date.valueOf(report.businessDate()))
                .param("key", report.sourceKey())
                .param("bankRows", s.bankRows())
                .param("ledgerPayments", s.ledgerPayments())
                .param("matched", s.matched())
                .param("missingInBank", s.missingInBank())
                .param("missingInLedger", s.missingInLedger())
                .param("duplicates", s.duplicates())
                .param("amountMismatches", s.amountMismatches())
                .param("report", json)
                .param("createdAt", Timestamp.from(report.generatedAt()))
                .update();
    }

    /** The most recent report for a business date, as stored JSON. */
    public Optional<String> latestJson(LocalDate businessDate) {
        return jdbc.sql("""
                        SELECT report::text FROM reconciliation_report
                         WHERE business_date = :date
                         ORDER BY created_at DESC
                         LIMIT 1
                        """)
                .param("date", Date.valueOf(businessDate))
                .query(String.class)
                .optional();
    }
}
