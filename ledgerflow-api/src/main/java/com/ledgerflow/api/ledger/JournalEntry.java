package com.ledgerflow.api.ledger;

import com.ledgerflow.api.support.AssignedIdEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

/**
 * A posted journal entry. {@link Immutable} tells Hibernate never to issue UPDATEs;
 * database triggers additionally reject UPDATE/DELETE from any client.
 */
@Entity
@Immutable
@Table(name = "journal_entry")
public class JournalEntry extends AssignedIdEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 32)
    private EntryType entryType;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "reverses_entry_id")
    private UUID reversesEntryId;

    @Column(nullable = false)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST)
    private List<JournalLine> lines = new ArrayList<>();

    protected JournalEntry() {
        // for JPA
    }

    static JournalEntry from(JournalEntryDraft draft) {
        JournalEntry entry = new JournalEntry();
        entry.id = UUID.randomUUID();
        entry.entryType = draft.entryType();
        entry.paymentId = draft.paymentId();
        entry.reversesEntryId = draft.reversesEntryId();
        entry.description = draft.description();
        for (JournalEntryDraft.Line line : draft.lines()) {
            entry.lines.add(new JournalLine(entry, line.accountId(), line.direction(), line.amount()));
        }
        return entry;
    }

    public UUID getId() {
        return id;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getReversesEntryId() {
        return reversesEntryId;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<JournalLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
