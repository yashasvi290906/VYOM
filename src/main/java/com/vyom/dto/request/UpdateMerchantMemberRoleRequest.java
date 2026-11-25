package com.vyom.dto.request;

import com.vyom.entity.enums.MerchantRole;
import jakarta.validation.constraints.NotNull;

public record UpdateMerchantMemberRoleRequest(
        @NotNull(message = "Merchant role is required")
        MerchantRole merchantRole
) {
}
