package com.ledgerflow.api.ledger;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way money moves. Posting an entry updates the cached balance of every account it
 * touches and inserts the entry, all in the caller's transaction.
 *
 * <p>Accounts are updated in one of two ways:
 * <ul>
 *   <li><b>Guarded</b> (may not go negative, e.g. customers): {@code SELECT ... FOR UPDATE} in id
 *       order, check the balance, write it back. The lock is what prevents two concurrent debits
 *       from both seeing enough money.
 *   <li><b>Unguarded</b> (may go negative: merchant receivable, platform fee, bank): no read at all,
 *       just {@code UPDATE ... SET balance = balance + delta}, issued last. There is nothing to check,
 *       so there is no need to hold the row lock for the whole transaction. These accounts are
 *       touched by every capture, so a long lock on them caps throughput for the whole system
 *       (the "hot account" problem).
 * </ul>
 * Locks are always taken in the same order (guarded accounts by id, then unguarded accounts by id),
 * so concurrent postings can't deadlock.
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final EntityManager entityManager;
    private final Clock clock;

    public LedgerService(AccountRepository accounts, JournalEntryRepository entries, EntityManager entityManager,
                         Clock clock) {
        this.accounts = accounts;
        this.entries = entries;
        this.entityManager = entityManager;
        this.clock = clock;
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
        Map<UUID, AccountRepository.PostingRule> rules = accounts.findPostingRules(accountIds).stream()
                .collect(Collectors.toMap(AccountRepository.PostingRule::getId, Function.identity()));
        if (rules.size() != accountIds.size()) {
            throw new IllegalArgumentException("unknown account in journal entry: " + accountIds);
        }

        // 1. Guarded accounts: lock, check, update.
        Set<UUID> guarded = accountIds.stream()
                .filter(id -> !rules.get(id).getAllowNegative())
                .collect(Collectors.toCollection(TreeSet::new));
        if (!guarded.isEmpty()) {
            Map<UUID, Account> locked = accounts.lockAllById(guarded).stream()
                    .collect(Collectors.toMap(Account::getId, Function.identity()));
            for (JournalEntryDraft.Line line : draft.lines()) {
                Account account = locked.get(line.accountId());
                if (account != null) {
                    account.apply(line.direction(), line.amount());
                }
            }
        }

        // 2. The entry itself, written out now so it isn't flushed while we hold the hot locks below.
        JournalEntry entry = entries.save(JournalEntry.from(draft));
        entityManager.flush();

        // 3. Unguarded accounts: one atomic increment each, in id order, as late as possible.
        Map<UUID, Long> deltas = new TreeMap<>();
        for (JournalEntryDraft.Line line : draft.lines()) {
            AccountRepository.PostingRule rule = rules.get(line.accountId());
            if (rule.getAllowNegative()) {
                deltas.merge(line.accountId(), rule.getAccountType().signedAmount(line.direction(), line.amount()),
                        Math::addExact);
            }
        }
        deltas.forEach((id, delta) -> accounts.addToBalance(id, delta, clock.instant()));
        return entry;
    }
}
