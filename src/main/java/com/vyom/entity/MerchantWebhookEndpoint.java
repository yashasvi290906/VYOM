package com.vyom.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "merchant_webhook_endpoints")
public class MerchantWebhookEndpoint implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    protected void markNotNew() {
        this.isNew = false;
    }

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "secret_ciphertext", nullable = false, columnDefinition = "TEXT")
    private String secretCiphertext;

    @Column(name = "secret_iv", nullable = false, columnDefinition = "TEXT")
    private String secretIv;

    @Column(name = "secret_tag", nullable = false, columnDefinition = "TEXT")
    private String secretTag;

    @Column(name = "secret_masked", nullable = false, length = 50)
    private String secretMasked;

    @Column(name = "master_key_version", nullable = false)
    private Integer masterKeyVersion = 1;

    @Column(name = "secret_version", nullable = false)
    private Integer secretVersion = 1;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "subscribed_events", columnDefinition = "text[]", nullable = false)
    private List<String> subscribedEvents = new ArrayList<>();

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    public MerchantWebhookEndpoint() {
    }

    public MerchantWebhookEndpoint(UUID merchantId, String url, String description,
                                   String secretCiphertext, String secretIv, String secretTag,
                                   String secretMasked, Integer masterKeyVersion, Integer secretVersion,
                                   List<String> subscribedEvents) {
        this.merchantId = merchantId;
        this.url = url;
        this.description = description;
        this.secretCiphertext = secretCiphertext;
        this.secretIv = secretIv;
        this.secretTag = secretTag;
        this.secretMasked = secretMasked;
        this.masterKeyVersion = masterKeyVersion != null ? masterKeyVersion : 1;
        this.secretVersion = secretVersion != null ? secretVersion : 1;
        this.subscribedEvents = subscribedEvents != null ? new ArrayList<>(subscribedEvents) : new ArrayList<>();
        this.isActive = true;
    }

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.masterKeyVersion == null) {
            this.masterKeyVersion = 1;
        }
        if (this.secretVersion == null) {
            this.secretVersion = 1;
        }
        if (this.isActive == null) {
            this.isActive = true;
        }
        if (this.subscribedEvents == null) {
            this.subscribedEvents = new ArrayList<>();
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

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSecretCiphertext() {
        return secretCiphertext;
    }

    public void setSecretCiphertext(String secretCiphertext) {
        this.secretCiphertext = secretCiphertext;
    }

    public String getSecretIv() {
        return secretIv;
    }

    public void setSecretIv(String secretIv) {
        this.secretIv = secretIv;
    }

    public String getSecretTag() {
        return secretTag;
    }

    public void setSecretTag(String secretTag) {
        this.secretTag = secretTag;
    }

    public String getSecretMasked() {
        return secretMasked;
    }

    public void setSecretMasked(String secretMasked) {
        this.secretMasked = secretMasked;
    }

    public Integer getMasterKeyVersion() {
        return masterKeyVersion;
    }

    public void setMasterKeyVersion(Integer masterKeyVersion) {
        this.masterKeyVersion = masterKeyVersion;
    }

    public Integer getSecretVersion() {
        return secretVersion;
    }

    public void setSecretVersion(Integer secretVersion) {
        this.secretVersion = secretVersion;
    }

    public List<String> getSubscribedEvents() {
        return subscribedEvents;
    }

    public void setSubscribedEvents(List<String> subscribedEvents) {
        this.subscribedEvents = subscribedEvents != null ? new ArrayList<>(subscribedEvents) : new ArrayList<>();
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean active) {
        isActive = active;
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

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MerchantWebhookEndpoint that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
