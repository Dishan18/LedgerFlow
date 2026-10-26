package com.ledgerflow.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerflow.api.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database enforces the ledger rules on its own, so even a bug in Java (or someone with a
 * psql prompt) can't corrupt the books.
 */
class LedgerInvariantsIT extends AbstractIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void journalLinesCannotBeUpdated() {
        String customer = data.customer(5_000);
        UUID lineId = anyLineOf(customer);

        assertThatThrownBy(() -> jdbc.sql("UPDATE journal_line SET amount = 1 WHERE id = :id").param("id", lineId).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void journalLinesCannotBeDeleted() {
        String customer = data.customer(5_000);
        UUID lineId = anyLineOf(customer);

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_line WHERE id = :id").param("id", lineId).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void journalEntriesCannotBeDeletedOrTruncated() {
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_entry").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE journal_entry CASCADE").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void unbalancedEntryIsRejectedAtCommit() {
        UUID entryId = UUID.randomUUID();
        UUID bank = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID fees = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.sql("INSERT INTO journal_entry (id, entry_type, description) VALUES (:id, 'TRANSFER', 'bad')")
                    .param("id", entryId).update();
            jdbc.sql("INSERT INTO journal_line (id, entry_id, account_id, direction, amount) VALUES (:l, :e, :a, 'DEBIT', 100)")
                    .param("l", UUID.randomUUID()).param("e", entryId).param("a", bank).update();
            jdbc.sql("INSERT INTO journal_line (id, entry_id, account_id, direction, amount) VALUES (:l, :e, :a, 'CREDIT', 99)")
                    .param("l", UUID.randomUUID()).param("e", entryId).param("a", fees).update();
        })).rootCause().hasMessageContaining("unbalanced");

        assertThat(jdbc.sql("SELECT count(*) FROM journal_entry WHERE id = :id").param("id", entryId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void customerBalanceCannotGoNegativeEvenWithRawSql() {
        String customer = data.customer(0);

        assertThatThrownBy(() -> jdbc.sql("UPDATE account SET balance = -1 WHERE owner_id = :o").param("o", customer).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("account_no_overdraft");
    }

    private UUID anyLineOf(String customerId) {
        return jdbc.sql("""
                        SELECT jl.id FROM journal_line jl JOIN account a ON a.id = jl.account_id
                         WHERE a.owner_id = :o LIMIT 1
                        """)
                .param("o", customerId)
                .query(UUID.class).single();
    }
}
