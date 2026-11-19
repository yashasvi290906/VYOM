package com.vyom.exception;

import java.util.UUID;

public class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException(UUID id) {
        super(String.format("Transaction not found: %s", id));
    }

    public TransactionNotFoundException(String reference) {
        super(String.format("Transaction not found with reference: %s", reference));
    }
}
