package com.vyom.entity;

import com.vyom.entity.enums.AccountType;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType = AccountType.CUSTOMER;

    @Column(name = "owner_name", nullable = false, length = 100)
    private String ownerName;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static final UUID DEFAULT_LEGACY_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    public Wallet() {
    }

    public Wallet(String ownerName, String currency) {
        this(DEFAULT_LEGACY_USER_ID, ownerName, currency, BigDecimal.ZERO.setScale(4), AccountType.CUSTOMER);
    }

    public Wallet(String ownerName, String currency, BigDecimal balance) {
        this(DEFAULT_LEGACY_USER_ID, ownerName, currency, balance, AccountType.CUSTOMER);
    }

    public Wallet(UUID userId, String ownerName, String currency, BigDecimal balance) {
        this(userId, ownerName, currency, balance, AccountType.CUSTOMER);
    }

    public Wallet(UUID userId, String ownerName, String currency, BigDecimal balance, AccountType accountType) {
        this.userId = userId;
        this.ownerName = ownerName;
        this.currency = currency;
        this.balance = balance != null ? balance.setScale(4) : BigDecimal.ZERO.setScale(4);
        this.accountType = accountType != null ? accountType : AccountType.CUSTOMER;
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
        if (this.balance == null) {
            this.balance = BigDecimal.ZERO.setScale(4);
        }
        if (this.accountType == null) {
            this.accountType = AccountType.CUSTOMER;
        }
        if (this.accountType == AccountType.CUSTOMER && this.userId == null) {
            this.userId = DEFAULT_LEGACY_USER_ID;
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

    public AccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(AccountType accountType) {
        this.accountType = accountType;
    }

    public boolean isSystemAccount() {
        return this.accountType == AccountType.SYSTEM_SETTLEMENT;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance != null ? balance.setScale(4) : BigDecimal.ZERO.setScale(4);
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
        if (!(o instanceof Wallet wallet)) return false;
        return Objects.equals(id, wallet.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
