package com.ledgerflow.api.ledger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    Optional<JournalEntry> findFirstByPaymentIdAndEntryType(UUID paymentId, EntryType entryType);

    List<JournalEntry> findByPaymentIdOrderByCreatedAt(UUID paymentId);
}
