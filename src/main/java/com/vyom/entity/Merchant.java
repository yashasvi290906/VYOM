package com.vyom.entity;

import com.vyom.entity.enums.AccountStatus;
import com.vyom.entity.enums.BusinessCategory;
import com.vyom.entity.enums.BusinessType;
import com.vyom.entity.enums.OnboardingStatus;
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
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_code", nullable = false, unique = true, length = 32)
    private String merchantCode;

    @Column(name = "business_name", nullable = false, length = 150)
    private String businessName;

    @Column(name = "legal_business_name", nullable = false, length = 200)
    private String legalBusinessName;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false, length = 50)
    private BusinessType businessType;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_category", nullable = false, length = 50)
    private BusinessCategory businessCategory;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "registration_email", nullable = false, length = 255)
    private String registrationEmail;

    @Column(name = "business_phone", nullable = false, length = 20)
    private String businessPhone;

    @Column(name = "website", length = 255)
    private String website;

    @Column(name = "country", nullable = false, length = 3)
    private String country = "IND";

    @Column(name = "business_address", nullable = false, length = 255)
    private String businessAddress;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "state", nullable = false, length = 100)
    private String state;

    @Column(name = "postal_code", nullable = false, length = 20)
    private String postalCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "onboarding_status", nullable = false, length = 30)
    private OnboardingStatus onboardingStatus = OnboardingStatus.SUBMITTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 30)
    private AccountStatus accountStatus = AccountStatus.INACTIVE;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Merchant() {
    }

    public Merchant(String merchantCode,
                    String businessName,
                    String legalBusinessName,
                    BusinessType businessType,
                    BusinessCategory businessCategory,
                    String description,
                    String registrationEmail,
                    String businessPhone,
                    String website,
                    String country,
                    String businessAddress,
                    String city,
                    String state,
                    String postalCode,
                    UUID createdBy) {
        this.merchantCode = merchantCode;
        this.businessName = businessName;
        this.legalBusinessName = legalBusinessName;
        this.businessType = businessType;
        this.businessCategory = businessCategory;
        this.description = description;
        this.registrationEmail = registrationEmail != null ? registrationEmail.toLowerCase().trim() : null;
        this.businessPhone = businessPhone;
        this.website = website;
        this.country = country != null ? country.toUpperCase().trim() : "IND";
        this.businessAddress = businessAddress;
        this.city = city;
        this.state = state;
        this.postalCode = postalCode;
        this.createdBy = createdBy;
        this.onboardingStatus = OnboardingStatus.SUBMITTED;
        this.accountStatus = AccountStatus.INACTIVE;
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
        if (this.country == null || this.country.isBlank()) {
            this.country = "IND";
        }
        if (this.onboardingStatus == null) {
            this.onboardingStatus = OnboardingStatus.SUBMITTED;
        }
        if (this.accountStatus == null) {
            this.accountStatus = AccountStatus.INACTIVE;
        }
        if (this.version == null) {
            this.version = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getMerchantCode() {
        return merchantCode;
    }

    public void setMerchantCode(String merchantCode) {
        this.merchantCode = merchantCode;
    }

    public String getBusinessName() {
        return businessName;
    }

    public void setBusinessName(String businessName) {
        this.businessName = businessName;
    }

    public String getLegalBusinessName() {
        return legalBusinessName;
    }

    public void setLegalBusinessName(String legalBusinessName) {
        this.legalBusinessName = legalBusinessName;
    }

    public BusinessType getBusinessType() {
        return businessType;
    }

    public void setBusinessType(BusinessType businessType) {
        this.businessType = businessType;
    }

    public BusinessCategory getBusinessCategory() {
        return businessCategory;
    }

    public void setBusinessCategory(BusinessCategory businessCategory) {
        this.businessCategory = businessCategory;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRegistrationEmail() {
        return registrationEmail;
    }

    public void setRegistrationEmail(String registrationEmail) {
        this.registrationEmail = registrationEmail != null ? registrationEmail.toLowerCase().trim() : null;
    }

    public String getBusinessPhone() {
        return businessPhone;
    }

    public void setBusinessPhone(String businessPhone) {
        this.businessPhone = businessPhone;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country != null ? country.toUpperCase().trim() : "IND";
    }

    public String getBusinessAddress() {
        return businessAddress;
    }

    public void setBusinessAddress(String businessAddress) {
        this.businessAddress = businessAddress;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public OnboardingStatus getOnboardingStatus() {
        return onboardingStatus;
    }

    public void setOnboardingStatus(OnboardingStatus onboardingStatus) {
        this.onboardingStatus = onboardingStatus;
    }

    public AccountStatus getAccountStatus() {
        return accountStatus;
    }

    public void setAccountStatus(AccountStatus accountStatus) {
        this.accountStatus = accountStatus;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(UUID approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
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
        if (o == null || getClass() != o.getClass()) return false;
        Merchant merchant = (Merchant) o;
        return Objects.equals(id, merchant.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
