package com.vyom.entity.enums;

public enum OrderStatus {
    CREATED,
    PENDING,
    PAID,
    FAILED,
    CANCELLED,
    EXPIRED;

    public boolean isTerminal() {
        return this == PAID || this == FAILED || this == CANCELLED || this == EXPIRED;
    }
}
