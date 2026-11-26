package com.vyom.exception;

public class WebhookCryptoException extends RuntimeException {
    public WebhookCryptoException(String message) {
        super(message);
    }

    public WebhookCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
