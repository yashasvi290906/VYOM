package com.vyom.repository;

import com.vyom.entity.PaymentAttempt;
import com.vyom.entity.enums.AttemptStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    List<PaymentAttempt> findByOrderIdOrderBySequenceNumberAsc(UUID orderId);

    Optional<PaymentAttempt> findByAttemptReference(String attemptReference);

    Page<PaymentAttempt> findByCustomerUserId(UUID customerUserId, Pageable pageable);

    Optional<PaymentAttempt> findTopByOrderIdOrderBySequenceNumberDesc(UUID orderId);

    boolean existsByOrderIdAndStatus(UUID orderId, AttemptStatus status);

    Optional<PaymentAttempt> findByTransactionId(UUID transactionId);
}
