package com.vyom.dto.response;

import com.vyom.entity.MerchantWebhookEndpoint;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One-time response returned ONLY upon endpoint creation or secret rotation.
 * Contains the plaintext signing secret. Subsequent GET queries return {@link WebhookEndpointResponse}
 * where the secret is masked and never revealed again.
 */
public record WebhookEndpointCreatedResponse(
        UUID id,
        UUID merchantId,
        String url,
        String description,
        String secret,
        String secretMasked,
        Integer secretVersion,
        List<String> subscribedEvents,
        Boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {
    public static WebhookEndpointCreatedResponse of(MerchantWebhookEndpoint endpoint, String plaintextSecret) {
        return new WebhookEndpointCreatedResponse(
                endpoint.getId(),
                endpoint.getMerchantId(),
                endpoint.getUrl(),
                endpoint.getDescription(),
                plaintextSecret,
                endpoint.getSecretMasked(),
                endpoint.getSecretVersion(),
                endpoint.getSubscribedEvents(),
                endpoint.getIsActive(),
                endpoint.getCreatedAt(),
                endpoint.getUpdatedAt()
        );
    }
}
