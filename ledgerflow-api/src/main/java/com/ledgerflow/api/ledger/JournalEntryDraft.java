package com.ledgerflow.api.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable, validated journal entry that has not been posted yet.
 *
 * <p>Construction fails unless the entry has at least two lines, every amount is
 * positive and total debits equal total credits. Keeping this check in a plain value
 * object (no Spring, no database) makes it trivial to property-test.
 */
public record JournalEntryDraft(
        EntryType entryType,
        UUID paymentId,
        UUID reversesEntryId,
        String description,
        List<Line> lines) {

    public record Line(UUID accountId, Direction direction, long amount) {
    }

    public JournalEntryDraft {
        Objects.requireNonNull(entryType, "entryType");
        Objects.requireNonNull(description, "description");
        lines = List.copyOf(lines);
        if (lines.size() < 2) {
            throw new UnbalancedEntryException("a journal entry needs at least two lines");
        }
        long debits = 0;
        long credits = 0;
        for (Line line : lines) {
            Objects.requireNonNull(line.accountId(), "accountId");
            Objects.requireNonNull(line.direction(), "direction");
            if (line.amount() <= 0) {
                throw new UnbalancedEntryException("line amounts must be positive, got " + line.amount());
            }
            if (line.direction() == Direction.DEBIT) {
                debits = Math.addExact(debits, line.amount());
            } else {
                credits = Math.addExact(credits, line.amount());
            }
        }
        if (debits != credits) {
            throw new UnbalancedEntryException("debits " + debits + " != credits " + credits);
        }
    }

    public long totalDebits() {
        return lines.stream().filter(l -> l.direction() == Direction.DEBIT).mapToLong(Line::amount).sum();
    }

    public static Builder builder(EntryType entryType, String description) {
        return new Builder(entryType, description);
    }

    public static final class Builder {
        private final EntryType entryType;
        private final String description;
        private final List<Line> lines = new ArrayList<>();
        private UUID paymentId;
        private UUID reversesEntryId;

        private Builder(EntryType entryType, String description) {
            this.entryType = entryType;
            this.description = description;
        }

        public Builder paymentId(UUID paymentId) {
            this.paymentId = paymentId;
            return this;
        }

        public Builder reverses(UUID entryId) {
            this.reversesEntryId = entryId;
            return this;
        }

        /** Adds a debit line. Zero amounts are skipped so callers needn't special-case a zero fee. */
        public Builder debit(UUID accountId, long amount) {
            return line(accountId, Direction.DEBIT, amount);
        }

        public Builder credit(UUID accountId, long amount) {
            return line(accountId, Direction.CREDIT, amount);
        }

        private Builder line(UUID accountId, Direction direction, long amount) {
            if (amount != 0) {
                lines.add(new Line(accountId, direction, amount));
            }
            return this;
        }

        public JournalEntryDraft build() {
            return new JournalEntryDraft(entryType, paymentId, reversesEntryId, description, lines);
        }
    }
}
