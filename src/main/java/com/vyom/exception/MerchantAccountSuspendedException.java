package com.vyom.exception;

public class MerchantAccountSuspendedException extends RuntimeException {
    public MerchantAccountSuspendedException(String message) {
        super(message);
    }
}
