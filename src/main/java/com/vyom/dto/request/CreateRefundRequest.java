package com.vyom.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateRefundRequest(
        @Positive(message = "Refund amount must be strictly positive")
        @Digits(integer = 15, fraction = 4, message = "Amount can have at most 4 decimal places")
        BigDecimal amount,

        @NotBlank(message = "Refund reason is required")
        @Size(max = 255, message = "Reason cannot exceed 255 characters")
        String reason
) {}
