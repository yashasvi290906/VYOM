package com.vyom.service;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyom.entity.Merchant;
import com.vyom.entity.PaymentOrder;
import com.vyom.entity.enums.AttemptStatus;
import com.vyom.entity.enums.OrderStatus;
import com.vyom.repository.OutboxEventRepository;
import com.vyom.repository.PaymentAttemptRepository;
import com.vyom.repository.PaymentOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentOrderServiceTest {

    @Mock
    private PaymentOrderRepository orderRepository;

    @Mock
    private PaymentAttemptRepository attemptRepository;

    @Mock
    private MerchantSecurityService merchantSecurityService;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private IdempotencyService idempotencyService;

    @Test
    @DisplayName("Cancel Order: JsonProcessingException propagates as IllegalStateException to trigger rollback and prevents outbox save")
    void testCancelOrder_SerializationFailure_ThrowsIllegalStateException() throws Exception {
        PaymentOrderService service = new PaymentOrderService(
                orderRepository, attemptRepository, merchantSecurityService,
                outboxEventRepository, objectMapper, idempotencyService);

        UUID merchantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID callerUserId = UUID.randomUUID();

        Merchant merchant = new Merchant();
        merchant.setId(merchantId);
        merchant.setBusinessName("Test Store");

        PaymentOrder order = new PaymentOrder(
                merchantId, "ORD-REF-123", "MCH-ORD-1",
                null, new BigDecimal("100.0000"), "INR", "Test",
                Instant.now().plusSeconds(3600), "{}", callerUserId);
        order.setStatus(OrderStatus.CREATED);

        when(merchantSecurityService.requireMerchant(merchantId)).thenReturn(merchant);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.existsByOrderIdAndStatus(orderId, AttemptStatus.PROCESSING)).thenReturn(false);
        when(orderRepository.save(any(PaymentOrder.class))).thenReturn(order);

        when(objectMapper.writeValueAsString(any())).thenThrow(new JsonParseException(null, "Simulated Jackson failure"));

        assertThatThrownBy(() -> service.cancelOrder(merchantId, orderId, "Cancellation reason", callerUserId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to serialize order cancellation outbox payload")
                .hasCauseInstanceOf(JsonProcessingException.class);

        verify(outboxEventRepository, never()).save(any());
    }
}
