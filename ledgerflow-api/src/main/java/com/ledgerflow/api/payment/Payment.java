package com.ledgerflow.api.payment;

import com.ledgerflow.api.support.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A payment from a customer to a merchant. All status changes go through
 * {@link #transitionTo}, which enforces the {@link PaymentStatus} transition table.
 */
@Entity
@Table(name = "payment")
public class Payment extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(nullable = false)
    private long amount;

    @Column(name = "fee_amount", nullable = false)
    private long feeAmount;

    @Column(name = "refunded_amount", nullable = false)
    private long refundedAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "bank_reference", length = 64)
    private String bankReference;

    @Version
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // for JPA
    }

    public static Payment create(UUID merchantId, String customerId, long amount, long feeAmount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (feeAmount < 0 || feeAmount > amount) {
            throw new IllegalArgumentException("fee must be between 0 and amount");
        }
        Payment payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.merchantId = merchantId;
        payment.customerId = customerId;
        payment.amount = amount;
        payment.feeAmount = feeAmount;
        payment.refundedAmount = 0;
        payment.currency = "INR";
        payment.status = PaymentStatus.CREATED;
        return payment;
    }

    public void transitionTo(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(status, target);
        }
        this.status = target;
    }

    public void markAuthorized(String bankReference) {
        transitionTo(PaymentStatus.AUTHORIZED);
        this.bankReference = bankReference;
    }

    public void capture() {
        transitionTo(PaymentStatus.CAPTURED);
    }

    /** Captured amount minus earlier refunds. Capture is always for the full amount. */
    public long refundableAmount() {
        return status.isRefundable() ? amount - refundedAmount : 0;
    }

    /**
     * Records a refund and moves to PARTIALLY_REFUNDED or REFUNDED.
     *
     * @return the share of the original fee that this refund gives back
     */
    public long applyRefund(long refundAmount) {
        if (refundAmount <= 0) {
            throw new IllegalArgumentException("refund amount must be positive");
        }
        PaymentStatus target = refundedAmount + refundAmount == amount
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
        if (!status.isRefundable()) {
            throw new IllegalStateTransitionException(status, target);
        }
        if (refundAmount > refundableAmount()) {
            throw new RefundExceedsCapturedException(refundAmount, refundableAmount());
        }
        long feePortion = FeeCalculator.refundFeePortion(feeAmount, amount, refundedAmount, refundAmount);
        transitionTo(target);
        this.refundedAmount += refundAmount;
        return feePortion;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public long getAmount() {
        return amount;
    }

    public long getFeeAmount() {
        return feeAmount;
    }

    public long getRefundedAmount() {
        return refundedAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getBankReference() {
        return bankReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
