package com.vyom.dto.request;

import com.vyom.entity.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ExecuteCheckoutPaymentRequest(
        @NotNull(message = "customerWalletId is required")
        UUID customerWalletId,

        PaymentMethod paymentMethod
) {}
