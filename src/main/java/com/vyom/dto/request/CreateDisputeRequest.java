package com.vyom.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDisputeRequest(
        @NotBlank(message = "Reason code is required")
        @Size(max = 50, message = "Reason code cannot exceed 50 characters")
        String reasonCode,

        @NotBlank(message = "Description is required")
        @Size(max = 1000, message = "Description cannot exceed 1000 characters")
        String description
) {}
