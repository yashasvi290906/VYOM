package com.vyom.exception;

public class InvalidMerchantTransitionException extends RuntimeException {
    public InvalidMerchantTransitionException(String message) {
        super(message);
    }
}
