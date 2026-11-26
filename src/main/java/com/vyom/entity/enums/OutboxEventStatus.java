package com.vyom.entity.enums;

public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    DISPATCHED,
    NO_SUBSCRIBERS,
    DELIVERED,
    FAILED
}
