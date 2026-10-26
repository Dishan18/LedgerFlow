package com.ledgerflow.api.ledger;

import com.ledgerflow.api.support.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "account")
public class Account extends AssignedIdEntity {

    public static final String SYSTEM_OWNER = "SYSTEM";

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 32)
    private AccountType accountType;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Balance in paise, on the account's normal side. */
    @Column(nullable = false)
    private long balance;

    @Column(name = "allow_negative", nullable = false)
    private boolean allowNegative;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // for JPA
    }

    private Account(UUID id, AccountType accountType, String ownerId) {
        this.id = id;
        this.accountType = accountType;
        this.ownerId = ownerId;
        this.currency = "INR";
        this.balance = 0;
        this.allowNegative = accountType.allowNegative();
    }

    public static Account open(AccountType type, String ownerId) {
        return new Account(UUID.randomUUID(), type, ownerId);
    }

    /**
     * Applies one journal line to this account. Callers must hold a row lock on the
     * account (see {@link AccountRepository#lockAllById}) so two transactions can't
     * both read the same old balance.
     *
     * @throws InsufficientFundsException if the account may not go negative and would
     */
    void apply(Direction direction, long amount) {
        long newBalance = direction == accountType.normalSide()
                ? Math.addExact(balance, amount)
                : Math.subtractExact(balance, amount);
        if (!allowNegative && newBalance < 0) {
            throw new InsufficientFundsException(id, balance, amount);
        }
        this.balance = newBalance;
    }

    public UUID getId() {
        return id;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalance() {
        return balance;
    }

    public boolean isAllowNegative() {
        return allowNegative;
    }
}
