package com.vyom.controller;

import com.vyom.dto.request.RegisterWebhookEndpointRequest;
import com.vyom.dto.request.UpdateWebhookEndpointRequest;
import com.vyom.dto.request.UpdateWebhookSubscriptionsRequest;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.WebhookEndpointCreatedResponse;
import com.vyom.dto.response.WebhookEndpointResponse;
import com.vyom.dto.response.WebhookEventSubscriptionResponse;
import com.vyom.security.SecurityUtils;
import com.vyom.service.MerchantWebhookService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Merchant Webhook Management REST API Controller.
 *
 * Exposes endpoints for managing merchant webhook configurations,
 * lifecycle operational states, subscriptions, and secret rotation.
 * All requests enforce tenant isolation and merchant RBAC on the server.
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/webhooks")
public class MerchantWebhookController {

    private final MerchantWebhookService webhookService;

    public MerchantWebhookController(MerchantWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /**
     * Registers a new webhook endpoint.
     * Reveals the raw signing secret in a one-time response.
     */
    @PostMapping
    public ResponseEntity<WebhookEndpointCreatedResponse> registerEndpoint(
            @PathVariable UUID merchantId,
            @Valid @RequestBody RegisterWebhookEndpointRequest request) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        WebhookEndpointCreatedResponse response = webhookService.registerEndpoint(merchantId, request, callerUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Lists paginated webhook endpoints for a merchant with masked secrets.
     */
    @GetMapping
    public ResponseEntity<PagedResponse<WebhookEndpointResponse>> listEndpoints(
            @PathVariable UUID merchantId,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        PagedResponse<WebhookEndpointResponse> response = webhookService.listEndpoints(merchantId, pageable, callerUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves endpoint details with masked secret.
     */
    @GetMapping("/{endpointId}")
    public ResponseEntity<WebhookEndpointResponse> getEndpoint(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        WebhookEndpointResponse response = webhookService.getEndpoint(merchantId, endpointId, callerUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Updates permitted endpoint configuration (URL and/or description).
     */
    @PatchMapping("/{endpointId}")
    public ResponseEntity<WebhookEndpointResponse> updateEndpoint(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId,
            @Valid @RequestBody UpdateWebhookEndpointRequest request) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        WebhookEndpointResponse response = webhookService.updateEndpoint(merchantId, endpointId, request, callerUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Disables an endpoint, pausing pending delivery attempts.
     */
    @PostMapping("/{endpointId}/disable")
    public ResponseEntity<WebhookEndpointResponse> disableEndpoint(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        WebhookEndpointResponse response = webhookService.disableEndpoint(merchantId, endpointId, callerUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Re-enables a disabled endpoint, resuming paused delivery attempts.
     */
    @PostMapping("/{endpointId}/enable")
    public ResponseEntity<WebhookEndpointResponse> enableEndpoint(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        WebhookEndpointResponse response = webhookService.enableEndpoint(merchantId, endpointId, callerUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Archives an endpoint permanently.
     */
    @DeleteMapping("/{endpointId}")
    public ResponseEntity<WebhookEndpointResponse> archiveEndpoint(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        WebhookEndpointResponse response = webhookService.archiveEndpoint(merchantId, endpointId, callerUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves subscribed event list for an endpoint.
     */
    @GetMapping("/{endpointId}/subscriptions")
    public ResponseEntity<WebhookEventSubscriptionResponse> getSubscriptions(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        WebhookEventSubscriptionResponse response = webhookService.getSubscriptions(merchantId, endpointId, callerUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Replaces the subscribed event list for an endpoint.
     */
    @PutMapping("/{endpointId}/subscriptions")
    public ResponseEntity<WebhookEventSubscriptionResponse> updateSubscriptions(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId,
            @Valid @RequestBody UpdateWebhookSubscriptionsRequest request) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        WebhookEventSubscriptionResponse response = webhookService.updateSubscriptions(merchantId, endpointId, request, callerUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Adds an event subscription to an endpoint.
     */
    @PostMapping("/{endpointId}/subscriptions/add")
    public ResponseEntity<WebhookEventSubscriptionResponse> addSubscription(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId,
            @RequestParam String eventType) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        WebhookEventSubscriptionResponse response = webhookService.addSubscription(merchantId, endpointId, eventType, callerUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Removes an event subscription from an endpoint.
     */
    @PostMapping("/{endpointId}/subscriptions/remove")
    public ResponseEntity<WebhookEventSubscriptionResponse> removeSubscription(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId,
            @RequestParam String eventType) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        WebhookEventSubscriptionResponse response = webhookService.removeSubscription(merchantId, endpointId, eventType, callerUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Rotates the signing secret, incrementing the secret version and revealing the new secret once.
     */
    @PostMapping("/{endpointId}/rotate-secret")
    public ResponseEntity<WebhookEndpointCreatedResponse> rotateSecret(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId) {
        UUID callerUserId = SecurityUtils.getCurrentUserId();
        WebhookEndpointCreatedResponse response = webhookService.rotateSecret(merchantId, endpointId, callerUserId);
        return ResponseEntity.ok(response);
    }
}
