package com.ledgerflow.api.payment;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** Row-locks the payment so concurrent captures/refunds of the same payment run one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id and p.merchantId = :merchantId")
    Optional<Payment> lockByIdAndMerchantId(@Param("id") UUID id, @Param("merchantId") UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") UUID id);

    /** Payments created in [from, to) that the bank confirmed collecting (they carry its reference). */
    @Query("""
            select p from Payment p
             where p.createdAt >= :from and p.createdAt < :to
               and p.bankReference is not null
               and p.status not in (com.ledgerflow.api.payment.PaymentStatus.CREATED,
                                    com.ledgerflow.api.payment.PaymentStatus.PENDING_CONFIRMATION,
                                    com.ledgerflow.api.payment.PaymentStatus.FAILED)
            """)
    List<Payment> findCollectedBetween(@Param("from") Instant from, @Param("to") Instant to);
}
