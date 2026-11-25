package com.vyom.dto.request;

import com.vyom.entity.enums.BusinessCategory;
import com.vyom.entity.enums.BusinessType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateMerchantRequest(
        @NotBlank(message = "Business display name is required")
        @Size(min = 2, max = 150, message = "Business display name must be between 2 and 150 characters")
        String businessName,

        @NotBlank(message = "Legal registered business name is required")
        @Size(min = 2, max = 200, message = "Legal business name must be between 2 and 200 characters")
        String legalBusinessName,

        @NotNull(message = "Business entity type is required")
        BusinessType businessType,

        @NotNull(message = "Business category is required")
        BusinessCategory businessCategory,

        @Size(max = 1000, message = "Description must not exceed 1000 characters")
        String description,

        @NotBlank(message = "Registration email is required")
        @Email(message = "Registration email must be a valid email address")
        String registrationEmail,

        @NotBlank(message = "Business phone number is required")
        @Pattern(regexp = "^[0-9+\\-\\s()]{7,20}$", message = "Business phone must be a valid phone number (7-20 characters)")
        String businessPhone,

        @Size(max = 255, message = "Website URL must not exceed 255 characters")
        String website,

        @Size(min = 2, max = 3, message = "Country code must be 2 or 3 characters")
        String country,

        @NotBlank(message = "Street address is required")
        @Size(min = 5, max = 255, message = "Business address must be between 5 and 255 characters")
        String businessAddress,

        @NotBlank(message = "City is required")
        @Size(min = 2, max = 100, message = "City must be between 2 and 100 characters")
        String city,

        @NotBlank(message = "State/Province is required")
        @Size(min = 2, max = 100, message = "State must be between 2 and 100 characters")
        String state,

        @NotBlank(message = "Postal/PIN code is required")
        @Size(min = 3, max = 20, message = "Postal code must be between 3 and 20 characters")
        String postalCode,

        Boolean autoSubmit
) {
}
