package com.vyom.dto.request;

import com.vyom.entity.enums.MerchantRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddMerchantMemberRequest(
        @NotBlank(message = "User email is required")
        @Email(message = "Must be a valid email address")
        String email,

        @NotNull(message = "Merchant role is required")
        MerchantRole merchantRole
) {
}
