package com.vyom.service;

import com.vyom.entity.MerchantWebhookEndpoint;
import com.vyom.entity.WebhookAttemptOutcome;
import com.vyom.entity.WebhookDelivery;
import com.vyom.entity.WebhookDeliveryAttempt;
import com.vyom.entity.enums.WebhookDeliveryStatus;
import com.vyom.repository.MerchantWebhookEndpointRepository;
import com.vyom.repository.WebhookAttemptOutcomeRepository;
import com.vyom.repository.WebhookDeliveryAttemptRepository;
import com.vyom.repository.WebhookDeliveryRepository;
import com.vyom.service.transport.BoundedResponse;
import com.vyom.service.transport.WebhookHttpClientFactory;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookDeliveryServiceTest {

    @Mock
    private WebhookDeliveryRepository deliveryRepository;

    @Mock
    private WebhookDeliveryAttemptRepository attemptRepository;

    @Mock
    private WebhookAttemptOutcomeRepository outcomeRepository;

    @Mock
    private MerchantWebhookEndpointRepository endpointRepository;

    @Mock
    private WebhookHttpClientFactory httpClientFactory;

    @Mock
    private WebhookCryptoService cryptoService;

    @Mock
    private WebhookRetryPolicy retryPolicy;

    private WebhookDeliveryService deliveryService;

    private final UUID deliveryId = UUID.randomUUID();
    private final UUID endpointId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final String payload = "{\"orderId\":\"test-order\",\"status\":\"PAID\"}";
    private final String testUrl = "https://merchant.example.com/webhook";

    @BeforeEach
    void setUp() {
        deliveryService = new WebhookDeliveryService(
                deliveryRepository,
                attemptRepository,
                outcomeRepository,
                endpointRepository,
                httpClientFactory,
                cryptoService,
                retryPolicy
        );
    }

    @Test
    @DisplayName("claimDelivery generates unique token and persists lease successfully")
    void testClaimDeliverySuccess() {
        when(deliveryRepository.claimDelivery(eq(deliveryId), anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(1);

        WebhookDeliveryService.ClaimResult result = deliveryService.claimDelivery(deliveryId);

        assertNotNull(result);
        assertEquals(deliveryId, result.deliveryId());
        assertNotNull(result.claimToken());
        assertTrue(result.lockedUntil().isAfter(Instant.now()));

        verify(deliveryRepository).claimDelivery(eq(deliveryId), eq(result.claimToken()), eq(result.lockedUntil()), any(Instant.class));
    }

    @Test
    @DisplayName("claimDelivery returns null when another worker already claimed the row")
    void testClaimDeliveryCollision() {
        when(deliveryRepository.claimDelivery(eq(deliveryId), anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(0);

        WebhookDeliveryService.ClaimResult result = deliveryService.claimDelivery(deliveryId);

        assertNull(result);
    }

    @Test
    @DisplayName("reserveAttempt creates initial unfinalized attempt record and audits crashed past attempts")
    void testReserveAttemptSuccessWithCrashAudit() {
        String token = UUID.randomUUID().toString();
        WebhookDeliveryService.ClaimResult claim = new WebhookDeliveryService.ClaimResult(deliveryId, token, Instant.now().plusSeconds(60));

        WebhookDelivery delivery = new WebhookDelivery(eventId, endpointId, merchantId, payload);
        delivery.setId(deliveryId);
        delivery.setClaimToken(token);
        delivery.setAttemptCount(1);
        delivery.setMaxAttempts(5);

        // Past attempt 1 had crashed (no outcome row exists)
        UUID pastAttemptId = UUID.randomUUID();
        WebhookDeliveryAttempt pastAttempt = new WebhookDeliveryAttempt(deliveryId, 1, testUrl, 1);
        pastAttempt.setId(pastAttemptId);

        MerchantWebhookEndpoint endpoint = new MerchantWebhookEndpoint();
        endpoint.setId(endpointId);
        endpoint.setUrl(testUrl);
        endpoint.setSecretVersion(2);
        endpoint.setIsActive(true);

        WebhookDeliveryAttempt savedNewAttempt = new WebhookDeliveryAttempt(deliveryId, 2, testUrl, 2);
        savedNewAttempt.setId(attemptId);

        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId)).thenReturn(List.of(pastAttempt));
        when(outcomeRepository.existsByAttemptId(pastAttemptId)).thenReturn(false);
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(deliveryRepository.incrementAttemptCount(eq(deliveryId), eq(token), any(Instant.class))).thenReturn(1);
        when(attemptRepository.save(any(WebhookDeliveryAttempt.class))).thenReturn(savedNewAttempt);

        WebhookDeliveryService.AttemptReservation res = deliveryService.reserveAttempt(claim);

        assertNotNull(res);
        assertEquals(attemptId, res.attemptId());
        assertEquals(2, res.attemptNumber());
        assertEquals(testUrl, res.endpointUrl());
        assertEquals(2, res.secretVersion());

        // Verify crashed attempt was audited by inserting into outcomeRepository
        ArgumentCaptor<WebhookAttemptOutcome> outcomeCaptor = ArgumentCaptor.forClass(WebhookAttemptOutcome.class);
        verify(outcomeRepository).save(outcomeCaptor.capture());
        WebhookAttemptOutcome auditedOutcome = outcomeCaptor.getValue();
        assertEquals(pastAttemptId, auditedOutcome.getAttemptId());
        assertNotNull(auditedOutcome.getErrorMessage());
        assertTrue(auditedOutcome.getErrorMessage().contains("Interrupted"));

        // Verify new attempt was reserved in attemptRepository
        ArgumentCaptor<WebhookDeliveryAttempt> attemptCaptor = ArgumentCaptor.forClass(WebhookDeliveryAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        WebhookDeliveryAttempt newAttempt = attemptCaptor.getValue();
        assertEquals(deliveryId, newAttempt.getDeliveryId());
        assertEquals(2, newAttempt.getAttemptNumber());
    }

    @Test
    @DisplayName("reserveAttempt transitions to DEAD_LETTER when endpoint is archived")
    void testReserveAttemptEndpointArchived() {
        String token = UUID.randomUUID().toString();
        WebhookDeliveryService.ClaimResult claim = new WebhookDeliveryService.ClaimResult(deliveryId, token, Instant.now().plusSeconds(60));

        WebhookDelivery delivery = new WebhookDelivery(eventId, endpointId, merchantId, payload);
        delivery.setId(deliveryId);
        delivery.setClaimToken(token);

        MerchantWebhookEndpoint endpoint = new MerchantWebhookEndpoint();
        endpoint.setId(endpointId);
        endpoint.setArchivedAt(Instant.now());

        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId)).thenReturn(Collections.emptyList());
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        WebhookDeliveryService.AttemptReservation res = deliveryService.reserveAttempt(claim);

        assertNull(res);
        verify(deliveryRepository).completeWithFencing(eq(deliveryId), eq(token), eq(WebhookDeliveryStatus.DEAD_LETTER), isNull(), any(Instant.class));
    }

    @Test
    @DisplayName("reserveAttempt transitions to PAUSED when endpoint is inactive")
    void testReserveAttemptEndpointInactive() {
        String token = UUID.randomUUID().toString();
        WebhookDeliveryService.ClaimResult claim = new WebhookDeliveryService.ClaimResult(deliveryId, token, Instant.now().plusSeconds(60));

        WebhookDelivery delivery = new WebhookDelivery(eventId, endpointId, merchantId, payload);
        delivery.setId(deliveryId);
        delivery.setClaimToken(token);

        MerchantWebhookEndpoint endpoint = new MerchantWebhookEndpoint();
        endpoint.setId(endpointId);
        endpoint.setIsActive(false);

        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId)).thenReturn(Collections.emptyList());
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        WebhookDeliveryService.AttemptReservation res = deliveryService.reserveAttempt(claim);

        assertNull(res);
        verify(deliveryRepository).completeWithFencing(eq(deliveryId), eq(token), eq(WebhookDeliveryStatus.PAUSED), isNull(), any(Instant.class));
    }

    @Test
    @DisplayName("executeHttpCall executes signed HTTP request outside DB transaction")
    void testExecuteHttpCallSuccess() throws Exception {
        String token = UUID.randomUUID().toString();
        WebhookDeliveryService.AttemptReservation res = new WebhookDeliveryService.AttemptReservation(
                attemptId, deliveryId, token, 1, endpointId, testUrl, 1, payload, eventId, merchantId, 5
        );

        MerchantWebhookEndpoint endpoint = new MerchantWebhookEndpoint();
        endpoint.setId(endpointId);
        endpoint.setSecretCiphertext("cipher");
        endpoint.setSecretIv("iv");
        endpoint.setSecretTag("tag");
        endpoint.setMasterKeyVersion(1);

        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(cryptoService.decryptSecret("cipher", "iv", "tag", merchantId, endpointId, 1)).thenReturn("whsec_testsecret123");
        when(cryptoService.signPayload(eq("whsec_testsecret123"), anyLong(), eq(payload))).thenReturn("t=123,v1=abc");
        when(retryPolicy.isSuccess(200)).thenReturn(true);

        BoundedResponse response = new BoundedResponse(200, "OK", Map.of(), "{\"status\":\"received\"}".getBytes(), false, 20);
        when(httpClientFactory.execute(any(ClassicHttpRequest.class))).thenReturn(response);

        WebhookDeliveryService.DeliveryExecutionResult execResult = deliveryService.executeHttpCall(res);

        assertNotNull(execResult);
        assertEquals(attemptId, execResult.attemptId());
        assertEquals(200, execResult.httpStatus());
        assertNull(execResult.errorMessage());
        assertNull(execResult.exception());
        assertTrue(execResult.latencyMs() >= 0);

        ArgumentCaptor<ClassicHttpRequest> reqCaptor = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        verify(httpClientFactory).execute(reqCaptor.capture());

        ClassicHttpRequest sentReq = reqCaptor.getValue();
        assertEquals("POST", sentReq.getMethod());
        assertEquals("t=123,v1=abc", sentReq.getHeader("Vyom-Signature").getValue());
        assertEquals(deliveryId.toString(), sentReq.getHeader("Vyom-Delivery-Id").getValue());
        assertEquals(eventId.toString(), sentReq.getHeader("Vyom-Event-Id").getValue());
    }

    @Test
    @DisplayName("executeHttpCall captures transport exception gracefully without crashing")
    void testExecuteHttpCallException() throws Exception {
        String token = UUID.randomUUID().toString();
        WebhookDeliveryService.AttemptReservation res = new WebhookDeliveryService.AttemptReservation(
                attemptId, deliveryId, token, 1, endpointId, testUrl, 1, payload, eventId, merchantId, 5
        );

        MerchantWebhookEndpoint endpoint = new MerchantWebhookEndpoint();
        endpoint.setId(endpointId);
        endpoint.setSecretCiphertext("cipher");
        endpoint.setSecretIv("iv");
        endpoint.setSecretTag("tag");
        endpoint.setMasterKeyVersion(1);

        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(cryptoService.decryptSecret(any(), any(), any(), any(), any(), anyInt())).thenReturn("secret");
        when(cryptoService.signPayload(any(), anyLong(), any())).thenReturn("sig");
        when(httpClientFactory.execute(any())).thenThrow(new IOException("Connection timed out"));

        WebhookDeliveryService.DeliveryExecutionResult execResult = deliveryService.executeHttpCall(res);

        assertNotNull(execResult);
        assertEquals(attemptId, execResult.attemptId());
        assertNull(execResult.httpStatus());
        assertNotNull(execResult.exception());
        assertTrue(execResult.errorMessage().contains("Connection timed out"));
    }

    @Test
    @DisplayName("recordAttemptOutcome transitions to DELIVERED on 2xx response and writes outcome row")
    void testRecordAttemptOutcomeDelivered() {
        String token = UUID.randomUUID().toString();
        WebhookDeliveryService.DeliveryExecutionResult res = new WebhookDeliveryService.DeliveryExecutionResult(
                attemptId, deliveryId, 1, token, 200, "OK", Map.of(), "ok", 45, null, null
        );

        when(outcomeRepository.existsByAttemptId(attemptId)).thenReturn(false);
        when(retryPolicy.isSuccess(200)).thenReturn(true);
        when(deliveryRepository.completeWithFencing(eq(deliveryId), eq(token), eq(WebhookDeliveryStatus.DELIVERED), isNull(), any(Instant.class)))
                .thenReturn(1);

        boolean outcome = deliveryService.recordAttemptOutcome(res, 5);

        assertTrue(outcome);
        // Verify outcome row was saved
        ArgumentCaptor<WebhookAttemptOutcome> outcomeCaptor = ArgumentCaptor.forClass(WebhookAttemptOutcome.class);
        verify(outcomeRepository).save(outcomeCaptor.capture());
        WebhookAttemptOutcome savedOutcome = outcomeCaptor.getValue();
        assertEquals(attemptId, savedOutcome.getAttemptId());
        assertEquals(200, savedOutcome.getResponseStatus());
        assertEquals(45, savedOutcome.getLatencyMs());

        // Verify attemptRepository was NEVER updated or saved
        verify(attemptRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordAttemptOutcome transitions to RETRY_WAIT when retryable and attempts remain")
    void testRecordAttemptOutcomeRetryWait() {
        String token = UUID.randomUUID().toString();
        WebhookDeliveryService.DeliveryExecutionResult res = new WebhookDeliveryService.DeliveryExecutionResult(
                attemptId, deliveryId, 1, token, 500, "Internal Server Error", Map.of(), "error", 120, "HTTP 500", null
        );

        when(outcomeRepository.existsByAttemptId(attemptId)).thenReturn(false);
        when(retryPolicy.isSuccess(500)).thenReturn(false);
        when(retryPolicy.isRetryable(eq(500), isNull())).thenReturn(true);
        when(retryPolicy.hasExhaustedAttempts(1, 5)).thenReturn(false);

        Instant retryTime = Instant.now().plusSeconds(15);
        when(retryPolicy.calculateNextAttempt(eq(1), eq(Map.of()), any(Instant.class))).thenReturn(retryTime);

        when(deliveryRepository.completeWithFencing(eq(deliveryId), eq(token), eq(WebhookDeliveryStatus.RETRY_WAIT), eq(retryTime), any(Instant.class)))
                .thenReturn(1);

        boolean outcome = deliveryService.recordAttemptOutcome(res, 5);

        assertTrue(outcome);
        ArgumentCaptor<WebhookAttemptOutcome> outcomeCaptor = ArgumentCaptor.forClass(WebhookAttemptOutcome.class);
        verify(outcomeRepository).save(outcomeCaptor.capture());
        WebhookAttemptOutcome savedOutcome = outcomeCaptor.getValue();
        assertEquals(attemptId, savedOutcome.getAttemptId());
        assertEquals(500, savedOutcome.getResponseStatus());
        assertEquals("HTTP 500", savedOutcome.getErrorMessage());

        verify(attemptRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordAttemptOutcome transitions to DEAD_LETTER when maximum attempts reached")
    void testRecordAttemptOutcomeDeadLetterMaxAttempts() {
        String token = UUID.randomUUID().toString();
        WebhookDeliveryService.DeliveryExecutionResult res = new WebhookDeliveryService.DeliveryExecutionResult(
                attemptId, deliveryId, 5, token, 500, "Internal Server Error", Map.of(), "error", 120, "HTTP 500", null
        );

        when(outcomeRepository.existsByAttemptId(attemptId)).thenReturn(false);
        when(retryPolicy.isSuccess(500)).thenReturn(false);
        when(retryPolicy.isRetryable(eq(500), isNull())).thenReturn(true);
        when(retryPolicy.hasExhaustedAttempts(5, 5)).thenReturn(true);

        when(deliveryRepository.completeWithFencing(eq(deliveryId), eq(token), eq(WebhookDeliveryStatus.DEAD_LETTER), isNull(), any(Instant.class)))
                .thenReturn(1);

        boolean outcome = deliveryService.recordAttemptOutcome(res, 5);

        assertTrue(outcome);
        verify(deliveryRepository).completeWithFencing(eq(deliveryId), eq(token), eq(WebhookDeliveryStatus.DEAD_LETTER), isNull(), any(Instant.class));
        verify(outcomeRepository).save(any(WebhookAttemptOutcome.class));
        verify(attemptRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordAttemptOutcome handles fencing token collision safely without throwing")
    void testRecordAttemptOutcomeFencingCollision() {
        String staleToken = UUID.randomUUID().toString();
        WebhookDeliveryService.DeliveryExecutionResult res = new WebhookDeliveryService.DeliveryExecutionResult(
                attemptId, deliveryId, 1, staleToken, 200, "OK", Map.of(), "ok", 45, null, null
        );

        when(outcomeRepository.existsByAttemptId(attemptId)).thenReturn(false);
        when(retryPolicy.isSuccess(200)).thenReturn(true);

        // Fencing update affected 0 rows because lease expired or was superseded by another worker
        when(deliveryRepository.completeWithFencing(eq(deliveryId), eq(staleToken), eq(WebhookDeliveryStatus.DELIVERED), isNull(), any(Instant.class)))
                .thenReturn(0);

        boolean outcome = deliveryService.recordAttemptOutcome(res, 5);

        assertFalse(outcome); // Safely returned false without corrupting state
    }

    @Test
    @DisplayName("pauseDeliveriesForEndpoint and resumeDeliveriesForEndpoint delegate to repository")
    void testPauseAndResumeEndpoint() {
        when(deliveryRepository.pauseDeliveriesForEndpoint(eq(endpointId), any(Instant.class))).thenReturn(3);
        when(deliveryRepository.resumeDeliveriesForEndpoint(eq(endpointId), any(Instant.class))).thenReturn(3);

        int paused = deliveryService.pauseDeliveriesForEndpoint(endpointId);
        int resumed = deliveryService.resumeDeliveriesForEndpoint(endpointId);

        assertEquals(3, paused);
        assertEquals(3, resumed);
    }
}
