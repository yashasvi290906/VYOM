package com.vyom.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(UUID walletId, BigDecimal available, BigDecimal requested) {
        super(String.format("Wallet %s has insufficient balance. Available: %s, Requested: %s",
                walletId, available.toPlainString(), requested.toPlainString()));
    }

    public InsufficientFundsException(String message) {
        super(message);
    }
}

