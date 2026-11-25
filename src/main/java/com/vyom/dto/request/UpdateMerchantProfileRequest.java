package com.vyom.dto.request;

import com.vyom.entity.enums.BusinessCategory;
import com.vyom.entity.enums.BusinessType;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateMerchantProfileRequest(
        @Size(min = 2, max = 150, message = "Business name must be between 2 and 150 characters")
        String businessName,

        @Size(max = 1000, message = "Description must not exceed 1000 characters")
        String description,

        @Pattern(regexp = "^[0-9+\\-\\s()]{7,20}$", message = "Business phone must be a valid phone number (7-20 characters)")
        String businessPhone,

        @Size(max = 255, message = "Website URL must not exceed 255 characters")
        String website,

        @Size(min = 5, max = 255, message = "Business address must be between 5 and 255 characters")
        String businessAddress,

        @Size(min = 2, max = 100, message = "City must be between 2 and 100 characters")
        String city,

        @Size(min = 2, max = 100, message = "State must be between 2 and 100 characters")
        String state,

        @Size(min = 3, max = 20, message = "Postal code must be between 3 and 20 characters")
        String postalCode,

        // Optional fields editable only while in DRAFT status
        @Size(min = 2, max = 200, message = "Legal business name must be between 2 and 200 characters")
        String legalBusinessName,

        BusinessType businessType,

        BusinessCategory businessCategory
) {
}
