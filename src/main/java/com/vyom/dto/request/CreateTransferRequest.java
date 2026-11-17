package com.vyom.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransferRequest(
        @NotNull(message = "Source wallet ID is required")
        UUID sourceWalletId,

        @NotNull(message = "Destination wallet ID is required")
        UUID destinationWalletId,

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be strictly positive")
        @Digits(integer = 15, fraction = 4, message = "Amount can have at most 4 decimal places")
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter uppercase ISO currency code")
        String currency,

        @Size(max = 255, message = "Description cannot exceed 255 characters")
        String description
) {}

