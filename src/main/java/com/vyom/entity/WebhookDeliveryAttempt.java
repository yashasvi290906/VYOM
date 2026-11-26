package com.vyom.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable Webhook Delivery Attempt Reservation.
 *
 * Represents the intent/reservation of an HTTP delivery attempt.
 * Once inserted, this record is NEVER updated or deleted.
 * Audited outcomes are recorded separately in {@link WebhookAttemptOutcome}.
 */
@Entity
@Table(name = "webhook_delivery_attempts")
public class WebhookDeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_id", nullable = false, updatable = false)
    private UUID deliveryId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private Integer attemptNumber;

    @Column(name = "endpoint_url", nullable = false, updatable = false, length = 2048)
    private String endpointUrl;

    @Column(name = "secret_version", nullable = false, updatable = false)
    private Integer secretVersion;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    public WebhookDeliveryAttempt() {
    }

    public WebhookDeliveryAttempt(UUID deliveryId, Integer attemptNumber, String endpointUrl, Integer secretVersion) {
        this.deliveryId = deliveryId;
        this.attemptNumber = attemptNumber;
        this.endpointUrl = endpointUrl;
        this.secretVersion = secretVersion;
        this.attemptedAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.attemptedAt == null) {
            this.attemptedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public void setDeliveryId(UUID deliveryId) {
        this.deliveryId = deliveryId;
    }

    public Integer getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(Integer attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public Integer getSecretVersion() {
        return secretVersion;
    }

    public void setSecretVersion(Integer secretVersion) {
        this.secretVersion = secretVersion;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public void setAttemptedAt(Instant attemptedAt) {
        this.attemptedAt = attemptedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebhookDeliveryAttempt that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
