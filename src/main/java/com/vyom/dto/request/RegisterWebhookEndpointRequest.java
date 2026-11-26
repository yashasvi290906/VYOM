package com.vyom.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RegisterWebhookEndpointRequest(
        @NotBlank(message = "Webhook URL is required")
        @Size(max = 2048, message = "Webhook URL must not exceed 2048 characters")
        String url,

        @Size(max = 255, message = "Description must not exceed 255 characters")
        String description,

        @NotEmpty(message = "At least one subscribed event must be specified")
        @Size(max = 50, message = "Cannot subscribe to more than 50 events")
        List<String> subscribedEvents
) {}
