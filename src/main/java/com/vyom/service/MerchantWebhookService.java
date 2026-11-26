package com.vyom.service;

import com.vyom.dto.request.RegisterWebhookEndpointRequest;
import com.vyom.dto.request.UpdateWebhookEndpointRequest;
import com.vyom.dto.request.UpdateWebhookSubscriptionsRequest;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.WebhookEndpointCreatedResponse;
import com.vyom.dto.response.WebhookEndpointResponse;
import com.vyom.dto.response.WebhookEventSubscriptionResponse;
import com.vyom.entity.Merchant;
import com.vyom.entity.MerchantWebhookEndpoint;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.WebhookEventType;
import com.vyom.exception.DuplicateWebhookEndpointException;
import com.vyom.exception.InvalidWebhookEventException;
import com.vyom.exception.WebhookEndpointNotFoundException;
import com.vyom.repository.MerchantWebhookEndpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Service managing merchant webhook endpoints, lifecycle operations,
 * cryptographic signing secret rotation, and event subscriptions.
 *
 * Security Invariants:
 * 1. Enforces active merchant membership and role permissions via {@link MerchantSecurityService}.
 * 2. Scopes all database queries by merchantId (tenant isolation, preventing IDOR).
 * 3. Never leaks plaintext signing secrets in ordinary GET queries (only revealed once upon creation/rotation).
 * 4. Strictly validates URLs for HTTPS protocol and SSRF safety before persistence.
 * 5. Coordinates pending delivery pause/resume on endpoint lifecycle state changes.
 */
@Service
public class MerchantWebhookService {

    private static final Logger log = LoggerFactory.getLogger(MerchantWebhookService.class);

    private static final Set<MerchantRole> READ_ROLES = Set.of(
            MerchantRole.MERCHANT_OWNER,
            MerchantRole.MERCHANT_ADMIN,
            MerchantRole.MERCHANT_OPERATOR,
            MerchantRole.MERCHANT_VIEWER
    );

    private static final Set<MerchantRole> WRITE_ROLES = Set.of(
            MerchantRole.MERCHANT_OWNER,
            MerchantRole.MERCHANT_ADMIN
    );

    private static final Set<MerchantRole> OPERATIONAL_ROLES = Set.of(
            MerchantRole.MERCHANT_OWNER,
            MerchantRole.MERCHANT_ADMIN,
            MerchantRole.MERCHANT_OPERATOR
    );

    private final MerchantSecurityService merchantSecurityService;
    private final MerchantWebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryService deliveryService;
    private final WebhookCryptoService cryptoService;
    private final SSRFShieldService ssrfShieldService;
    private final WebhookMetrics webhookMetrics;

    public MerchantWebhookService(
            MerchantSecurityService merchantSecurityService,
            MerchantWebhookEndpointRepository endpointRepository,
            WebhookDeliveryService deliveryService,
            WebhookCryptoService cryptoService,
            SSRFShieldService ssrfShieldService,
            @Autowired(required = false) WebhookMetrics webhookMetrics) {
        this.merchantSecurityService = merchantSecurityService;
        this.endpointRepository = endpointRepository;
        this.deliveryService = deliveryService;
        this.cryptoService = cryptoService;
        this.ssrfShieldService = ssrfShieldService;
        this.webhookMetrics = webhookMetrics;
    }

    /**
     * Registers a new webhook endpoint for the merchant.
     * Generates a 256-bit secure signing secret and returns it in a one-time creation response.
     */
    @Transactional
    public WebhookEndpointCreatedResponse registerEndpoint(
            UUID merchantId, RegisterWebhookEndpointRequest request, UUID callerUserId) {
        Merchant merchant = merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.validateOperationalStatus(merchant);
        merchantSecurityService.requireRole(merchantId, callerUserId, WRITE_ROLES);

        validateUrl(request.url());
        List<String> validatedEvents = validateAndNormalizeEvents(request.subscribedEvents());

        String normalizedUrl = request.url().trim();
        if (endpointRepository.existsByMerchantIdAndUrl(merchantId, normalizedUrl)) {
            throw new DuplicateWebhookEndpointException(
                    "A webhook endpoint with URL '" + normalizedUrl + "' is already registered for merchant " + merchantId);
        }

        UUID endpointId = UUID.randomUUID();
        String rawSecret = cryptoService.generateSigningSecret();
        int activeKeyVersion = cryptoService.getActiveKeyVersion();

        WebhookCryptoService.EncryptedSecret enc = cryptoService.encryptSecret(
                rawSecret, merchantId, endpointId, activeKeyVersion);

        MerchantWebhookEndpoint endpoint = new MerchantWebhookEndpoint(
                merchantId,
                normalizedUrl,
                request.description() != null ? request.description().trim() : null,
                enc.ciphertext(),
                enc.iv(),
                enc.tag(),
                enc.maskedSecret(),
                enc.masterKeyVersion(),
                1,
                validatedEvents
        );
        endpoint.setId(endpointId);
        endpoint.setIsActive(true);

        endpoint = endpointRepository.save(endpoint);
        log.info("Registered webhook endpoint {} for merchant {} by user {}", endpointId, merchantId, callerUserId);

        return WebhookEndpointCreatedResponse.of(endpoint, rawSecret);
    }

    /**
     * Lists paginated webhook endpoints for a merchant.
     */
    @Transactional(readOnly = true)
    public PagedResponse<WebhookEndpointResponse> listEndpoints(
            UUID merchantId, Pageable pageable, UUID callerUserId) {
        Merchant merchant = merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.requireRole(merchantId, callerUserId, READ_ROLES);

        Page<MerchantWebhookEndpoint> page = endpointRepository.findByMerchantId(merchantId, pageable);
        List<WebhookEndpointResponse> dtos = page.getContent().stream()
                .map(WebhookEndpointResponse::from)
                .toList();

        return new PagedResponse<>(
                dtos,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext()
        );
    }

    /**
     * Retrieves details for a specific webhook endpoint with masked secret.
     */
    @Transactional(readOnly = true)
    public WebhookEndpointResponse getEndpoint(UUID merchantId, UUID endpointId, UUID callerUserId) {
        Merchant merchant = merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.requireRole(merchantId, callerUserId, READ_ROLES);

        MerchantWebhookEndpoint endpoint = findEndpointScoped(merchantId, endpointId);
        return WebhookEndpointResponse.from(endpoint);
    }

    /**
     * Updates permitted endpoint configuration (URL and/or description).
     */
    @Transactional
    public WebhookEndpointResponse updateEndpoint(
            UUID merchantId, UUID endpointId, UpdateWebhookEndpointRequest request, UUID callerUserId) {
        Merchant merchant = merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.validateOperationalStatus(merchant);
        merchantSecurityService.requireRole(merchantId, callerUserId, WRITE_ROLES);

        MerchantWebhookEndpoint endpoint = findEndpointScoped(merchantId, endpointId);
        ensureNotArchived(endpoint);

        boolean modified = false;

        if (request.url() != null && !request.url().isBlank()) {
            validateUrl(request.url());
            String newUrl = request.url().trim();
            if (!newUrl.equalsIgnoreCase(endpoint.getUrl())) {
                if (endpointRepository.existsByMerchantIdAndUrl(merchantId, newUrl)) {
                    throw new DuplicateWebhookEndpointException(
                            "Another webhook endpoint with URL '" + newUrl + "' already exists for merchant " + merchantId);
                }
                endpoint.setUrl(newUrl);
                modified = true;
            }
        }

        if (request.description() != null) {
            String newDesc = request.description().trim();
            if (!Objects.equals(newDesc, endpoint.getDescription())) {
                endpoint.setDescription(newDesc);
                modified = true;
            }
        }

        if (modified) {
            endpoint.setUpdatedAt(Instant.now());
            endpoint = endpointRepository.save(endpoint);
            log.info("Updated webhook endpoint {} configuration for merchant {} by user {}", endpointId, merchantId, callerUserId);
        }

        return WebhookEndpointResponse.from(endpoint);
    }

    /**
     * Disables an endpoint and pauses its pending deliveries.
     */
    @Transactional
    public WebhookEndpointResponse disableEndpoint(UUID merchantId, UUID endpointId, UUID callerUserId) {
        Merchant merchant = merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.validateOperationalStatus(merchant);
        merchantSecurityService.requireRole(merchantId, callerUserId, OPERATIONAL_ROLES);

        MerchantWebhookEndpoint endpoint = findEndpointScoped(merchantId, endpointId);
        ensureNotArchived(endpoint);

        if (Boolean.TRUE.equals(endpoint.getIsActive())) {
            endpoint.setIsActive(false);
            endpoint.setUpdatedAt(Instant.now());
            endpoint = endpointRepository.save(endpoint);

            int paused = deliveryService.pauseDeliveriesForEndpoint(endpointId);
            log.info("Disabled webhook endpoint {} for merchant {} (paused {} deliveries) by user {}",
                    endpointId, merchantId, paused, callerUserId);
        }

        return WebhookEndpointResponse.from(endpoint);
    }

    /**
     * Re-enables a disabled endpoint and resumes its paused deliveries.
     */
    @Transactional
    public WebhookEndpointResponse enableEndpoint(UUID merchantId, UUID endpointId, UUID callerUserId) {
        Merchant merchant = merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.validateOperationalStatus(merchant);
        merchantSecurityService.requireRole(merchantId, callerUserId, OPERATIONAL_ROLES);

        MerchantWebhookEndpoint endpoint = findEndpointScoped(merchantId, endpointId);
        ensureNotArchived(endpoint);

        if (!Boolean.TRUE.equals(endpoint.getIsActive())) {
            endpoint.setIsActive(true);
            endpoint.setUpdatedAt(Instant.now());
            endpoint = endpointRepository.save(endpoint);

            int resumed = deliveryService.resumeDeliveriesForEndpoint(endpointId);
            log.info("Re-enabled webhook endpoint {} for merchant {} (resumed {} deliveries) by user {}",
                    endpointId, merchantId, resumed, callerUserId);
        }

        return WebhookEndpointResponse.from(endpoint);
    }

    /**
     * Archives an endpoint permanently, disabling it and preventing future deliveries.
     */
    @Transactional
    public WebhookEndpointResponse archiveEndpoint(UUID merchantId, UUID endpointId, UUID callerUserId) {
        Merchant merchant = merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.validateOperationalStatus(merchant);
        merchantSecurityService.requireRole(merchantId, callerUserId, WRITE_ROLES);

        MerchantWebhookEndpoint endpoint = findEndpointScoped(merchantId, endpointId);
        if (endpoint.getArchivedAt() != null) {
            throw new IllegalStateException("Webhook endpoint " + endpointId + " is already archived");
        }

        endpoint.setArchivedAt(Instant.now());
        endpoint.setIsActive(false);
        endpoint.setUpdatedAt(Instant.now());
        endpoint = endpointRepository.save(endpoint);

        int paused = deliveryService.pauseDeliveriesForEndpoint(endpointId);
        log.info("Archived webhook endpoint {} for merchant {} (paused {} deliveries) by user {}",
                endpointId, merchantId, paused, callerUserId);

        return WebhookEndpointResponse.from(endpoint);
    }

    /**
     * Retrieves the current subscribed events for an endpoint.
     */
    @Transactional(readOnly = true)
    public WebhookEventSubscriptionResponse getSubscriptions(
            UUID merchantId, UUID endpointId, UUID callerUserId) {
        Merchant merchant = merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.requireRole(merchantId, callerUserId, READ_ROLES);

        MerchantWebhookEndpoint endpoint = findEndpointScoped(merchantId, endpointId);
        return new WebhookEventSubscriptionResponse(endpoint.getId(), merchantId, endpoint.getSubscribedEvents());
    }

    /**
     * Replaces the complete set of subscribed events for an endpoint.
     */
    @Transactional
    public WebhookEventSubscriptionResponse updateSubscriptions(
            UUID merchantId, UUID endpointId, UpdateWebhookSubscriptionsRequest request, UUID callerUserId) {
        Merchant merchant = merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.validateOperationalStatus(merchant);
        merchantSecurityService.requireRole(merchantId, callerUserId, WRITE_ROLES);

        MerchantWebhookEndpoint endpoint = findEndpointScoped(merchantId, endpointId);
        ensureNotArchived(endpoint);

        List<String> validatedEvents = validateAndNormalizeEvents(request.subscribedEvents());
        endpoint.setSubscribedEvents(validatedEvents);
        endpoint.setUpdatedAt(Instant.now());
        endpoint = endpointRepository.save(endpoint);

        log.info("Updated subscriptions for endpoint {} of merchant {} to {} by user {}",
                endpointId, merchantId, validatedEvents, callerUserId);

        return new WebhookEventSubscriptionResponse(endpoint.getId(), merchantId, endpoint.getSubscribedEvents());
    }

    /**
     * Adds a single event subscription to an endpoint.
     */
    @Transactional
    public WebhookEventSubscriptionResponse addSubscription(
            UUID merchantId, UUID endpointId, String eventType, UUID callerUserId) {
        Merchant merchant = merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.validateOperationalStatus(merchant);
        merchantSecurityService.requireRole(merchantId, callerUserId, WRITE_ROLES);

        MerchantWebhookEndpoint endpoint = findEndpointScoped(merchantId, endpointId);
        ensureNotArchived(endpoint);

        String normalized = validateEventType(eventType);
        List<String> events = new ArrayList<>(endpoint.getSubscribedEvents());
        if (!events.contains(normalized)) {
            events.add(normalized);
            endpoint.setSubscribedEvents(events);
            endpoint.setUpdatedAt(Instant.now());
            endpoint = endpointRepository.save(endpoint);
            log.info("Added subscription '{}' to endpoint {} for merchant {}", normalized, endpointId, merchantId);
        }

        return new WebhookEventSubscriptionResponse(endpoint.getId(), merchantId, endpoint.getSubscribedEvents());
    }

    /**
     * Removes an event subscription from an endpoint.
     */
    @Transactional
    public WebhookEventSubscriptionResponse removeSubscription(
            UUID merchantId, UUID endpointId, String eventType, UUID callerUserId) {
        Merchant merchant = merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.validateOperationalStatus(merchant);
        merchantSecurityService.requireRole(merchantId, callerUserId, WRITE_ROLES);

        MerchantWebhookEndpoint endpoint = findEndpointScoped(merchantId, endpointId);
        ensureNotArchived(endpoint);

        String normalized = validateEventType(eventType);
        List<String> events = new ArrayList<>(endpoint.getSubscribedEvents());
        if (events.contains(normalized)) {
            if (events.size() <= 1) {
                throw new IllegalArgumentException(
                        "Cannot remove all subscribed events. An endpoint must subscribe to at least one event.");
            }
            events.remove(normalized);
            endpoint.setSubscribedEvents(events);
            endpoint.setUpdatedAt(Instant.now());
            endpoint = endpointRepository.save(endpoint);
            log.info("Removed subscription '{}' from endpoint {} for merchant {}", normalized, endpointId, merchantId);
        }

        return new WebhookEventSubscriptionResponse(endpoint.getId(), merchantId, endpoint.getSubscribedEvents());
    }

    /**
     * Rotates the signing secret for an endpoint, incrementing the secret version.
     * Returns the new raw secret in a one-time response.
     */
    @Transactional
    public WebhookEndpointCreatedResponse rotateSecret(
            UUID merchantId, UUID endpointId, UUID callerUserId) {
        Merchant merchant = merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.validateOperationalStatus(merchant);
        merchantSecurityService.requireRole(merchantId, callerUserId, WRITE_ROLES);

        MerchantWebhookEndpoint endpoint = findEndpointScoped(merchantId, endpointId);
        ensureNotArchived(endpoint);

        String rawSecret = cryptoService.generateSigningSecret();
        int activeKeyVersion = cryptoService.getActiveKeyVersion();
        int nextSecretVersion = endpoint.getSecretVersion() + 1;

        WebhookCryptoService.EncryptedSecret enc = cryptoService.encryptSecret(
                rawSecret, merchantId, endpointId, activeKeyVersion);

        endpoint.setSecretCiphertext(enc.ciphertext());
        endpoint.setSecretIv(enc.iv());
        endpoint.setSecretTag(enc.tag());
        endpoint.setSecretMasked(enc.maskedSecret());
        endpoint.setMasterKeyVersion(enc.masterKeyVersion());
        endpoint.setSecretVersion(nextSecretVersion);
        endpoint.setUpdatedAt(Instant.now());

        endpoint = endpointRepository.save(endpoint);
        log.info("Rotated signing secret for webhook endpoint {} (version {}) for merchant {} by user {}",
                endpointId, nextSecretVersion, merchantId, callerUserId);

        return WebhookEndpointCreatedResponse.of(endpoint, rawSecret);
    }

    private MerchantWebhookEndpoint findEndpointScoped(UUID merchantId, UUID endpointId) {
        return endpointRepository.findByIdAndMerchantId(endpointId, merchantId)
                .orElseThrow(() -> new WebhookEndpointNotFoundException(
                        "Webhook endpoint not found with ID: " + endpointId + " for merchant " + merchantId));
    }

    private void ensureNotArchived(MerchantWebhookEndpoint endpoint) {
        if (endpoint.getArchivedAt() != null) {
            throw new IllegalStateException("Webhook endpoint " + endpoint.getId() + " is archived and cannot be modified");
        }
    }

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Webhook URL cannot be null or blank");
        }
        String trimmed = url.trim();
        if (!trimmed.toLowerCase().startsWith("https://")) {
            throw new IllegalArgumentException("Webhook URL must use the HTTPS protocol (e.g. https://example.com/webhook)");
        }
        try {
            ssrfShieldService.validateUrl(trimmed);
        } catch (Exception ex) {
            if (webhookMetrics != null) {
                webhookMetrics.recordSsrfRejection();
            }
            throw ex;
        }
    }

    private List<String> validateAndNormalizeEvents(List<String> events) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("Subscribed events list cannot be empty");
        }
        List<String> distinctEvents = new ArrayList<>();
        for (String ev : events) {
            String normalized = validateEventType(ev);
            if (!distinctEvents.contains(normalized)) {
                distinctEvents.add(normalized);
            }
        }
        return distinctEvents;
    }

    private String validateEventType(String eventName) {
        if (eventName == null || eventName.isBlank()) {
            throw new InvalidWebhookEventException("Webhook event name cannot be null or blank");
        }
        WebhookEventType type = WebhookEventType.fromEventName(eventName.trim());
        if (type == null) {
            throw new InvalidWebhookEventException(
                    "Unsupported webhook event type '" + eventName + "'. Supported events: " +
                            List.of(WebhookEventType.values()).stream().map(WebhookEventType::getEventName).toList());
        }
        return type.getEventName();
    }
}
