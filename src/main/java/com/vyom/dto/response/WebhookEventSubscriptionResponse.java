package com.vyom.dto.response;

import java.util.List;
import java.util.UUID;

public record WebhookEventSubscriptionResponse(
        UUID endpointId,
        UUID merchantId,
        List<String> subscribedEvents
) {}
