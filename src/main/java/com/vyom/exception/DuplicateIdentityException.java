package com.vyom.exception;

public class DuplicateIdentityException extends RuntimeException {
    public DuplicateIdentityException(String message) {
        super(message);
    }
}
