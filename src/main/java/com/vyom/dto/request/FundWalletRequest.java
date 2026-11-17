package com.vyom.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record FundWalletRequest(
        @NotNull(message = "Amount is required")
        @Positive(message = "Funding amount must be strictly positive")
        @Digits(integer = 15, fraction = 4, message = "Amount can have at most 4 decimal places")
        BigDecimal amount,

        @Size(max = 255, message = "Description cannot exceed 255 characters")
        String description
) {}

