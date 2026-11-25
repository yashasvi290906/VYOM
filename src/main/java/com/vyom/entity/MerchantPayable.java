package com.vyom.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "merchant_payables")
public class MerchantPayable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "current_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal currentBalance = BigDecimal.ZERO.setScale(4);

    @Column(name = "pending_settlement_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal pendingSettlementBalance = BigDecimal.ZERO.setScale(4);

    @Column(name = "total_collected", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCollected = BigDecimal.ZERO.setScale(4);

    @Column(name = "total_refunded", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalRefunded = BigDecimal.ZERO.setScale(4);

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public MerchantPayable() {
    }

    public MerchantPayable(UUID merchantId, String currency) {
        this.merchantId = merchantId;
        this.currency = currency != null ? currency.toUpperCase().trim() : "INR";
        this.currentBalance = BigDecimal.ZERO.setScale(4);
        this.pendingSettlementBalance = BigDecimal.ZERO.setScale(4);
        this.totalCollected = BigDecimal.ZERO.setScale(4);
        this.totalRefunded = BigDecimal.ZERO.setScale(4);
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
        normalizeScales();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
        normalizeScales();
    }

    private void normalizeScales() {
        if (this.currentBalance != null) this.currentBalance = this.currentBalance.setScale(4);
        if (this.pendingSettlementBalance != null) this.pendingSettlementBalance = this.pendingSettlementBalance.setScale(4);
        if (this.totalCollected != null) this.totalCollected = this.totalCollected.setScale(4);
        if (this.totalRefunded != null) this.totalRefunded = this.totalRefunded.setScale(4);
    }

    public void credit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit amount must be strictly positive");
        }
        this.currentBalance = this.currentBalance.add(amount).setScale(4);
        this.totalCollected = this.totalCollected.add(amount).setScale(4);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance != null ? currentBalance.setScale(4) : null;
    }

    public BigDecimal getPendingSettlementBalance() {
        return pendingSettlementBalance;
    }

    public void setPendingSettlementBalance(BigDecimal pendingSettlementBalance) {
        this.pendingSettlementBalance = pendingSettlementBalance != null ? pendingSettlementBalance.setScale(4) : null;
    }

    public BigDecimal getTotalCollected() {
        return totalCollected;
    }

    public void setTotalCollected(BigDecimal totalCollected) {
        this.totalCollected = totalCollected != null ? totalCollected.setScale(4) : null;
    }

    public BigDecimal getTotalRefunded() {
        return totalRefunded;
    }

    public void setTotalRefunded(BigDecimal totalRefunded) {
        this.totalRefunded = totalRefunded != null ? totalRefunded.setScale(4) : null;
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
        if (!(o instanceof MerchantPayable that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
