package com.vyom.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

public record CreatePaymentOrderRequest(
        @NotBlank(message = "merchantOrderId is required")
        @Size(max = 64, message = "merchantOrderId must not exceed 64 characters")
        String merchantOrderId,

        @Size(max = 100, message = "customerReference must not exceed 100 characters")
        String customerReference,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0100", message = "amount must be at least 0.01")
        @DecimalMax(value = "10000000.0000", message = "amount must not exceed 10,000,000.00")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter uppercase ISO code")
        String currency,

        @Size(max = 255, message = "description must not exceed 255 characters")
        String description,

        Integer expiresInMinutes,

        Map<String, Object> metadata
) {}
