package com.vyom.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class OverRefundException extends RuntimeException {
    public OverRefundException(UUID transactionId, BigDecimal requested, BigDecimal remaining) {
        super(String.format("Requested refund amount (%s) exceeds remaining refundable amount (%s) on transaction %s",
                requested, remaining, transactionId));
    }

    public OverRefundException(String message) {
        super(message);
    }
}
