package com.vyom.dto.response;

import com.vyom.entity.Dispute;
import com.vyom.entity.enums.DisputeStatus;

import java.time.Instant;
import java.util.UUID;

public record DisputeResponse(
        UUID id,
        String disputeReference,
        UUID transactionId,
        UUID userId,
        String reasonCode,
        DisputeStatus status,
        String description,
        String resolutionNotes,
        Instant createdAt,
        Instant resolvedAt
) {
    public static DisputeResponse from(Dispute d) {
        return new DisputeResponse(
                d.getId(),
                d.getDisputeReference(),
                d.getTransactionId(),
                d.getUserId(),
                d.getReasonCode(),
                d.getStatus(),
                d.getDescription(),
                d.getResolutionNotes(),
                d.getCreatedAt(),
                d.getResolvedAt()
        );
    }
}
