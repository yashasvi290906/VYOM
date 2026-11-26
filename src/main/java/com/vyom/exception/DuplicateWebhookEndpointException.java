package com.vyom.exception;

public class DuplicateWebhookEndpointException extends RuntimeException {
    public DuplicateWebhookEndpointException(String message) {
        super(message);
    }
}
