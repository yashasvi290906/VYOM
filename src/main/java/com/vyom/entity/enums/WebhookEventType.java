package com.vyom.entity.enums;

public enum WebhookEventType {
    ORDER_PAID("order.paid"),
    ORDER_CANCELLED("order.cancelled"),
    ORDER_FAILED("order.failed"),
    ORDER_REFUNDED("order.refunded"),
    ORDER_CREATED("order.created"),
    ORDER_EXPIRED("order.expired");

    private final String eventName;

    WebhookEventType(String eventName) {
        this.eventName = eventName;
    }

    public String getEventName() {
        return eventName;
    }

    public static WebhookEventType fromEventName(String eventName) {
        if (eventName == null) {
            return null;
        }
        for (WebhookEventType type : values()) {
            if (type.eventName.equalsIgnoreCase(eventName) || type.name().equalsIgnoreCase(eventName)) {
                return type;
            }
        }
        return null;
    }
}
