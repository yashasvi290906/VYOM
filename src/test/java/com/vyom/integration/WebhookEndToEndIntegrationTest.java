package com.vyom.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyom.dto.request.CreateMerchantRequest;
import com.vyom.dto.request.RegisterWebhookEndpointRequest;
import com.vyom.dto.request.UpdateWebhookSubscriptionsRequest;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.MerchantResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.WebhookEndpointCreatedResponse;
import com.vyom.dto.response.WebhookEndpointResponse;
import com.vyom.dto.response.WebhookEventSubscriptionResponse;
import com.vyom.entity.MerchantWebhookEndpoint;
import com.vyom.entity.OutboxEvent;
import com.vyom.entity.User;
import com.vyom.entity.WebhookAttemptOutcome;
import com.vyom.entity.WebhookDelivery;
import com.vyom.entity.WebhookDeliveryAttempt;
import com.vyom.entity.enums.AccountStatus;
import com.vyom.entity.enums.BusinessCategory;
import com.vyom.entity.enums.BusinessType;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.OnboardingStatus;
import com.vyom.entity.enums.Role;
import com.vyom.entity.enums.WebhookDeliveryStatus;
import com.vyom.repository.MerchantRepository;
import com.vyom.repository.MerchantWebhookEndpointRepository;
import com.vyom.repository.OutboxEventRepository;
import com.vyom.repository.WebhookAttemptOutcomeRepository;
import com.vyom.repository.WebhookDeliveryAttemptRepository;
import com.vyom.repository.WebhookDeliveryRepository;
import com.vyom.service.OutboxDispatcherService;
import com.vyom.service.SSRFShieldService;
import com.vyom.service.WebhookCryptoService;
import com.vyom.service.WebhookDeliveryService;
import com.vyom.service.transport.BoundedResponse;
import com.vyom.service.transport.WebhookHttpClientFactory;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-End Production Integration Tests for VYOM Merchant Webhook Engine (Stage 2.2.6).
 *
 * Verifies complete operational lifecycle from REST API registration to transactional outbox fan-out,
 * authenticated AES-256-GCM secret decryption, HMAC-SHA256 signature verification over canonical payload,
 * append-only attempt/outcome audit persistence, secret rotation, pause/resume synchronization,
 * and zero-IDOR multi-tenant isolation.
 */
public class WebhookEndToEndIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MerchantWebhookEndpointRepository endpointRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private WebhookDeliveryAttemptRepository attemptRepository;

    @Autowired
    private WebhookAttemptOutcomeRepository outcomeRepository;

    @Autowired
    private OutboxDispatcherService dispatcherService;

    @Autowired
    private WebhookDeliveryService deliveryService;

    @Autowired
    private WebhookCryptoService cryptoService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WebhookHttpClientFactory httpClientFactory;

    @MockitoBean
    private SSRFShieldService ssrfShieldService;

    private User alice;
    private User bob;
    private String aliceToken;
    private String bobToken;

    private UUID aliceMerchantId;
    private UUID bobMerchantId;

    @BeforeEach
    void setUpUsersAndMerchants() {
        reset(httpClientFactory, ssrfShieldService);
        when(ssrfShieldService.validateUrl(any())).thenAnswer(inv -> URI.create(inv.getArgument(0)));

        // User Alice (Owner of Merchant A)
        alice = new User();
        alice.setEmail("alice.wh." + UUID.randomUUID().toString().substring(0, 8) + "@vyom.internal");
        alice.setFullName("Alice Webhook Owner");
        alice.setPasswordHash(passwordEncoder.encode("Password123!"));
        alice.setRole(Role.USER);
        alice = userRepository.save(alice);
        aliceToken = jwtTokenService.generateToken(alice.getId(), alice.getEmail(), alice.getFullName(), Role.USER);

        // User Bob (Owner of Merchant B)
        bob = new User();
        bob.setEmail("bob.wh." + UUID.randomUUID().toString().substring(0, 8) + "@vyom.internal");
        bob.setFullName("Bob Other Owner");
        bob.setPasswordHash(passwordEncoder.encode("Password123!"));
        bob.setRole(Role.USER);
        bob = userRepository.save(bob);
        bobToken = jwtTokenService.generateToken(bob.getId(), bob.getEmail(), bob.getFullName(), Role.USER);

        // Provision active merchants for Alice and Bob
        aliceMerchantId = provisionActiveMerchant(alice, "Alice Retail");
        bobMerchantId = provisionActiveMerchant(bob, "Bob Wholesale");
    }

    private UUID provisionActiveMerchant(User owner, String businessName) {
        UUID merchantId = UUID.randomUUID();
        String merchantCode = "MCH_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO merchants (id, merchant_code, business_name, legal_business_name, business_type, " +
                "business_category, registration_email, business_phone, country, business_address, city, state, postal_code, " +
                "onboarding_status, account_status, created_by) " +
                "VALUES (?, ?, ?, ?, 'PRIVATE_LIMITED', 'RETAIL', ?, '+919876543210', 'IND', '100 Market St', 'Bengaluru', 'Karnataka', '560001', " +
                "'APPROVED', 'ACTIVE', ?)",
                merchantId, merchantCode, businessName, businessName + " Pvt Ltd", owner.getEmail(), owner.getId()
        );

        jdbcTemplate.update(
                "INSERT INTO merchant_members (id, merchant_id, user_id, merchant_role, membership_status, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'MERCHANT_OWNER', 'ACTIVE', NOW(), NOW())",
                UUID.randomUUID(), merchantId, owner.getId()
        );

        return merchantId;
    }

    @Test
    @DisplayName("End-to-End: Full merchant webhook lifecycle from REST API to signed HTTP delivery and append-only audit")
    void testCompleteWebhookLifecycle() throws Exception {
        // 1. Merchant Alice registers a webhook endpoint via REST API
        RegisterWebhookEndpointRequest regReq = new RegisterWebhookEndpointRequest(
                "https://api.merchant-alice.com/webhook-receiver",
                "Primary production webhook",
                List.of("order.paid")
        );

        ResponseEntity<WebhookEndpointCreatedResponse> regResp = restTemplate.exchange(
                "/api/v1/merchants/" + aliceMerchantId + "/webhooks",
                HttpMethod.POST,
                new HttpEntity<>(regReq, authHeaders(aliceToken)),
                WebhookEndpointCreatedResponse.class
        );

        assertThat(regResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        WebhookEndpointCreatedResponse created = regResp.getBody();
        assertThat(created).isNotNull();
        UUID endpointId = created.id();
        String rawSecret = created.secret();

        // Verify one-time secret revelation
        assertThat(rawSecret).startsWith("whsec_").hasSizeGreaterThan(20);
        assertThat(created.secretMasked()).startsWith("whsec_...").doesNotContain(rawSecret.substring(10, 20));
        assertThat(created.secretVersion()).isEqualTo(1);
        assertThat(created.isActive()).isTrue();

        // 2. Subsequent GET retrieves endpoint with masked secret ONLY
        ResponseEntity<WebhookEndpointResponse> getResp = restTemplate.exchange(
                "/api/v1/merchants/" + aliceMerchantId + "/webhooks/" + endpointId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(aliceToken)),
                WebhookEndpointResponse.class
        );
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody().secretMasked()).isEqualTo(created.secretMasked());

        // 3. A real application event enters the transactional outbox
        String canonicalPayload = "{\"orderId\":\"ord-alice-1001\",\"amount\":\"1500.00\",\"currency\":\"INR\",\"status\":\"PAID\"}";
        OutboxEvent outboxEvent = new OutboxEvent(
                aliceMerchantId,
                "order.paid",
                "ORDER",
                UUID.randomUUID(),
                canonicalPayload
        );
        outboxEvent = outboxEventRepository.save(outboxEvent);

        // 4. Dispatcher discovers and fans out the event
        int dispatched = dispatcherService.dispatchPendingEvents(10);
        assertThat(dispatched).isGreaterThanOrEqualTo(1);

        outboxEvent = outboxEventRepository.findById(outboxEvent.getId()).orElseThrow();
        List<WebhookDelivery> deliveries = deliveryRepository.findByOutboxEventId(outboxEvent.getId());
        assertThat(deliveries).hasSize(1);
        WebhookDelivery delivery = deliveries.get(0);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getCanonicalPayload()).isEqualTo(outboxEvent.getPayload());

        // 5. Setup mock HTTP destination to return 200 OK
        ArgumentCaptor<ClassicHttpRequest> requestCaptor = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        when(httpClientFactory.execute(requestCaptor.capture()))
                .thenReturn(new BoundedResponse(200, "OK", Map.of(), "{\"received\":true}".getBytes(), false, 25));

        // 6. Deliver the webhook via delivery service
        boolean deliveryResult = deliveryService.processSingleDelivery(delivery.getId());
        assertThat(deliveryResult).isTrue();

        // 7. Verify HTTP request parameters, signature, and headers
        ClassicHttpRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.getUri().toString()).isEqualTo("https://api.merchant-alice.com/webhook-receiver");

        Header sigHeader = capturedRequest.getHeader("Vyom-Signature");
        assertThat(sigHeader).isNotNull();
        String signatureHeaderValue = sigHeader.getValue();
        assertThat(signatureHeaderValue).contains("t=").contains("v1=");

        Header deliveryIdHeader = capturedRequest.getHeader("Vyom-Delivery-Id");
        assertThat(deliveryIdHeader).isNotNull();
        assertThat(deliveryIdHeader.getValue()).isEqualTo(delivery.getId().toString());

        Header eventIdHeader = capturedRequest.getHeader("Vyom-Event-Id");
        assertThat(eventIdHeader).isNotNull();
        assertThat(eventIdHeader.getValue()).isEqualTo(outboxEvent.getId().toString());

        String capturedBody = EntityUtils.toString(capturedRequest.getEntity());
        assertThat(capturedBody).isEqualTo(delivery.getCanonicalPayload());

        // 8. Cryptographically verify signature using the one-time disclosed secret
        String[] sigParts = signatureHeaderValue.split(",");
        long timestamp = Long.parseLong(sigParts[0].substring(2));
        String expectedSignature = cryptoService.signPayload(rawSecret, timestamp, delivery.getCanonicalPayload());
        assertThat(signatureHeaderValue).isEqualTo(expectedSignature);

        // 9. Verify append-only attempt and outcome persistence
        List<WebhookDeliveryAttempt> attempts = attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId());
        assertThat(attempts).hasSize(1);
        WebhookDeliveryAttempt attempt1 = attempts.get(0);
        assertThat(attempt1.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt1.getSecretVersion()).isEqualTo(1);

        WebhookAttemptOutcome outcome1 = outcomeRepository.findByAttemptId(attempt1.getId()).orElseThrow();
        assertThat(outcome1.getResponseStatus()).isEqualTo(200);
        assertThat(outcome1.getResponseBody()).isEqualTo("{\"received\":true}");
        assertThat(outcome1.getErrorMessage()).isNull();

        WebhookDelivery finalDelivery = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(finalDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("Secret Rotation: Rotates signing secret, increments version, and signs subsequent events with new secret")
    void testSecretRotationLifecycle() throws Exception {
        // Register endpoint
        RegisterWebhookEndpointRequest regReq = new RegisterWebhookEndpointRequest(
                "https://api.merchant-alice.com/rotation-receiver",
                "Rotation test endpoint",
                List.of("order.paid")
        );
        ResponseEntity<WebhookEndpointCreatedResponse> regResp = restTemplate.exchange(
                "/api/v1/merchants/" + aliceMerchantId + "/webhooks",
                HttpMethod.POST,
                new HttpEntity<>(regReq, authHeaders(aliceToken)),
                WebhookEndpointCreatedResponse.class
        );
        UUID endpointId = regResp.getBody().id();
        String initialSecret = regResp.getBody().secret();

        // Rotate secret via POST /rotate-secret
        ResponseEntity<WebhookEndpointCreatedResponse> rotResp = restTemplate.exchange(
                "/api/v1/merchants/" + aliceMerchantId + "/webhooks/" + endpointId + "/rotate-secret",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(aliceToken)),
                WebhookEndpointCreatedResponse.class
        );
        assertThat(rotResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        WebhookEndpointCreatedResponse rotated = rotResp.getBody();
        assertThat(rotated.secretVersion()).isEqualTo(2);
        String rotatedSecret = rotated.secret();
        assertThat(rotatedSecret).isNotEqualTo(initialSecret);

        // Send outbox event
        String payload = "{\"orderId\":\"ord-alice-rot\",\"status\":\"PAID\"}";
        OutboxEvent event = outboxEventRepository.save(new OutboxEvent(aliceMerchantId, "order.paid", "ORDER", UUID.randomUUID(), payload));
        dispatcherService.dispatchPendingEvents(10);
        WebhookDelivery delivery = deliveryRepository.findByOutboxEventId(event.getId()).get(0);

        ArgumentCaptor<ClassicHttpRequest> requestCaptor = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        when(httpClientFactory.execute(requestCaptor.capture()))
                .thenReturn(new BoundedResponse(200, "OK", Map.of(), "ok".getBytes(), false, 20));

        deliveryService.processSingleDelivery(delivery.getId());

        // Verify that signature on HTTP request validates against the NEW rotated secret (version 2)
        ClassicHttpRequest captured = requestCaptor.getValue();
        String sigHeader = captured.getHeader("Vyom-Signature").getValue();
        long timestamp = Long.parseLong(sigHeader.split(",")[0].substring(2));
        String expectedWithNewSecret = cryptoService.signPayload(rotatedSecret, timestamp, delivery.getCanonicalPayload());
        assertThat(sigHeader).isEqualTo(expectedWithNewSecret);

        // Verify attempt record recorded secret version 2
        WebhookDeliveryAttempt attempt = attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId()).get(0);
        assertThat(attempt.getSecretVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("Retry-After compliance: Respects HTTP 429 Retry-After header and schedules backoff")
    void testRetryAfterHeaderCompliance() throws Exception {
        RegisterWebhookEndpointRequest regReq = new RegisterWebhookEndpointRequest(
                "https://api.merchant-alice.com/rate-limited",
                "Rate limit test endpoint",
                List.of("order.paid")
        );
        ResponseEntity<WebhookEndpointCreatedResponse> regResp = restTemplate.exchange(
                "/api/v1/merchants/" + aliceMerchantId + "/webhooks",
                HttpMethod.POST,
                new HttpEntity<>(regReq, authHeaders(aliceToken)),
                WebhookEndpointCreatedResponse.class
        );
        UUID endpointId = regResp.getBody().id();

        OutboxEvent event = outboxEventRepository.save(
                new OutboxEvent(aliceMerchantId, "order.paid", "ORDER", UUID.randomUUID(), "{\"amount\":100}")
        );
        dispatcherService.dispatchPendingEvents(10);
        WebhookDelivery delivery = deliveryRepository.findByOutboxEventId(event.getId()).get(0);

        // Mock remote destination responding with 429 Too Many Requests and Retry-After: 90
        when(httpClientFactory.execute(any(ClassicHttpRequest.class)))
                .thenReturn(new BoundedResponse(429, "Too Many Requests", Map.of("retry-after", "90"), "slow down".getBytes(), false, 30));

        Instant beforeExecution = Instant.now();
        deliveryService.processSingleDelivery(delivery.getId());

        WebhookDelivery updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRY_WAIT);
        assertThat(updated.getAttemptCount()).isEqualTo(1);

        // Verify nextAttemptAt is scheduled ~90 seconds in future
        assertThat(updated.getNextAttemptAt()).isAfterOrEqualTo(beforeExecution.plusSeconds(85));
        assertThat(updated.getNextAttemptAt()).isBeforeOrEqualTo(beforeExecution.plusSeconds(105));
    }

    @Test
    @DisplayName("Transport Resilience: Handles socket timeout and network errors with retry backoff")
    void testTransportExceptionResilience() throws Exception {
        RegisterWebhookEndpointRequest regReq = new RegisterWebhookEndpointRequest(
                "https://api.merchant-alice.com/flaky",
                "Flaky endpoint",
                List.of("order.paid")
        );
        ResponseEntity<WebhookEndpointCreatedResponse> regResp = restTemplate.exchange(
                "/api/v1/merchants/" + aliceMerchantId + "/webhooks",
                HttpMethod.POST,
                new HttpEntity<>(regReq, authHeaders(aliceToken)),
                WebhookEndpointCreatedResponse.class
        );
        UUID endpointId = regResp.getBody().id();

        OutboxEvent event = outboxEventRepository.save(
                new OutboxEvent(aliceMerchantId, "order.paid", "ORDER", UUID.randomUUID(), "{\"amount\":500}")
        );
        dispatcherService.dispatchPendingEvents(10);
        WebhookDelivery delivery = deliveryRepository.findByOutboxEventId(event.getId()).get(0);

        // Mock connection timeout
        when(httpClientFactory.execute(any(ClassicHttpRequest.class)))
                .thenThrow(new SocketTimeoutException("Read timed out after 10000ms"));

        deliveryService.processSingleDelivery(delivery.getId());

        WebhookDelivery updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRY_WAIT);
        assertThat(updated.getAttemptCount()).isEqualTo(1);

        // Audit outcome record captures the timeout message
        WebhookDeliveryAttempt attempt = attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId()).get(0);
        WebhookAttemptOutcome outcome = outcomeRepository.findByAttemptId(attempt.getId()).orElseThrow();
        assertThat(outcome.getResponseStatus()).isNull();
        assertThat(outcome.getErrorMessage()).contains("Read timed out");
    }

    @Test
    @DisplayName("Endpoint operational synchronization: Disabling pauses pending deliveries; enabling resumes them")
    void testEndpointDisableAndEnableOperationalSync() {
        RegisterWebhookEndpointRequest regReq = new RegisterWebhookEndpointRequest(
                "https://api.merchant-alice.com/pause-test",
                "Pause sync endpoint",
                List.of("order.paid")
        );
        ResponseEntity<WebhookEndpointCreatedResponse> regResp = restTemplate.exchange(
                "/api/v1/merchants/" + aliceMerchantId + "/webhooks",
                HttpMethod.POST,
                new HttpEntity<>(regReq, authHeaders(aliceToken)),
                WebhookEndpointCreatedResponse.class
        );
        UUID endpointId = regResp.getBody().id();

        OutboxEvent event = outboxEventRepository.save(
                new OutboxEvent(aliceMerchantId, "order.paid", "ORDER", UUID.randomUUID(), "{\"amount\":700}")
        );
        dispatcherService.dispatchPendingEvents(10);
        WebhookDelivery delivery = deliveryRepository.findByOutboxEventId(event.getId()).get(0);

        // Disable endpoint via REST API
        ResponseEntity<WebhookEndpointResponse> disableResp = restTemplate.exchange(
                "/api/v1/merchants/" + aliceMerchantId + "/webhooks/" + endpointId + "/disable",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(aliceToken)),
                WebhookEndpointResponse.class
        );
        assertThat(disableResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disableResp.getBody().isActive()).isFalse();

        // Verify pending delivery was synchronized to PAUSED
        WebhookDelivery pausedDelivery = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(pausedDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PAUSED);

        // Re-enable endpoint via REST API
        ResponseEntity<WebhookEndpointResponse> enableResp = restTemplate.exchange(
                "/api/v1/merchants/" + aliceMerchantId + "/webhooks/" + endpointId + "/enable",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(aliceToken)),
                WebhookEndpointResponse.class
        );
        assertThat(enableResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(enableResp.getBody().isActive()).isTrue();

        // Verify paused delivery was synchronized to RETRY_WAIT with immediate retry
        WebhookDelivery resumedDelivery = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(resumedDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRY_WAIT);
        assertThat(resumedDelivery.getNextAttemptAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("Security & Multi-Tenant Isolation: Cross-merchant endpoint access returns 404 (zero IDOR leakage)")
    void testCrossTenantIsolationThroughGateway() {
        // Alice registers an endpoint
        RegisterWebhookEndpointRequest regReq = new RegisterWebhookEndpointRequest(
                "https://api.merchant-alice.com/isolated",
                "Alice private endpoint",
                List.of("order.paid")
        );
        ResponseEntity<WebhookEndpointCreatedResponse> regResp = restTemplate.exchange(
                "/api/v1/merchants/" + aliceMerchantId + "/webhooks",
                HttpMethod.POST,
                new HttpEntity<>(regReq, authHeaders(aliceToken)),
                WebhookEndpointCreatedResponse.class
        );
        UUID aliceEndpointId = regResp.getBody().id();

        // Bob (Merchant B) attempts to read Alice's endpoint under Bob's merchant path -> 404 Not Found
        ResponseEntity<ErrorResponse> bobGetUnderBob = restTemplate.exchange(
                "/api/v1/merchants/" + bobMerchantId + "/webhooks/" + aliceEndpointId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(bobToken)),
                ErrorResponse.class
        );
        assertThat(bobGetUnderBob.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Bob attempts to mutate Alice's endpoint under Alice's merchant path -> 404 Not Found (Anti-enumeration: Bob is not a member of Merchant A)
        ResponseEntity<ErrorResponse> bobMutateAlice = restTemplate.exchange(
                "/api/v1/merchants/" + aliceMerchantId + "/webhooks/" + aliceEndpointId + "/disable",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(bobToken)),
                ErrorResponse.class
        );
        assertThat(bobMutateAlice.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Bob attempts to rotate secret on Alice's endpoint -> 404 Not Found (Anti-enumeration)
        ResponseEntity<ErrorResponse> bobRotateAlice = restTemplate.exchange(
                "/api/v1/merchants/" + aliceMerchantId + "/webhooks/" + aliceEndpointId + "/rotate-secret",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(bobToken)),
                ErrorResponse.class
        );
        assertThat(bobRotateAlice.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Charlie (a Viewer member of Merchant A) attempts to mutate Alice's endpoint -> 403 Forbidden (RBAC enforcement)
        User charlie = new User();
        charlie.setEmail("charlie.wh." + UUID.randomUUID().toString().substring(0, 8) + "@vyom.internal");
        charlie.setFullName("Charlie Viewer");
        charlie.setPasswordHash(passwordEncoder.encode("Password123!"));
        charlie.setRole(Role.USER);
        charlie = userRepository.save(charlie);
        String charlieToken = jwtTokenService.generateToken(charlie.getId(), charlie.getEmail(), charlie.getFullName(), Role.USER);
        jdbcTemplate.update(
                "INSERT INTO merchant_members (id, merchant_id, user_id, merchant_role, membership_status, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'MERCHANT_VIEWER', 'ACTIVE', NOW(), NOW())",
                UUID.randomUUID(), aliceMerchantId, charlie.getId()
        );

        ResponseEntity<ErrorResponse> charlieMutateAlice = restTemplate.exchange(
                "/api/v1/merchants/" + aliceMerchantId + "/webhooks/" + aliceEndpointId + "/disable",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(charlieToken)),
                ErrorResponse.class
        );
        assertThat(charlieMutateAlice.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Bob lists endpoints under Bob's merchant -> sees only Bob's endpoints (0 items)
        ResponseEntity<PagedResponse<WebhookEndpointResponse>> bobList = restTemplate.exchange(
                "/api/v1/merchants/" + bobMerchantId + "/webhooks",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(bobToken)),
                new ParameterizedTypeReference<PagedResponse<WebhookEndpointResponse>>() {}
        );
        assertThat(bobList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bobList.getBody().items()).isEmpty();
    }
}
