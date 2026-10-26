package com.ledgerflow.common.settlement;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The bank's daily settlement file: one row per collect the bank settled on that business date.
 *
 * <pre>
 * settlement_date,utr,payment_id,payer_vpa,amount_paise
 * 2026-10-27,123456789012,6f1c...,alice@upi,25000
 * </pre>
 *
 * Shared by mock-bank (writer) and LedgerFlow (reader) so the two can't disagree on the format.
 */
public final class SettlementCsv {

    /** Business dates are Indian dates: a collect at 00:30 IST belongs to that day, not the previous UTC day. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    public static final String HEADER = "settlement_date,utr,payment_id,payer_vpa,amount_paise";

    public record Row(LocalDate settlementDate, String utr, UUID paymentId, String payerVpa, long amount) {
    }

    private SettlementCsv() {
    }

    /** S3 key of the settlement file for a business date. */
    public static String key(String prefix, LocalDate date) {
        return prefix + date + "/settlement.csv";
    }

    /** Inverse of {@link #key}: the business date in {@code <prefix><date>/settlement.csv}. */
    public static LocalDate dateFromKey(String key) {
        String[] parts = key.split("/");
        if (parts.length < 2 || !parts[parts.length - 1].equals("settlement.csv")) {
            throw new IllegalArgumentException("not a settlement file key: " + key);
        }
        return LocalDate.parse(parts[parts.length - 2]);
    }

    public static String write(List<Row> rows) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (Row row : rows) {
            csv.append(row.settlementDate()).append(',')
                    .append(row.utr()).append(',')
                    .append(row.paymentId()).append(',')
                    .append(row.payerVpa()).append(',')
                    .append(row.amount()).append('\n');
        }
        return csv.toString();
    }

    /**
     * Parses a whole file.
     *
     * @throws IllegalArgumentException on a wrong header or malformed row, naming the line
     */
    public static List<Row> parse(String csv) {
        String[] lines = csv.split("\r?\n");
        if (lines.length == 0 || !lines[0].trim().equals(HEADER)) {
            throw new IllegalArgumentException("not a settlement file: unexpected header");
        }
        List<Row> rows = new ArrayList<>(lines.length - 1);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(",", -1);
            if (f.length != 5) {
                throw new IllegalArgumentException("line " + (i + 1) + ": expected 5 fields, got " + f.length);
            }
            try {
                rows.add(new Row(LocalDate.parse(f[0]), f[1], UUID.fromString(f[2]), f[3], Long.parseLong(f[4])));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return rows;
    }
}
