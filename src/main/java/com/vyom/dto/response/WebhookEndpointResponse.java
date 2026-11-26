package com.vyom.dto.response;

import com.vyom.entity.MerchantWebhookEndpoint;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WebhookEndpointResponse(
        UUID id,
        UUID merchantId,
        String url,
        String description,
        String secretMasked,
        Integer secretVersion,
        List<String> subscribedEvents,
        Boolean isActive,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt
) {
    public static WebhookEndpointResponse from(MerchantWebhookEndpoint endpoint) {
        return new WebhookEndpointResponse(
                endpoint.getId(),
                endpoint.getMerchantId(),
                endpoint.getUrl(),
                endpoint.getDescription(),
                endpoint.getSecretMasked(),
                endpoint.getSecretVersion(),
                endpoint.getSubscribedEvents(),
                endpoint.getIsActive(),
                endpoint.getCreatedAt(),
                endpoint.getUpdatedAt(),
                endpoint.getArchivedAt()
        );
    }
}
