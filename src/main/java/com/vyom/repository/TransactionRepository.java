package com.vyom.repository;

import com.vyom.entity.Transaction;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByReferenceNumber(String referenceNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") UUID id);

    List<Transaction> findByRelatedTransactionId(UUID relatedTransactionId);

    @Query("""
        SELECT t FROM Transaction t
        WHERE (t.initiatorUserId = :userId OR t.recipientUserId = :userId)
          AND (:transactionType IS NULL OR t.transactionType = :transactionType)
          AND (:status IS NULL OR t.status = :status)
          AND (:startDate IS NULL OR t.createdAt >= :startDate)
          AND (:endDate IS NULL OR t.createdAt <= :endDate)
          AND (:minAmount IS NULL OR t.amount >= :minAmount)
          AND (:maxAmount IS NULL OR t.amount <= :maxAmount)
          AND (cast(:refFilter as string) IS NULL OR LOWER(t.referenceNumber) LIKE cast(:refFilter as string))
        ORDER BY t.createdAt DESC, t.id DESC
    """)
    Page<Transaction> findUserTransactionsWithFilters(
            @Param("userId") UUID userId,
            @Param("transactionType") TransactionType transactionType,
            @Param("status") TransactionStatus status,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate,
            @Param("minAmount") BigDecimal minAmount,
            @Param("maxAmount") BigDecimal maxAmount,
            @Param("refFilter") String refFilter,
            Pageable pageable
    );

    @Query("""
        SELECT t FROM Transaction t
        WHERE (t.sourceWalletId = :walletId OR t.destinationWalletId = :walletId)
        ORDER BY t.createdAt DESC, t.id DESC
    """)
    Page<Transaction> findByWalletIdOrderByCreatedAtDesc(@Param("walletId") UUID walletId, Pageable pageable);
}
