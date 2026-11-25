package com.vyom.exception;

public class InsufficientMerchantPrivilegeException extends RuntimeException {
    public InsufficientMerchantPrivilegeException(String message) {
        super(message);
    }
}
