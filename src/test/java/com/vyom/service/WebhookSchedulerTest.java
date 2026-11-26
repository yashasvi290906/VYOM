package com.vyom.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Webhook Background Scheduler & Overlap Prevention Tests")
class WebhookSchedulerTest {

    @Mock
    private OutboxDispatcherService dispatcherService;

    @Mock
    private WebhookDeliveryService deliveryService;

    @Mock
    private WebhookMetrics webhookMetrics;

    private WebhookScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new WebhookScheduler(dispatcherService, deliveryService, webhookMetrics);
        ReflectionTestUtils.setField(scheduler, "dispatchBatchSize", 20);
        ReflectionTestUtils.setField(scheduler, "deliveryBatchSize", 20);
    }

    @Test
    @DisplayName("scheduleOutboxDispatch: Success path fanning out events and recording telemetry")
    void scheduleOutboxDispatch_success() {
        when(dispatcherService.dispatchPendingEvents(20)).thenReturn(5);

        scheduler.scheduleOutboxDispatch();

        assertThat(scheduler.getTotalDispatchedEvents()).isEqualTo(5);
        assertThat(scheduler.getConsecutiveFailures()).isEqualTo(0);
        assertThat(scheduler.getLastDispatchTime()).isNotNull();
        assertThat(scheduler.getLastError()).isNull();

        verify(dispatcherService).dispatchPendingEvents(20);
        verify(webhookMetrics).recordSchedulerExecution(eq("dispatch"), eq(true), anyLong());
    }

    @Test
    @DisplayName("scheduleOutboxDispatch: Error path resilience - catches error and audits failure without throwing")
    void scheduleOutboxDispatch_handlesExceptionGracefully() {
        when(dispatcherService.dispatchPendingEvents(20)).thenThrow(new RuntimeException("Database connection timeout"));

        scheduler.scheduleOutboxDispatch();

        assertThat(scheduler.getTotalDispatchedEvents()).isEqualTo(0);
        assertThat(scheduler.getConsecutiveFailures()).isEqualTo(1);
        assertThat(scheduler.getLastError()).isEqualTo("Database connection timeout");

        verify(webhookMetrics).recordSchedulerExecution(eq("dispatch"), eq(false), anyLong());
    }

    @Test
    @DisplayName("scheduleWebhookDelivery: Success path processing due deliveries")
    void scheduleWebhookDelivery_success() {
        when(deliveryService.processDueDeliveries(20)).thenReturn(3);

        scheduler.scheduleWebhookDelivery();

        assertThat(scheduler.getTotalProcessedDeliveries()).isEqualTo(3);
        assertThat(scheduler.getConsecutiveFailures()).isEqualTo(0);
        assertThat(scheduler.getLastDeliveryTime()).isNotNull();

        verify(deliveryService).processDueDeliveries(20);
        verify(webhookMetrics).recordSchedulerExecution(eq("delivery"), eq(true), anyLong());
    }

    @Test
    @DisplayName("scheduleWebhookDelivery: Error path resilience")
    void scheduleWebhookDelivery_handlesExceptionGracefully() {
        when(deliveryService.processDueDeliveries(20)).thenThrow(new IllegalStateException("Pool exhausted"));

        scheduler.scheduleWebhookDelivery();

        assertThat(scheduler.getTotalProcessedDeliveries()).isEqualTo(0);
        assertThat(scheduler.getConsecutiveFailures()).isEqualTo(1);
        assertThat(scheduler.getLastError()).isEqualTo("Pool exhausted");

        verify(webhookMetrics).recordSchedulerExecution(eq("delivery"), eq(false), anyLong());
    }

    @Test
    @DisplayName("Single-instance overlap prevention: Overlapping tick is skipped when previous dispatch is active")
    void dispatch_overlapPrevention() throws Exception {
        AtomicBoolean isDispatching = (AtomicBoolean) ReflectionTestUtils.getField(scheduler, "isDispatching");
        assertThat(isDispatching).isNotNull();

        // Simulate active dispatch
        isDispatching.set(true);

        // Subsequent tick
        scheduler.scheduleOutboxDispatch();

        // Should NOT invoke dispatcher
        verify(dispatcherService, never()).dispatchPendingEvents(anyInt());

        // Reset
        isDispatching.set(false);
    }

    @Test
    @DisplayName("Single-instance overlap prevention: Overlapping tick is skipped when previous delivery is active")
    void delivery_overlapPrevention() throws Exception {
        AtomicBoolean isDelivering = (AtomicBoolean) ReflectionTestUtils.getField(scheduler, "isDelivering");
        assertThat(isDelivering).isNotNull();

        // Simulate active delivery
        isDelivering.set(true);

        // Subsequent tick
        scheduler.scheduleWebhookDelivery();

        // Should NOT invoke deliveryService
        verify(deliveryService, never()).processDueDeliveries(anyInt());

        // Reset
        isDelivering.set(false);
    }

    @Test
    @DisplayName("Graceful shutdown: onShutdown() stops subsequent schedule ticks immediately")
    void onShutdown_abortsTicks() {
        scheduler.onShutdown();

        scheduler.scheduleOutboxDispatch();
        scheduler.scheduleWebhookDelivery();

        verify(dispatcherService, never()).dispatchPendingEvents(anyInt());
        verify(deliveryService, never()).processDueDeliveries(anyInt());
    }
}
