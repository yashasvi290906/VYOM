package com.vyom.entity;

import com.vyom.entity.enums.IdempotencyStatus;
import com.vyom.entity.enums.OperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 20)
    private OperationType operationType = OperationType.TRANSFER;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Column(name = "funding_id")
    private UUID fundingId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "response_status_code")
    private Integer responseStatusCode;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "owner_token", nullable = false)
    private UUID ownerToken;

    @Column(name = "version", nullable = false)
    private Long version = 1L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public IdempotencyRecord() {
    }

    public IdempotencyRecord(String idempotencyKey, String requestFingerprint, IdempotencyStatus status) {
        this(UUID.fromString("00000000-0000-0000-0000-000000000099"), idempotencyKey, OperationType.TRANSFER, requestFingerprint, status, UUID.randomUUID());
    }

    public IdempotencyRecord(String idempotencyKey, OperationType operationType, String requestFingerprint, IdempotencyStatus status) {
        this(UUID.fromString("00000000-0000-0000-0000-000000000099"), idempotencyKey, operationType, requestFingerprint, status, UUID.randomUUID());
    }

    public IdempotencyRecord(String idempotencyKey, OperationType operationType, String requestFingerprint, IdempotencyStatus status, UUID ownerToken) {
        this(UUID.fromString("00000000-0000-0000-0000-000000000099"), idempotencyKey, operationType, requestFingerprint, status, ownerToken);
    }

    public IdempotencyRecord(UUID userId, String idempotencyKey, String requestFingerprint, IdempotencyStatus status) {
        this(userId, idempotencyKey, OperationType.TRANSFER, requestFingerprint, status, UUID.randomUUID());
    }

    public IdempotencyRecord(UUID userId, String idempotencyKey, OperationType operationType, String requestFingerprint, IdempotencyStatus status) {
        this(userId, idempotencyKey, operationType, requestFingerprint, status, UUID.randomUUID());
    }

    public IdempotencyRecord(UUID userId, String idempotencyKey, OperationType operationType, String requestFingerprint, IdempotencyStatus status, UUID ownerToken) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.operationType = operationType;
        this.requestFingerprint = requestFingerprint;
        this.status = status;
        this.ownerToken = ownerToken != null ? ownerToken : UUID.randomUUID();
        this.version = 1L;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.operationType == null) {
            this.operationType = OperationType.TRANSFER;
        }
        if (this.ownerToken == null) {
            this.ownerToken = UUID.randomUUID();
        }
        if (this.version == null) {
            this.version = 1L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public OperationType getOperationType() {
        return operationType;
    }

    public void setOperationType(OperationType operationType) {
        this.operationType = operationType;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public void setTransferId(UUID transferId) {
        this.transferId = transferId;
    }

    public UUID getFundingId() {
        return fundingId;
    }

    public void setFundingId(UUID fundingId) {
        this.fundingId = fundingId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public void setStatus(IdempotencyStatus status) {
        this.status = status;
    }

    public Integer getResponseStatusCode() {
        return responseStatusCode;
    }

    public void setResponseStatusCode(Integer responseStatusCode) {
        this.responseStatusCode = responseStatusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public UUID getOwnerToken() {
        return ownerToken;
    }

    public void setOwnerToken(UUID ownerToken) {
        this.ownerToken = ownerToken;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IdempotencyRecord that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
