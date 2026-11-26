package com.vyom.dto.request;

import jakarta.validation.constraints.Size;

public record UpdateWebhookEndpointRequest(
        @Size(max = 2048, message = "Webhook URL must not exceed 2048 characters")
        String url,

        @Size(max = 255, message = "Description must not exceed 255 characters")
        String description
) {}
