package com.vyom.dto.request;

import jakarta.validation.constraints.Size;

public record CancelPaymentOrderRequest(
        @Size(max = 255, message = "Cancellation reason must not exceed 255 characters")
        String reason
) {}
