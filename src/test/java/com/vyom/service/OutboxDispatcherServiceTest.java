package com.vyom.service;

import com.vyom.entity.MerchantWebhookEndpoint;
import com.vyom.entity.OutboxEvent;
import com.vyom.entity.WebhookDelivery;
import com.vyom.entity.enums.OutboxEventStatus;
import com.vyom.entity.enums.WebhookDeliveryStatus;
import com.vyom.repository.MerchantWebhookEndpointRepository;
import com.vyom.repository.OutboxEventRepository;
import com.vyom.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxDispatcherServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private MerchantWebhookEndpointRepository endpointRepository;

    @Mock
    private WebhookDeliveryRepository deliveryRepository;

    private OutboxDispatcherService dispatcherService;

    private final UUID merchantId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final String payloadJson = "{\"orderId\":\"" + UUID.randomUUID() + "\",\"amount\":150.00,\"currency\":\"INR\"}";

    @BeforeEach
    void setUp() {
        dispatcherService = new OutboxDispatcherService(outboxEventRepository, endpointRepository, deliveryRepository);
    }

    @Test
    @DisplayName("Zero pending events returns 0 without database writes")
    void testDispatchZeroPendingEvents() {
        when(outboxEventRepository.pollPendingEventsForFanout(20)).thenReturn(Collections.emptyList());

        int count = dispatcherService.dispatchPendingEvents(20);

        assertEquals(0, count);
        verify(outboxEventRepository, never()).save(any());
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Outbox event with 0 subscribers transitions to NO_SUBSCRIBERS")
    void testDispatchNoSubscribers() {
        OutboxEvent event = new OutboxEvent(merchantId, "order.paid", "ORDER", UUID.randomUUID(), payloadJson);
        event.setId(eventId);
        event.setStatus(OutboxEventStatus.PENDING);

        when(outboxEventRepository.pollPendingEventsForFanout(20)).thenReturn(List.of(event));
        when(endpointRepository.findActiveSubscriptions(merchantId, "order.paid")).thenReturn(Collections.emptyList());

        int count = dispatcherService.dispatchPendingEvents(20);

        assertEquals(1, count);
        assertEquals(OutboxEventStatus.NO_SUBSCRIBERS, event.getStatus());
        assertNotNull(event.getProcessedAt());

        verify(outboxEventRepository).save(event);
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Outbox event with active subscribers creates deliveries and transitions to DISPATCHED")
    void testDispatchWithSubscribers() {
        OutboxEvent event = new OutboxEvent(merchantId, "order.paid", "ORDER", UUID.randomUUID(), payloadJson);
        event.setId(eventId);
        event.setStatus(OutboxEventStatus.PENDING);

        MerchantWebhookEndpoint ep1 = new MerchantWebhookEndpoint();
        ep1.setId(UUID.randomUUID());
        ep1.setMerchantId(merchantId);
        ep1.setUrl("https://api.merchant.com/webhook1");

        MerchantWebhookEndpoint ep2 = new MerchantWebhookEndpoint();
        ep2.setId(UUID.randomUUID());
        ep2.setMerchantId(merchantId);
        ep2.setUrl("https://api.merchant.com/webhook2");

        when(outboxEventRepository.pollPendingEventsForFanout(20)).thenReturn(List.of(event));
        when(endpointRepository.findActiveSubscriptions(merchantId, "order.paid")).thenReturn(List.of(ep1, ep2));
        when(deliveryRepository.existsByOutboxEventIdAndEndpointIdAndRedeliveryOfIsNull(eq(eventId), any())).thenReturn(false);

        int count = dispatcherService.dispatchPendingEvents(20);

        assertEquals(1, count);
        assertEquals(OutboxEventStatus.DISPATCHED, event.getStatus());
        assertNotNull(event.getProcessedAt());

        ArgumentCaptor<WebhookDelivery> deliveryCaptor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository, times(2)).save(deliveryCaptor.capture());

        List<WebhookDelivery> deliveries = deliveryCaptor.getAllValues();
        assertEquals(2, deliveries.size());

        for (WebhookDelivery d : deliveries) {
            assertEquals(eventId, d.getOutboxEventId());
            assertEquals(merchantId, d.getMerchantId());
            assertEquals(payloadJson, d.getCanonicalPayload()); // Exact byte preservation
            assertEquals(WebhookDeliveryStatus.PENDING, d.getStatus());
            assertEquals(0, d.getAttemptCount());
            assertEquals(5, d.getMaxAttempts());
            assertNull(d.getRedeliveryOf());
        }

        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Fan-out is idempotent and skips delivery creation if delivery already exists")
    void testDispatchIdempotentWhenDeliveryAlreadyExists() {
        OutboxEvent event = new OutboxEvent(merchantId, "order.paid", "ORDER", UUID.randomUUID(), payloadJson);
        event.setId(eventId);
        event.setStatus(OutboxEventStatus.PENDING);

        MerchantWebhookEndpoint ep1 = new MerchantWebhookEndpoint();
        ep1.setId(UUID.randomUUID());
        ep1.setMerchantId(merchantId);

        when(outboxEventRepository.pollPendingEventsForFanout(20)).thenReturn(List.of(event));
        when(endpointRepository.findActiveSubscriptions(merchantId, "order.paid")).thenReturn(List.of(ep1));
        when(deliveryRepository.existsByOutboxEventIdAndEndpointIdAndRedeliveryOfIsNull(eventId, ep1.getId())).thenReturn(true);

        int count = dispatcherService.dispatchPendingEvents(20);

        assertEquals(1, count);
        assertEquals(OutboxEventStatus.DISPATCHED, event.getStatus());
        // No new delivery saved since it already exists
        verify(deliveryRepository, never()).save(any());
        verify(outboxEventRepository).save(event);
    }
}
