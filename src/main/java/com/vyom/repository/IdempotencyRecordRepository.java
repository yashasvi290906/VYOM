package com.vyom.repository;

import com.vyom.entity.IdempotencyRecord;
import com.vyom.entity.enums.IdempotencyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE IdempotencyRecord r " +
            "SET r.ownerToken = :newOwnerToken, r.updatedAt = :now, r.version = r.version + 1 " +
            "WHERE r.idempotencyKey = :key " +
            "  AND r.status = com.vyom.entity.enums.IdempotencyStatus.PROCESSING " +
            "  AND r.updatedAt < :staleThreshold")
    int reclaimStaleReservation(@Param("key") String key,
                                @Param("newOwnerToken") UUID newOwnerToken,
                                @Param("now") Instant now,
                                @Param("staleThreshold") Instant staleThreshold);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE IdempotencyRecord r " +
            "SET r.status = :status, " +
            "    r.transferId = :transferId, " +
            "    r.fundingId = :fundingId, " +
            "    r.responseStatusCode = :statusCode, " +
            "    r.responseBody = :responseBody, " +
            "    r.updatedAt = :now, " +
            "    r.version = r.version + 1 " +
            "WHERE r.idempotencyKey = :key " +
            "  AND r.ownerToken = :ownerToken " +
            "  AND r.status = com.vyom.entity.enums.IdempotencyStatus.PROCESSING")
    int completeWithFencing(@Param("key") String key,
                            @Param("ownerToken") UUID ownerToken,
                            @Param("status") IdempotencyStatus status,
                            @Param("transferId") UUID transferId,
                            @Param("fundingId") UUID fundingId,
                            @Param("statusCode") int statusCode,
                            @Param("responseBody") String responseBody,
                            @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE IdempotencyRecord r " +
            "SET r.status = com.vyom.entity.enums.IdempotencyStatus.FAILED, " +
            "    r.updatedAt = :now, " +
            "    r.version = r.version + 1 " +
            "WHERE r.idempotencyKey = :key " +
            "  AND r.ownerToken = :ownerToken " +
            "  AND r.status = com.vyom.entity.enums.IdempotencyStatus.PROCESSING")
    int markFailedWithFencing(@Param("key") String key,
                              @Param("ownerToken") UUID ownerToken,
                              @Param("now") Instant now);
}

