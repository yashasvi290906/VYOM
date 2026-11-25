package com.vyom.exception;

public class DuplicateMerchantOrderIdException extends RuntimeException {
    public DuplicateMerchantOrderIdException(String message) {
        super(message);
    }
}
