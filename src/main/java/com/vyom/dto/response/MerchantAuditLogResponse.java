package com.vyom.dto.response;

import com.vyom.entity.MerchantAuditLog;
import com.vyom.entity.enums.MerchantAuditEventType;

import java.time.Instant;
import java.util.UUID;

public record MerchantAuditLogResponse(
        UUID id,
        UUID merchantId,
        UUID actorUserId,
        String actorName,
        MerchantAuditEventType eventType,
        String description,
        String metadata,
        Instant createdAt
) {
    public static MerchantAuditLogResponse from(MerchantAuditLog log, String actorName) {
        return new MerchantAuditLogResponse(
                log.getId(),
                log.getMerchantId(),
                log.getActorUserId(),
                actorName,
                log.getEventType(),
                log.getDescription(),
                log.getMetadata(),
                log.getCreatedAt()
        );
    }
}
