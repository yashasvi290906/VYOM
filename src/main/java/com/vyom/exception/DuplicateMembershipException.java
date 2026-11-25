package com.vyom.exception;

public class DuplicateMembershipException extends RuntimeException {
    public DuplicateMembershipException(String message) {
        super(message);
    }
}
