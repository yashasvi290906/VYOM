package com.vyom.entity.enums;

public enum AttemptStatus {
    INITIATED,
    PROCESSING,
    SUCCESS,
    FAILED,
    ABANDONED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == ABANDONED;
    }
}
