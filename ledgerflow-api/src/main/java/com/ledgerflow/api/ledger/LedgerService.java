package com.ledgerflow.api.ledger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way money moves. Posting an entry locks every account it touches, applies
 * each line to the cached balances and inserts the entry, all in the caller's transaction.
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final JournalEntryRepository entries;

    public LedgerService(AccountRepository accounts, JournalEntryRepository entries) {
        this.accounts = accounts;
        this.entries = entries;
    }

    /**
     * Posts a balanced entry. Must run inside an existing transaction so the entry commits
     * (or rolls back) together with the business change that caused it, e.g. a capture.
     *
     * @throws InsufficientFundsException if a non-negative account would be overdrawn;
     *         nothing is written in that case because the caller's transaction rolls back
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public JournalEntry post(JournalEntryDraft draft) {
        Set<UUID> accountIds = draft.lines().stream()
                .map(JournalEntryDraft.Line::accountId)
                .collect(Collectors.toCollection(TreeSet::new));

        List<Account> locked = accounts.lockAllById(accountIds);
        if (locked.size() != accountIds.size()) {
            throw new IllegalArgumentException("unknown account in journal entry: " + accountIds);
        }
        Map<UUID, Account> byId = locked.stream().collect(Collectors.toMap(Account::getId, Function.identity()));

        for (JournalEntryDraft.Line line : draft.lines()) {
            byId.get(line.accountId()).apply(line.direction(), line.amount());
        }
        return entries.save(JournalEntry.from(draft));
    }
}
