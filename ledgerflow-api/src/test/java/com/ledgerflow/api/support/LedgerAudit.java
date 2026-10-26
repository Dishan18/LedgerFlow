package com.ledgerflow.api.support;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Independent checks of the whole ledger, written in SQL so they don't share any code (or bugs)
 * with the Java ledger they are checking.
 */
@TestComponent
public class LedgerAudit {

    private final JdbcClient jdbc;

    public LedgerAudit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Total debits minus total credits over every journal line ever written. Must be 0. */
    public long globalImbalance() {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0)
                          FROM journal_line
                        """)
                .query(Long.class).single();
    }

    /**
     * Accounts whose cached {@code balance} disagrees with the sum of their journal lines.
     * BANK_SETTLEMENT is debit-normal; every other type is credit-normal. Must be empty.
     */
    public List<UUID> accountsWithDriftedBalance() {
        return jdbc.sql("""
                        SELECT a.id
                          FROM account a
                          LEFT JOIN journal_line l ON l.account_id = a.id
                         GROUP BY a.id, a.account_type, a.balance
                        HAVING a.balance <> COALESCE(SUM(
                                   CASE WHEN (l.direction = 'DEBIT') = (a.account_type = 'BANK_SETTLEMENT')
                                        THEN l.amount ELSE -l.amount END), 0)
                        """)
                .query(UUID.class).list();
    }

    /** Accounts that may not go negative but did. Must be 0. */
    public long overdrawnAccounts() {
        return jdbc.sql("SELECT count(*) FROM account WHERE NOT allow_negative AND balance < 0")
                .query(Long.class).single();
    }
}
