package com.vyom.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateWebhookSubscriptionsRequest(
        @NotEmpty(message = "At least one subscribed event must be specified")
        @Size(max = 50, message = "Cannot subscribe to more than 50 events")
        List<String> subscribedEvents
) {}
