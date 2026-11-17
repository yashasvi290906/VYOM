package com.vyom.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateWalletRequest(
        @NotBlank(message = "Owner name is required")
        @Size(max = 100, message = "Owner name cannot exceed 100 characters")
        String ownerName,

        @NotBlank(message = "Currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter uppercase ISO currency code")
        String currency
) {}

