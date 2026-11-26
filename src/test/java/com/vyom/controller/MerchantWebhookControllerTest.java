package com.vyom.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyom.dto.request.RegisterWebhookEndpointRequest;
import com.vyom.dto.request.UpdateWebhookEndpointRequest;
import com.vyom.dto.request.UpdateWebhookSubscriptionsRequest;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.WebhookEndpointCreatedResponse;
import com.vyom.dto.response.WebhookEndpointResponse;
import com.vyom.dto.response.WebhookEventSubscriptionResponse;
import com.vyom.entity.enums.Role;
import com.vyom.exception.DuplicateWebhookEndpointException;
import com.vyom.exception.GlobalExceptionHandler;
import com.vyom.exception.InvalidWebhookEventException;
import com.vyom.exception.SSRFProtectionException;
import com.vyom.exception.WebhookEndpointNotFoundException;
import com.vyom.security.JwtTokenService;
import com.vyom.security.UserPrincipal;
import com.vyom.service.MerchantWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MerchantWebhookController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class MerchantWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MerchantWebhookService webhookService;

    @MockBean
    private JwtTokenService jwtTokenService;

    @MockBean
    private com.vyom.repository.UserRepository userRepository;

    private final UUID mockUserId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID merchantId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID endpointId = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUpSecurity() {
        UserPrincipal principal = new UserPrincipal(mockUserId, "merchant.user@vyom.internal", "Merchant User", "", Role.USER);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private WebhookEndpointResponse sampleResponse(UUID endpointId, UUID merchantId, String url, String desc, boolean active, Instant archivedAt) {
        return new WebhookEndpointResponse(
                endpointId,
                merchantId,
                url,
                desc,
                "whsec_...cdef",
                1,
                List.of("order.paid"),
                active,
                Instant.now(),
                Instant.now(),
                archivedAt
        );
    }

    private WebhookEndpointCreatedResponse sampleCreatedResponse(UUID endpointId, UUID merchantId, int secretVersion) {
        return new WebhookEndpointCreatedResponse(
                endpointId,
                merchantId,
                "https://api.merchant.com/webhooks",
                "Primary Webhook Endpoint",
                "whsec_0123456789abcdef0123456789abcdef",
                "whsec_...cdef",
                secretVersion,
                List.of("order.paid"),
                true,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{merchantId}/webhooks - 201 Created on valid endpoint registration with one-time secret")
    void registerEndpoint_success() throws Exception {
        RegisterWebhookEndpointRequest request = new RegisterWebhookEndpointRequest(
                "https://api.merchant.com/webhooks",
                "Primary Webhook Endpoint",
                List.of("order.paid")
        );

        WebhookEndpointCreatedResponse response = sampleCreatedResponse(endpointId, merchantId, 1);

        when(webhookService.registerEndpoint(eq(merchantId), any(RegisterWebhookEndpointRequest.class), eq(mockUserId)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/webhooks", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(endpointId.toString()))
                .andExpect(jsonPath("$.merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.url").value("https://api.merchant.com/webhooks"))
                .andExpect(jsonPath("$.secret").value("whsec_0123456789abcdef0123456789abcdef"))
                .andExpect(jsonPath("$.secretMasked").value("whsec_...cdef"))
                .andExpect(jsonPath("$.secretVersion").value(1))
                .andExpect(jsonPath("$.isActive").value(true));

        verify(webhookService).registerEndpoint(eq(merchantId), any(RegisterWebhookEndpointRequest.class), eq(mockUserId));
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{merchantId}/webhooks - 400 Bad Request on SSRF protection rejection")
    void registerEndpoint_ssrfRejected() throws Exception {
        RegisterWebhookEndpointRequest request = new RegisterWebhookEndpointRequest(
                "https://127.0.0.1/webhook",
                "Local loopback endpoint",
                List.of("order.paid")
        );

        when(webhookService.registerEndpoint(eq(merchantId), any(RegisterWebhookEndpointRequest.class), eq(mockUserId)))
                .thenThrow(new SSRFProtectionException("SSRF Protection: Webhook destination resolves to non-public destination"));

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/webhooks", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("SSRF_PROTECTION_VIOLATION"));
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{merchantId}/webhooks - 409 Conflict on duplicate endpoint URL")
    void registerEndpoint_duplicate() throws Exception {
        RegisterWebhookEndpointRequest request = new RegisterWebhookEndpointRequest(
                "https://api.merchant.com/webhooks",
                "Duplicate",
                List.of("order.paid")
        );

        when(webhookService.registerEndpoint(eq(merchantId), any(RegisterWebhookEndpointRequest.class), eq(mockUserId)))
                .thenThrow(new DuplicateWebhookEndpointException("Active webhook endpoint already exists with URL: " + request.url()));

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/webhooks", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_WEBHOOK_ENDPOINT"));
    }

    @Test
    @DisplayName("GET /api/v1/merchants/{merchantId}/webhooks - 200 OK paginated endpoints with masked secrets")
    void listEndpoints_success() throws Exception {
        WebhookEndpointResponse item = sampleResponse(endpointId, merchantId, "https://api.merchant.com/webhooks", "Active", true, null);

        PagedResponse<WebhookEndpointResponse> pagedResponse = new PagedResponse<>(
                List.of(item),
                0,
                20,
                1L,
                1,
                false
        );

        when(webhookService.listEndpoints(eq(merchantId), any(Pageable.class), eq(mockUserId)))
                .thenReturn(pagedResponse);

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/webhooks", merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(endpointId.toString()))
                .andExpect(jsonPath("$.items[0].secretMasked").value("whsec_...cdef"))
                .andExpect(jsonPath("$.items[0].secret").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/merchants/{merchantId}/webhooks/{endpointId} - 200 OK single endpoint")
    void getEndpoint_success() throws Exception {
        WebhookEndpointResponse response = sampleResponse(endpointId, merchantId, "https://api.merchant.com/webhooks", "Single", true, null);

        when(webhookService.getEndpoint(eq(merchantId), eq(endpointId), eq(mockUserId)))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/webhooks/{endpointId}", merchantId, endpointId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(endpointId.toString()))
                .andExpect(jsonPath("$.secretMasked").value("whsec_...cdef"))
                .andExpect(jsonPath("$.secret").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/merchants/{merchantId}/webhooks/{endpointId} - 404 Not Found")
    void getEndpoint_notFound() throws Exception {
        when(webhookService.getEndpoint(eq(merchantId), eq(endpointId), eq(mockUserId)))
                .thenThrow(new WebhookEndpointNotFoundException("Endpoint not found with ID: " + endpointId));

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/webhooks/{endpointId}", merchantId, endpointId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("WEBHOOK_ENDPOINT_NOT_FOUND"));
    }

    @Test
    @DisplayName("PATCH /api/v1/merchants/{merchantId}/webhooks/{endpointId} - 200 OK update URL and description")
    void updateEndpoint_success() throws Exception {
        UpdateWebhookEndpointRequest request = new UpdateWebhookEndpointRequest(
                "https://updated.merchant.com/webhooks",
                "Updated description"
        );

        WebhookEndpointResponse response = sampleResponse(endpointId, merchantId, "https://updated.merchant.com/webhooks", "Updated description", true, null);

        when(webhookService.updateEndpoint(eq(merchantId), eq(endpointId), any(UpdateWebhookEndpointRequest.class), eq(mockUserId)))
                .thenReturn(response);

        mockMvc.perform(patch("/api/v1/merchants/{merchantId}/webhooks/{endpointId}", merchantId, endpointId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://updated.merchant.com/webhooks"))
                .andExpect(jsonPath("$.description").value("Updated description"));
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{merchantId}/webhooks/{endpointId}/disable - 200 OK disables endpoint")
    void disableEndpoint_success() throws Exception {
        WebhookEndpointResponse response = sampleResponse(endpointId, merchantId, "https://api.merchant.com/webhooks", "Disabled", false, null);

        when(webhookService.disableEndpoint(eq(merchantId), eq(endpointId), eq(mockUserId)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/webhooks/{endpointId}/disable", merchantId, endpointId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{merchantId}/webhooks/{endpointId}/enable - 200 OK enables endpoint")
    void enableEndpoint_success() throws Exception {
        WebhookEndpointResponse response = sampleResponse(endpointId, merchantId, "https://api.merchant.com/webhooks", "Enabled", true, null);

        when(webhookService.enableEndpoint(eq(merchantId), eq(endpointId), eq(mockUserId)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/webhooks/{endpointId}/enable", merchantId, endpointId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    @DisplayName("DELETE /api/v1/merchants/{merchantId}/webhooks/{endpointId} - 200 OK archives endpoint")
    void archiveEndpoint_success() throws Exception {
        WebhookEndpointResponse response = sampleResponse(endpointId, merchantId, "https://api.merchant.com/webhooks", "Archived", false, Instant.now());

        when(webhookService.archiveEndpoint(eq(merchantId), eq(endpointId), eq(mockUserId)))
                .thenReturn(response);

        mockMvc.perform(delete("/api/v1/merchants/{merchantId}/webhooks/{endpointId}", merchantId, endpointId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/merchants/{merchantId}/webhooks/{endpointId}/subscriptions - 200 OK")
    void getSubscriptions_success() throws Exception {
        WebhookEventSubscriptionResponse response = new WebhookEventSubscriptionResponse(
                endpointId,
                merchantId,
                List.of("order.paid", "order.refunded")
        );

        when(webhookService.getSubscriptions(eq(merchantId), eq(endpointId), eq(mockUserId)))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/webhooks/{endpointId}/subscriptions", merchantId, endpointId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribedEvents.length()").value(2))
                .andExpect(jsonPath("$.subscribedEvents[0]").value("order.paid"));
    }

    @Test
    @DisplayName("PUT /api/v1/merchants/{merchantId}/webhooks/{endpointId}/subscriptions - 200 OK")
    void updateSubscriptions_success() throws Exception {
        UpdateWebhookSubscriptionsRequest request = new UpdateWebhookSubscriptionsRequest(
                List.of("order.failed", "order.cancelled")
        );

        WebhookEventSubscriptionResponse response = new WebhookEventSubscriptionResponse(
                endpointId,
                merchantId,
                List.of("order.failed", "order.cancelled")
        );

        when(webhookService.updateSubscriptions(eq(merchantId), eq(endpointId), any(UpdateWebhookSubscriptionsRequest.class), eq(mockUserId)))
                .thenReturn(response);

        mockMvc.perform(put("/api/v1/merchants/{merchantId}/webhooks/{endpointId}/subscriptions", merchantId, endpointId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribedEvents.length()").value(2));
    }

    @Test
    @DisplayName("PUT /api/v1/merchants/{merchantId}/webhooks/{endpointId}/subscriptions - 400 on invalid event")
    void updateSubscriptions_invalidEvent() throws Exception {
        UpdateWebhookSubscriptionsRequest request = new UpdateWebhookSubscriptionsRequest(
                List.of("INVALID_EVENT_NAME")
        );

        when(webhookService.updateSubscriptions(eq(merchantId), eq(endpointId), any(UpdateWebhookSubscriptionsRequest.class), eq(mockUserId)))
                .thenThrow(new InvalidWebhookEventException("Invalid webhook event type: INVALID_EVENT_NAME"));

        mockMvc.perform(put("/api/v1/merchants/{merchantId}/webhooks/{endpointId}/subscriptions", merchantId, endpointId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_WEBHOOK_EVENT"));
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{merchantId}/webhooks/{endpointId}/subscriptions/add - 200 OK")
    void addSubscription_success() throws Exception {
        WebhookEventSubscriptionResponse response = new WebhookEventSubscriptionResponse(
                endpointId,
                merchantId,
                List.of("order.paid", "order.refunded")
        );

        when(webhookService.addSubscription(eq(merchantId), eq(endpointId), eq("order.refunded"), eq(mockUserId)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/webhooks/{endpointId}/subscriptions/add", merchantId, endpointId)
                        .param("eventType", "order.refunded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribedEvents.length()").value(2));
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{merchantId}/webhooks/{endpointId}/subscriptions/remove - 200 OK")
    void removeSubscription_success() throws Exception {
        WebhookEventSubscriptionResponse response = new WebhookEventSubscriptionResponse(
                endpointId,
                merchantId,
                List.of("order.paid")
        );

        when(webhookService.removeSubscription(eq(merchantId), eq(endpointId), eq("order.refunded"), eq(mockUserId)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/webhooks/{endpointId}/subscriptions/remove", merchantId, endpointId)
                        .param("eventType", "order.refunded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribedEvents.length()").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{merchantId}/webhooks/{endpointId}/rotate-secret - 200 OK reveals new secret")
    void rotateSecret_success() throws Exception {
        WebhookEndpointCreatedResponse response = sampleCreatedResponse(endpointId, merchantId, 2);

        when(webhookService.rotateSecret(eq(merchantId), eq(endpointId), eq(mockUserId)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/webhooks/{endpointId}/rotate-secret", merchantId, endpointId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secretVersion").value(2))
                .andExpect(jsonPath("$.secret").value("whsec_0123456789abcdef0123456789abcdef"))
                .andExpect(jsonPath("$.secretMasked").value("whsec_...cdef"));
    }
}
