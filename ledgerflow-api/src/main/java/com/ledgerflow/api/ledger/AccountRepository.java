package com.ledgerflow.api.ledger;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountTypeAndOwnerId(AccountType accountType, String ownerId);

    /**
     * Looks up only the id, without loading the entity. Business code must not load an Account
     * before {@link #lockAllById} does: Hibernate keeps the already-loaded (possibly stale)
     * balance even after a later locking query returns the same row.
     */
    @Query("select a.id from Account a where a.accountType = :type and a.ownerId = :ownerId")
    Optional<UUID> findIdByTypeAndOwner(@Param("type") AccountType type, @Param("ownerId") String ownerId);

    /**
     * Locks the given accounts with {@code SELECT ... FOR NO KEY UPDATE}, always in id order.
     * A fixed lock order means two transactions touching the same pair of accounts can't
     * each hold one lock while waiting for the other, so they can't deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllById(@Param("ids") Collection<UUID> ids);
}
