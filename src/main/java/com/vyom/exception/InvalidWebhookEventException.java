package com.vyom.exception;

public class InvalidWebhookEventException extends RuntimeException {
    public InvalidWebhookEventException(String message) {
        super(message);
    }
}
