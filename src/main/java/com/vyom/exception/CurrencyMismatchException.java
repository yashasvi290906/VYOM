package com.vyom.exception;

import java.util.UUID;

public class CurrencyMismatchException extends RuntimeException {
    public CurrencyMismatchException(UUID walletId, String expectedCurrency, String actualCurrency) {
        super(String.format("Currency mismatch for wallet %s. Expected: %s, Actual: %s",
                walletId, expectedCurrency, actualCurrency));
    }

    public CurrencyMismatchException(String message) {
        super(message);
    }
}

