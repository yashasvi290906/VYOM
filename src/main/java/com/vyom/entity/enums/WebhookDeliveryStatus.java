package com.vyom.entity.enums;

public enum WebhookDeliveryStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    PAUSED,
    DELIVERED,
    DEAD_LETTER
}
