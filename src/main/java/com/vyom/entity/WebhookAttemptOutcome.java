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
 * Immutable Webhook Attempt Outcome.
 *
 * Represents the final audited outcome of an HTTP attempt reservation.
 * Strictly append-only: At most one outcome exists per attempt reservation (enforced by DB unique constraint).
 * Neither vyom_app nor workers can update or delete this record.
 */
@Entity
@Table(name = "webhook_attempt_outcomes")
public class WebhookAttemptOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "attempt_id", nullable = false, unique = true, updatable = false)
    private UUID attemptId;

    @Column(name = "response_status", updatable = false)
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT", updatable = false)
    private String responseBody;

    @Column(name = "latency_ms", updatable = false)
    private Integer latencyMs;

    @Column(name = "error_message", columnDefinition = "TEXT", updatable = false)
    private String errorMessage;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;

    public WebhookAttemptOutcome() {
    }

    public WebhookAttemptOutcome(UUID attemptId, Integer responseStatus, String responseBody,
                                 Integer latencyMs, String errorMessage) {
        this.attemptId = attemptId;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.latencyMs = latencyMs;
        this.errorMessage = errorMessage;
        this.completedAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.completedAt == null) {
            this.completedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(UUID attemptId) {
        this.attemptId = attemptId;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebhookAttemptOutcome that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
