package com.vyom.dto.request;

import com.vyom.entity.enums.DisputeStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResolveDisputeRequest(
        @NotNull(message = "Resolution status is required")
        DisputeStatus status,

        @Size(max = 1000, message = "Resolution notes cannot exceed 1000 characters")
        String resolutionNotes
) {}
