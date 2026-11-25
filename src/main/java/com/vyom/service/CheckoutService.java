package com.vyom.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyom.dto.IdempotencyReservationResult;
import com.vyom.dto.request.ExecuteCheckoutPaymentRequest;
import com.vyom.dto.response.CheckoutPaymentReceiptResponse;
import com.vyom.dto.response.CheckoutSessionResponse;
import com.vyom.dto.response.CustomerPaymentHistoryResponse;
import com.vyom.entity.CheckoutSession;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Merchant;
import com.vyom.entity.OutboxEvent;
import com.vyom.entity.PaymentAttempt;
import com.vyom.entity.PaymentOrder;
import com.vyom.entity.Transaction;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.AttemptStatus;
import com.vyom.entity.enums.CheckoutSessionStatus;
import com.vyom.entity.enums.EntryType;
import com.vyom.entity.enums.OperationType;
import com.vyom.entity.enums.OrderStatus;
import com.vyom.entity.enums.PaymentMethod;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;
import com.vyom.exception.CheckoutSessionNotFoundException;
import com.vyom.exception.CurrencyMismatchException;
import com.vyom.exception.InsufficientFundsException;
import com.vyom.exception.InvalidOrderStateException;
import com.vyom.exception.InvalidTransferException;
import com.vyom.exception.MerchantNotFoundException;
import com.vyom.exception.OrderAlreadyPaidException;
import com.vyom.exception.OrderExpiredException;
import com.vyom.exception.OrderNotFoundException;
import com.vyom.exception.TransactionNotFoundException;
import com.vyom.exception.WalletNotFoundException;
import com.vyom.repository.CheckoutSessionRepository;
import com.vyom.repository.LedgerEntryRepository;
import com.vyom.repository.MerchantMemberRepository;
import com.vyom.repository.MerchantRepository;
import com.vyom.repository.OutboxEventRepository;
import com.vyom.repository.PaymentAttemptRepository;
import com.vyom.repository.PaymentOrderRepository;
import com.vyom.repository.TransactionRepository;
import com.vyom.repository.WalletRepository;
import com.vyom.util.ReferenceGenerator;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final PaymentOrderRepository orderRepository;
    private final CheckoutSessionRepository sessionRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantMemberRepository merchantMemberRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final MerchantPayableService merchantPayableService;
    private final MerchantSecurityService merchantSecurityService;
    private final IdempotencyService idempotencyService;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    public CheckoutService(PaymentOrderRepository orderRepository,
                           CheckoutSessionRepository sessionRepository,
                           PaymentAttemptRepository attemptRepository,
                           MerchantRepository merchantRepository,
                           MerchantMemberRepository merchantMemberRepository,
                           WalletRepository walletRepository,
                           TransactionRepository transactionRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           OutboxEventRepository outboxEventRepository,
                           MerchantPayableService merchantPayableService,
                           MerchantSecurityService merchantSecurityService,
                           IdempotencyService idempotencyService,
                           EntityManager entityManager,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.merchantRepository = merchantRepository;
        this.merchantMemberRepository = merchantMemberRepository;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.merchantPayableService = merchantPayableService;
        this.merchantSecurityService = merchantSecurityService;
        this.idempotencyService = idempotencyService;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.transactionManager = transactionManager;
    }

    @Transactional
    public CheckoutSessionResponse createOrResolveSession(String orderReference, UUID customerUserId) {
        PaymentOrder order = orderRepository.findByOrderReference(orderReference)
                .orElseThrow(() -> new OrderNotFoundException("Payment order not found with reference: " + orderReference));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new InvalidOrderStateException("Order " + orderReference + " has been cancelled");
        }
        if (order.getStatus() == OrderStatus.PAID) {
            throw new OrderAlreadyPaidException("Order " + orderReference + " has already been paid");
        }
        if (order.isExpired()) {
            if (order.getStatus() == OrderStatus.CREATED || order.getStatus() == OrderStatus.PENDING) {
                order.setStatus(OrderStatus.EXPIRED);
                orderRepository.save(order);
            }
            throw new OrderExpiredException("Order " + orderReference + " expired at " + order.getExpiresAt());
        }

        Merchant merchant = merchantRepository.findById(order.getMerchantId())
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + order.getMerchantId()));

        CheckoutSession session = sessionRepository.findByOrderId(order.getId())
                .filter(s -> !s.isExpired() && s.getStatus() == CheckoutSessionStatus.ACTIVE)
                .orElseGet(() -> {
                    String token = ReferenceGenerator.generateSessionToken();
                    CheckoutSession newSession = new CheckoutSession(token, order.getId(), customerUserId, order.getExpiresAt());
                    return sessionRepository.save(newSession);
                });

        if (customerUserId != null && session.getCustomerUserId() == null) {
            session.setCustomerUserId(customerUserId);
            session = sessionRepository.save(session);
        }

        List<CheckoutSessionResponse.CustomerWalletOption> walletOptions = buildCustomerWalletOptions(customerUserId, order);

        return new CheckoutSessionResponse(
                session.getSessionToken(),
                order.getOrderReference(),
                merchant.getId(),
                merchant.getBusinessName(),
                merchant.getMerchantCode(),
                order.getAmount(),
                order.getCurrency(),
                order.getDescription(),
                order.getStatus(),
                order.getExpiresAt(),
                walletOptions,
                session.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public CheckoutSessionResponse getSession(String sessionToken, UUID customerUserId) {
        CheckoutSession session = sessionRepository.findBySessionToken(sessionToken)
                .orElseThrow(() -> new CheckoutSessionNotFoundException("Checkout session not found: " + sessionToken));

        PaymentOrder order = orderRepository.findById(session.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException("Payment order not found for session: " + sessionToken));

        if (session.isExpired() || order.isExpired()) {
            throw new OrderExpiredException("Checkout session or order has expired");
        }

        Merchant merchant = merchantRepository.findById(order.getMerchantId())
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + order.getMerchantId()));

        UUID effectiveUserId = customerUserId != null ? customerUserId : session.getCustomerUserId();
        List<CheckoutSessionResponse.CustomerWalletOption> walletOptions = buildCustomerWalletOptions(effectiveUserId, order);

        return new CheckoutSessionResponse(
                session.getSessionToken(),
                order.getOrderReference(),
                merchant.getId(),
                merchant.getBusinessName(),
                merchant.getMerchantCode(),
                order.getAmount(),
                order.getCurrency(),
                order.getDescription(),
                order.getStatus(),
                order.getExpiresAt(),
                walletOptions,
                session.getCreatedAt()
        );
    }

    public CheckoutPaymentReceiptResponse executePaymentBySessionToken(String sessionToken,
                                                                       ExecuteCheckoutPaymentRequest request,
                                                                       UUID customerUserId,
                                                                       String idempotencyKey) {
        CheckoutSession session = sessionRepository.findBySessionToken(sessionToken)
                .orElseThrow(() -> new CheckoutSessionNotFoundException("Checkout session not found: " + sessionToken));

        PaymentOrder order = orderRepository.findById(session.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException("Order not found for session: " + sessionToken));

        return executePayment(order.getOrderReference(), request, customerUserId, idempotencyKey);
    }

    public CheckoutPaymentReceiptResponse executePayment(String orderReference,
                                                         ExecuteCheckoutPaymentRequest request,
                                                         UUID customerUserId,
                                                         String idempotencyKey) {
        if (customerUserId == null) {
            throw new AccessDeniedException("Full authentication required to execute checkout payment");
        }
        if (request == null || request.customerWalletId() == null) {
            throw new IllegalArgumentException("customerWalletId is required");
        }

        String fingerprint = idempotencyService.computeMerchantPaymentFingerprint(
                customerUserId, orderReference, request.customerWalletId());

        IdempotencyReservationResult<CheckoutPaymentReceiptResponse> reservation =
                idempotencyService.reserveKey(customerUserId, idempotencyKey, OperationType.MERCHANT_PAYMENT,
                        fingerprint, CheckoutPaymentReceiptResponse.class);

        if (reservation.isCompleted()) {
            log.info("Replaying cached checkout payment response for idempotency key: {}", idempotencyKey);
            return reservation.cachedResponse();
        }

        UUID ownerToken = reservation.ownerToken();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        try {
            return tx.execute(status -> executePaymentTransactional(orderReference, request, customerUserId, idempotencyKey, ownerToken));
        } catch (Exception ex) {
            log.warn("Payment execution failed for orderRef={}, marking idempotency failed: {}", orderReference, ex.getMessage());
            try {
                idempotencyService.markFailed(customerUserId, idempotencyKey, ownerToken);
            } catch (Exception markEx) {
                log.error("Could not mark idempotency key failed", markEx);
            }
            throw ex;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public CheckoutPaymentReceiptResponse executePaymentTransactional(String orderReference,
                                                                      ExecuteCheckoutPaymentRequest request,
                                                                      UUID customerUserId,
                                                                      String idempotencyKey,
                                                                      UUID ownerToken) {
        // 1. Acquire exclusive write lock on PaymentOrder
        PaymentOrder order = orderRepository.findByOrderReferenceForUpdate(orderReference)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with reference: " + orderReference));
        entityManager.refresh(order);

        // 2. Validate Order State
        if (order.getStatus() == OrderStatus.PAID) {
            throw new OrderAlreadyPaidException("Order " + orderReference + " has already been paid");
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new InvalidOrderStateException("Order " + orderReference + " is cancelled and cannot be paid");
        }
        if (order.isExpired()) {
            order.setStatus(OrderStatus.EXPIRED);
            orderRepository.save(order);
            throw new OrderExpiredException("Order " + orderReference + " has expired at " + order.getExpiresAt());
        }
        if (order.getStatus() != OrderStatus.CREATED && order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidOrderStateException("Order " + orderReference + " cannot be paid in status: " + order.getStatus());
        }

        // 3. Validate Merchant Operational Status
        Merchant merchant = merchantRepository.findById(order.getMerchantId())
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + order.getMerchantId()));
        merchantSecurityService.validateOperationalStatus(merchant);

        // 4. Create Payment Attempt in PROCESSING state
        int nextSeq = attemptRepository.findTopByOrderIdOrderBySequenceNumberDesc(order.getId())
                .map(a -> a.getSequenceNumber() + 1)
                .orElse(1);
        String attemptRef = ReferenceGenerator.generateAttemptReference();
        PaymentMethod paymentMethod = request.paymentMethod() != null ? request.paymentMethod() : PaymentMethod.CUSTOMER_WALLET;

        PaymentAttempt attempt = new PaymentAttempt(
                order.getId(),
                attemptRef,
                nextSeq,
                customerUserId,
                request.customerWalletId(),
                paymentMethod
        );
        attempt.setStatus(AttemptStatus.PROCESSING);
        attempt = attemptRepository.saveAndFlush(attempt);

        // 5. Lock Customer Wallet & verify ownership, currency, solvency
        UUID payerWalletId = request.customerWalletId();
        Wallet payerWallet = walletRepository.findByIdForUpdate(payerWalletId)
                .orElseThrow(() -> new WalletNotFoundException(payerWalletId));
        entityManager.refresh(payerWallet);

        if (payerWallet.getUserId() == null || !payerWallet.getUserId().equals(customerUserId)) {
            attempt.setStatus(AttemptStatus.FAILED);
            attempt.setFailureCode("WALLET_UNAUTHORIZED");
            attempt.setFailureReason("User does not own source wallet");
            attempt.setCompletedAt(Instant.now());
            attemptRepository.save(attempt);
            throw new AccessDeniedException("You do not own the selected payment wallet");
        }

        if (payerWallet.isSystemAccount() || payerWallet.isMerchantPayableAccount()) {
            attempt.setStatus(AttemptStatus.FAILED);
            attempt.setFailureCode("INVALID_WALLET_TYPE");
            attempt.setFailureReason("Settlement accounts cannot be used as payment source");
            attempt.setCompletedAt(Instant.now());
            attemptRepository.save(attempt);
            throw new InvalidTransferException("Settlement accounts cannot be used as payment source");
        }

        if (!payerWallet.getCurrency().equalsIgnoreCase(order.getCurrency())) {
            attempt.setStatus(AttemptStatus.FAILED);
            attempt.setFailureCode("CURRENCY_MISMATCH");
            attempt.setFailureReason("Wallet currency (" + payerWallet.getCurrency() + ") does not match order currency (" + order.getCurrency() + ")");
            attempt.setCompletedAt(Instant.now());
            attemptRepository.save(attempt);
            throw new CurrencyMismatchException("Wallet currency does not match order currency");
        }

        BigDecimal amount = order.getAmount().setScale(4);
        if (payerWallet.getBalance().compareTo(amount) < 0) {
            attempt.setStatus(AttemptStatus.FAILED);
            attempt.setFailureCode("INSUFFICIENT_FUNDS");
            attempt.setFailureReason("Insufficient funds in customer wallet");
            attempt.setCompletedAt(Instant.now());
            attemptRepository.save(attempt);
            throw new InsufficientFundsException(payerWallet.getId(), payerWallet.getBalance(), amount);
        }

        // 6. Lock Commercial Payable Wallet
        Wallet payableWallet = merchantPayableService.getCommercialPayableWallet(order.getCurrency());
        payableWallet = walletRepository.findByIdForUpdate(payableWallet.getId())
                .orElseThrow(() -> new WalletNotFoundException("Commercial payable wallet not found for: " + order.getCurrency()));
        entityManager.refresh(payableWallet);

        // 7. Atomic balance mutations
        BigDecimal newPayerBalance = payerWallet.getBalance().subtract(amount);
        payerWallet.setBalance(newPayerBalance);
        walletRepository.save(payerWallet);

        BigDecimal newPayableBalance = payableWallet.getBalance().add(amount);
        payableWallet.setBalance(newPayableBalance);
        walletRepository.save(payableWallet);

        // 8. Update merchant payable subledger
        merchantPayableService.creditPayable(order.getMerchantId(), order.getCurrency(), amount);

        // 9. Create canonical Transaction anchor
        String txRef = ReferenceGenerator.generateTransactionReference();
        Instant completedAt = Instant.now();
        Transaction tx = new Transaction(
                txRef,
                TransactionType.MERCHANT_PAYMENT,
                TransactionStatus.SUCCESS,
                customerUserId,
                null,
                payerWallet.getId(),
                payableWallet.getId(),
                amount,
                order.getCurrency(),
                "Payment for Order " + order.getOrderReference()
        );
        tx.setCompletedAt(completedAt);
        Transaction savedTx = transactionRepository.save(tx);

        // 10. Record exactly two immutable LedgerEntry records (1 DEBIT, 1 CREDIT)
        LedgerEntry debitEntry = LedgerEntry.forTransaction(
                savedTx.getId(),
                payerWallet.getId(),
                EntryType.DEBIT,
                amount,
                newPayerBalance
        );

        LedgerEntry creditEntry = LedgerEntry.forTransaction(
                savedTx.getId(),
                payableWallet.getId(),
                EntryType.CREDIT,
                amount,
                newPayableBalance
        );

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        // 11. Transition Order to PAID
        order.setStatus(OrderStatus.PAID);
        orderRepository.save(order);

        // 12. Transition PaymentAttempt to SUCCESS
        attempt.setStatus(AttemptStatus.SUCCESS);
        attempt.setTransactionId(savedTx.getId());
        attempt.setCompletedAt(completedAt);
        attemptRepository.save(attempt);

        // 13. Transition CheckoutSession to COMPLETED
        sessionRepository.findByOrderId(order.getId()).ifPresent(s -> {
            s.setStatus(CheckoutSessionStatus.COMPLETED);
            s.setCompletedAt(completedAt);
            sessionRepository.save(s);
        });

        // 14. Record persistent Outbox Event for webhook dispatch
        try {
            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("orderId", order.getId().toString());
            payloadMap.put("orderReference", order.getOrderReference());
            payloadMap.put("merchantOrderId", order.getMerchantOrderId());
            payloadMap.put("merchantId", order.getMerchantId().toString());
            payloadMap.put("amount", amount.toPlainString());
            payloadMap.put("currency", order.getCurrency());
            payloadMap.put("status", "PAID");
            payloadMap.put("transactionReference", savedTx.getReferenceNumber());
            payloadMap.put("attemptReference", attempt.getAttemptReference());
            payloadMap.put("paidAt", completedAt.toString());

            String payloadJson = objectMapper.writeValueAsString(payloadMap);
            OutboxEvent outboxEvent = new OutboxEvent(
                    order.getMerchantId(),
                    "order.paid",
                    "ORDER",
                    order.getId(),
                    payloadJson
            );
            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event payload for order {}", order.getId(), e);
        }

        // 15. Build Receipt Response
        String maskedWallet = payerWallet.getId().toString().substring(0, 8) + "-****-****-****-********";
        CheckoutPaymentReceiptResponse response = new CheckoutPaymentReceiptResponse(
                savedTx.getReferenceNumber(),
                order.getOrderReference(),
                attempt.getAttemptReference(),
                savedTx.getReferenceNumber(),
                merchant.getBusinessName(),
                merchant.getMerchantCode(),
                amount,
                order.getCurrency(),
                "SUCCESS",
                maskedWallet,
                completedAt,
                order.getDescription()
        );

        // 16. Record Idempotency completion
        idempotencyService.complete(customerUserId, idempotencyKey, ownerToken, savedTx.getId(),
                OperationType.MERCHANT_PAYMENT, 200, response);

        log.info("Successfully executed commercial checkout payment: order={}, tx={}, attempt={}, amount={} {}",
                order.getOrderReference(), savedTx.getReferenceNumber(), attempt.getAttemptReference(), amount, order.getCurrency());

        return response;
    }

    @Transactional(readOnly = true)
    public Page<CustomerPaymentHistoryResponse> getCustomerPaymentHistory(UUID customerUserId, Pageable pageable) {
        Page<PaymentAttempt> attempts = attemptRepository.findByCustomerUserId(customerUserId, pageable);

        List<CustomerPaymentHistoryResponse> responses = attempts.getContent().stream().map(a -> {
            PaymentOrder order = orderRepository.findById(a.getOrderId()).orElse(null);
            String merchantName = "Unknown Merchant";
            String orderRef = "N/A";
            BigDecimal amount = BigDecimal.ZERO;
            String currency = "INR";

            if (order != null) {
                orderRef = order.getOrderReference();
                amount = order.getAmount();
                currency = order.getCurrency();
                Merchant merchant = merchantRepository.findById(order.getMerchantId()).orElse(null);
                if (merchant != null) {
                    merchantName = merchant.getBusinessName();
                }
            }

            String txRef = null;
            if (a.getTransactionId() != null) {
                txRef = transactionRepository.findById(a.getTransactionId())
                        .map(Transaction::getReferenceNumber)
                        .orElse(null);
            }

            return new CustomerPaymentHistoryResponse(
                    a.getId(),
                    a.getAttemptReference(),
                    orderRef,
                    merchantName,
                    amount,
                    currency,
                    a.getPaymentMethod(),
                    a.getStatus(),
                    txRef,
                    a.getCreatedAt(),
                    a.getCompletedAt()
            );
        }).collect(Collectors.toList());

        return new PageImpl<>(responses, pageable, attempts.getTotalElements());
    }

    @Transactional(readOnly = true)
    public CheckoutPaymentReceiptResponse getReceipt(String referenceNumber, UUID callerUserId, boolean isAdmin) {
        Transaction tx = transactionRepository.findByReferenceNumber(referenceNumber)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found with reference: " + referenceNumber));

        PaymentAttempt attempt = attemptRepository.findByTransactionId(tx.getId())
                .orElseThrow(() -> new TransactionNotFoundException("Commercial payment attempt not found for transaction: " + referenceNumber));

        PaymentOrder order = orderRepository.findById(attempt.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException("Order not found for transaction: " + referenceNumber));

        Merchant merchant = merchantRepository.findById(order.getMerchantId())
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + order.getMerchantId()));

        if (!isAdmin && callerUserId != null) {
            boolean isPayer = callerUserId.equals(attempt.getCustomerUserId());
            boolean isMerchantMember = merchantMemberRepository.findByMerchantIdAndUserId(merchant.getId(), callerUserId)
                    .map(m -> m.isActive()).orElse(false);

            if (!isPayer && !isMerchantMember) {
                throw new AccessDeniedException("You do not have access to view this payment receipt");
            }
        }

        String maskedWallet = attempt.getPayerWalletId().toString().substring(0, 8) + "-****-****-****-********";

        return new CheckoutPaymentReceiptResponse(
                tx.getReferenceNumber(),
                order.getOrderReference(),
                attempt.getAttemptReference(),
                tx.getReferenceNumber(),
                merchant.getBusinessName(),
                merchant.getMerchantCode(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getStatus().name(),
                maskedWallet,
                tx.getCompletedAt(),
                order.getDescription()
        );
    }

    private List<CheckoutSessionResponse.CustomerWalletOption> buildCustomerWalletOptions(UUID customerUserId, PaymentOrder order) {
        if (customerUserId == null) {
            return Collections.emptyList();
        }
        List<Wallet> wallets = walletRepository.findByUserId(customerUserId);
        return wallets.stream()
                .filter(w -> !w.isSystemAccount() && !w.isMerchantPayableAccount())
                .filter(w -> w.getCurrency().equalsIgnoreCase(order.getCurrency()))
                .map(w -> new CheckoutSessionResponse.CustomerWalletOption(
                        w.getId(),
                        w.getCurrency() + " Personal Wallet",
                        w.getCurrency(),
                        w.getBalance(),
                        w.getBalance().compareTo(order.getAmount()) >= 0
                ))
                .collect(Collectors.toList());
    }
}
