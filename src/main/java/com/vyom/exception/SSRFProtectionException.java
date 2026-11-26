package com.vyom.exception;

public class SSRFProtectionException extends RuntimeException {
    public SSRFProtectionException(String message) {
        super(message);
    }
}
