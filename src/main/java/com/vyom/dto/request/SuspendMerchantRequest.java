package com.vyom.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SuspendMerchantRequest(
        @NotBlank(message = "Suspension reason is required")
        @Size(min = 5, max = 1000, message = "Suspension reason must be between 5 and 1000 characters")
        String reason
) {
}
