package com.vyom.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyom.dto.request.CreatePaymentOrderRequest;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.PaymentAttemptResponse;
import com.vyom.dto.response.PaymentOrderResponse;
import com.vyom.dto.response.PaymentOrderSummaryResponse;
import com.vyom.entity.Merchant;
import com.vyom.entity.OutboxEvent;
import com.vyom.entity.PaymentAttempt;
import com.vyom.entity.PaymentOrder;
import com.vyom.entity.enums.AttemptStatus;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.OnboardingStatus;
import com.vyom.entity.enums.OperationType;
import com.vyom.entity.enums.OrderStatus;
import com.vyom.exception.DuplicateMerchantOrderIdException;
import com.vyom.exception.InvalidOrderStateException;
import com.vyom.exception.OrderAlreadyPaidException;
import com.vyom.exception.OrderNotFoundException;
import com.vyom.repository.OutboxEventRepository;
import com.vyom.repository.PaymentAttemptRepository;
import com.vyom.repository.PaymentOrderRepository;
import com.vyom.util.ReferenceGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentOrderService {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrderService.class);
    private static final Set<String> FORBIDDEN_METADATA_KEYS = Set.of("role", "status", "balance", "auth", "token");
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("INR", "USD", "EUR", "GBP");

    private final PaymentOrderRepository orderRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final MerchantSecurityService merchantSecurityService;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;

    @Value("${vyom.gateway.base-url:}")
    private String gatewayBaseUrl;

    @Autowired
    public PaymentOrderService(PaymentOrderRepository orderRepository,
                               PaymentAttemptRepository attemptRepository,
                               MerchantSecurityService merchantSecurityService,
                               OutboxEventRepository outboxEventRepository,
                               ObjectMapper objectMapper,
                               IdempotencyService idempotencyService) {
        this.orderRepository = orderRepository;
        this.attemptRepository = attemptRepository;
        this.merchantSecurityService = merchantSecurityService;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.idempotencyService = idempotencyService;
    }

    public PaymentOrderService(PaymentOrderRepository orderRepository,
                               PaymentAttemptRepository attemptRepository,
                               MerchantSecurityService merchantSecurityService,
                               OutboxEventRepository outboxEventRepository,
                               ObjectMapper objectMapper) {
        this(orderRepository, attemptRepository, merchantSecurityService, outboxEventRepository, objectMapper, null);
    }

    @Transactional
    public PaymentOrderResponse createOrder(UUID merchantId, CreatePaymentOrderRequest req, UUID callerUserId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyService == null) {
            return createOrder(merchantId, req, callerUserId);
        }

        String fingerprint = idempotencyService.computeOrderCreationFingerprint(
                callerUserId, merchantId, req.merchantOrderId(), req.amount(), req.currency());

        var reservation = idempotencyService.reserveKey(
                callerUserId, idempotencyKey, OperationType.ORDER_CREATION, fingerprint, PaymentOrderResponse.class);

        if (reservation.isCompleted()) {
            return reservation.cachedResponse();
        }

        try {
            PaymentOrderResponse response = createOrder(merchantId, req, callerUserId);
            idempotencyService.complete(callerUserId, idempotencyKey, reservation.ownerToken(),
                    response.id(), OperationType.ORDER_CREATION, 201, response);
            return response;
        } catch (Exception ex) {
            idempotencyService.markFailed(callerUserId, idempotencyKey, reservation.ownerToken());
            throw ex;
        }
    }

    @Transactional
    public PaymentOrderResponse createOrder(UUID merchantId, CreatePaymentOrderRequest req, UUID callerUserId) {
        Merchant merchant = merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.requireRole(merchantId, callerUserId,
                Set.of(MerchantRole.MERCHANT_OWNER, MerchantRole.MERCHANT_ADMIN, MerchantRole.MERCHANT_OPERATOR));

        merchantSecurityService.validateOperationalStatus(merchant);

        if (merchant.getOnboardingStatus() != OnboardingStatus.APPROVED) {
            throw new IllegalStateException("Merchant is not APPROVED for commercial payment acceptance (current: "
                    + merchant.getOnboardingStatus() + ")");
        }

        String currency = req.currency().toUpperCase().trim();
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw new IllegalArgumentException("Unsupported currency: " + currency + ". Supported: " + SUPPORTED_CURRENCIES);
        }

        String merchantOrderId = req.merchantOrderId().trim();
        if (orderRepository.existsByMerchantIdAndMerchantOrderId(merchantId, merchantOrderId)) {
            throw new DuplicateMerchantOrderIdException("Merchant order ID '" + merchantOrderId
                    + "' already exists for merchant " + merchantId);
        }

        String metadataJson = sanitizeMetadata(req.metadata());

        int expiryMinutes = req.expiresInMinutes() != null ? req.expiresInMinutes() : 15;
        if (expiryMinutes < 5 || expiryMinutes > 1440) {
            throw new IllegalArgumentException("expiresInMinutes must be between 5 and 1440");
        }
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(expiryMinutes));

        String orderReference = ReferenceGenerator.generateOrderReference();

        PaymentOrder order = new PaymentOrder(
                merchantId,
                orderReference,
                merchantOrderId,
                req.customerReference() != null ? req.customerReference().trim() : null,
                req.amount(),
                currency,
                req.description() != null ? req.description().trim() : null,
                expiresAt,
                metadataJson,
                callerUserId
        );

        order = orderRepository.save(order);
        log.info("Payment order created: orderId={}, ref={}, merchant={}, amount={} {}",
                order.getId(), orderReference, merchantId, order.getAmount(), currency);

        return PaymentOrderResponse.from(order, merchant.getBusinessName(), gatewayBaseUrl);
    }

    @Transactional(readOnly = true)
    public PaymentOrderResponse getOrder(UUID merchantId, UUID orderId, UUID callerUserId) {
        Merchant merchant = merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.requireRole(merchantId, callerUserId,
                Set.of(MerchantRole.MERCHANT_OWNER, MerchantRole.MERCHANT_ADMIN, MerchantRole.MERCHANT_OPERATOR, MerchantRole.MERCHANT_VIEWER));

        PaymentOrder order = orderRepository.findById(orderId)
                .filter(o -> o.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new OrderNotFoundException("Payment order not found: " + orderId));

        return PaymentOrderResponse.from(order, merchant.getBusinessName(), gatewayBaseUrl);
    }

    @Transactional(readOnly = true)
    public PagedResponse<PaymentOrderSummaryResponse> listOrders(UUID merchantId, OrderStatus status, Pageable pageable, UUID callerUserId) {
        merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.requireRole(merchantId, callerUserId,
                Set.of(MerchantRole.MERCHANT_OWNER, MerchantRole.MERCHANT_ADMIN, MerchantRole.MERCHANT_OPERATOR, MerchantRole.MERCHANT_VIEWER));

        Page<PaymentOrder> page = (status != null)
                ? orderRepository.findByMerchantIdAndStatus(merchantId, status, pageable)
                : orderRepository.findByMerchantId(merchantId, pageable);

        List<PaymentOrderSummaryResponse> items = page.getContent().stream()
                .map(PaymentOrderSummaryResponse::from)
                .toList();

        return new PagedResponse<>(
                items,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext()
        );
    }

    @Transactional
    public PaymentOrderResponse cancelOrder(UUID merchantId, UUID orderId, String reason, UUID callerUserId) {
        Merchant merchant = merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.requireRole(merchantId, callerUserId,
                Set.of(MerchantRole.MERCHANT_OWNER, MerchantRole.MERCHANT_ADMIN));

        PaymentOrder order = orderRepository.findByIdForUpdate(orderId)
                .filter(o -> o.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new OrderNotFoundException("Payment order not found: " + orderId));

        if (order.getStatus() == OrderStatus.PAID) {
            throw new OrderAlreadyPaidException("Cannot cancel an order that is already PAID");
        }
        if (order.getStatus().isTerminal()) {
            throw new InvalidOrderStateException("Cannot cancel an order in terminal state: " + order.getStatus());
        }

        if (attemptRepository.existsByOrderIdAndStatus(orderId, AttemptStatus.PROCESSING)) {
            throw new InvalidOrderStateException("Cannot cancel an order with an active in-flight payment attempt");
        }

        order.setStatus(OrderStatus.CANCELLED);
        order = orderRepository.save(order);

        // Stage outbox cancellation notification
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("orderReference", order.getOrderReference());
        payloadMap.put("status", "CANCELLED");
        payloadMap.put("reason", reason != null ? reason : "Cancelled by merchant");

        String eventPayload;
        try {
            eventPayload = objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize order cancellation outbox payload", e);
        }

        outboxEventRepository.save(new OutboxEvent(merchantId, "order.cancelled", "ORDER", order.getId(), eventPayload));

        log.info("Payment order cancelled: orderId={}, ref={}, merchant={}, reason={}",
                orderId, order.getOrderReference(), merchantId, reason);

        return PaymentOrderResponse.from(order, merchant.getBusinessName(), gatewayBaseUrl);
    }

    @Transactional(readOnly = true)
    public List<PaymentAttemptResponse> getOrderAttempts(UUID merchantId, UUID orderId, UUID callerUserId) {
        merchantSecurityService.requireMerchant(merchantId);
        merchantSecurityService.requireRole(merchantId, callerUserId,
                Set.of(MerchantRole.MERCHANT_OWNER, MerchantRole.MERCHANT_ADMIN, MerchantRole.MERCHANT_OPERATOR, MerchantRole.MERCHANT_VIEWER));

        orderRepository.findById(orderId)
                .filter(o -> o.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new OrderNotFoundException("Payment order not found: " + orderId));

        return attemptRepository.findByOrderIdOrderBySequenceNumberAsc(orderId).stream()
                .map(PaymentAttemptResponse::from)
                .toList();
    }

    private String sanitizeMetadata(Map<String, Object> metadataMap) {
        if (metadataMap == null || metadataMap.isEmpty()) {
            return "{}";
        }
        for (String forbiddenKey : FORBIDDEN_METADATA_KEYS) {
            if (metadataMap.containsKey(forbiddenKey)) {
                throw new IllegalArgumentException("Metadata contains forbidden key: " + forbiddenKey);
            }
        }
        try {
            String json = objectMapper.writeValueAsString(metadataMap);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) {
                throw new IllegalArgumentException("Metadata payload exceeds maximum allowed size of 4096 bytes");
            }
            return json;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Metadata could not be serialized to JSON: " + e.getMessage());
        }
    }
}

