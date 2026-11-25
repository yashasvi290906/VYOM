package com.vyom.repository;

import com.vyom.entity.Dispute;
import com.vyom.entity.enums.DisputeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

    Optional<Dispute> findByDisputeReference(String disputeReference);

    List<Dispute> findByTransactionId(UUID transactionId);

    Page<Dispute> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    boolean existsByTransactionIdAndStatusIn(UUID transactionId, Collection<DisputeStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Dispute d WHERE d.id = :id")
    Optional<Dispute> findByIdForUpdate(@Param("id") UUID id);
}
