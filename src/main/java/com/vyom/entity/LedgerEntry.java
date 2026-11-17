package com.vyom.entity;

import com.vyom.entity.enums.EntryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false)
    private UUID id;

    @Column(name = "transfer_id", updatable = false)
    private UUID transferId;

    @Column(name = "funding_id", updatable = false)
    private UUID fundingId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10, updatable = false)
    private EntryType entryType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LedgerEntry() {
    }

    public static LedgerEntry forTransfer(UUID transferId, UUID walletId, EntryType entryType, BigDecimal amount, BigDecimal balanceAfter) {
        LedgerEntry entry = new LedgerEntry();
        entry.transferId = transferId;
        entry.walletId = walletId;
        entry.entryType = entryType;
        entry.amount = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4);
        entry.balanceAfter = balanceAfter != null ? balanceAfter.setScale(4) : BigDecimal.ZERO.setScale(4);
        return entry;
    }

    public static LedgerEntry forFunding(UUID fundingId, UUID walletId, BigDecimal amount, BigDecimal balanceAfter) {
        LedgerEntry entry = new LedgerEntry();
        entry.fundingId = fundingId;
        entry.walletId = walletId;
        entry.entryType = EntryType.CREDIT;
        entry.amount = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4);
        entry.balanceAfter = balanceAfter != null ? balanceAfter.setScale(4) : BigDecimal.ZERO.setScale(4);
        return entry;
    }

    public LedgerEntry(UUID transferId, UUID walletId, EntryType entryType, BigDecimal amount, BigDecimal balanceAfter) {
        this.transferId = transferId;
        this.walletId = walletId;
        this.entryType = entryType;
        this.amount = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4);
        this.balanceAfter = balanceAfter != null ? balanceAfter.setScale(4) : BigDecimal.ZERO.setScale(4);
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.amount != null) {
            this.amount = this.amount.setScale(4);
        }
        if (this.balanceAfter != null) {
            this.balanceAfter = this.balanceAfter.setScale(4);
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public UUID getWalletId() {
        return walletId;
    }

    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public void setEntryType(EntryType entryType) {
        this.entryType = entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount != null ? amount.setScale(4) : null;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter != null ? balanceAfter.setScale(4) : null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LedgerEntry that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

