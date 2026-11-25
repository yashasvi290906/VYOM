package com.vyom.integration;

import com.vyom.dto.request.CancelPaymentOrderRequest;
import com.vyom.dto.request.CreatePaymentOrderRequest;
import com.vyom.dto.request.ExecuteCheckoutPaymentRequest;
import com.vyom.dto.response.CheckoutPaymentReceiptResponse;
import com.vyom.dto.response.CheckoutSessionResponse;
import com.vyom.dto.response.CustomerPaymentHistoryResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.PaymentAttemptResponse;
import com.vyom.dto.response.PaymentOrderResponse;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Merchant;
import com.vyom.entity.MerchantMember;
import com.vyom.entity.MerchantPayable;
import com.vyom.entity.OutboxEvent;
import com.vyom.entity.PaymentAttempt;
import com.vyom.entity.PaymentOrder;
import com.vyom.entity.Transaction;
import com.vyom.entity.User;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.AccountStatus;
import com.vyom.entity.enums.AccountType;
import com.vyom.entity.enums.AttemptStatus;
import com.vyom.entity.enums.BusinessCategory;
import com.vyom.entity.enums.BusinessType;
import com.vyom.entity.enums.EntryType;
import com.vyom.entity.enums.MembershipStatus;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.OnboardingStatus;
import com.vyom.entity.enums.OrderStatus;
import com.vyom.entity.enums.PaymentMethod;
import com.vyom.entity.enums.Role;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;
import com.vyom.repository.MerchantMemberRepository;
import com.vyom.repository.MerchantPayableRepository;
import com.vyom.repository.MerchantRepository;
import com.vyom.repository.OutboxEventRepository;
import com.vyom.repository.PaymentAttemptRepository;
import com.vyom.repository.PaymentOrderRepository;
import com.vyom.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 2.1: Merchant Commercial Payments, Checkout & Atomic Execution Integration Tests")
public class MerchantPaymentIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MerchantMemberRepository merchantMemberRepository;

    @Autowired
    private MerchantPayableRepository merchantPayableRepository;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User aliceOwner;
    private User bobCustomer;
    private User charlieUnrelated;

    private String aliceToken;
    private String bobToken;
    private String charlieToken;

    private Merchant merchant;
    private Wallet bobWallet;

    @BeforeEach
    void setupTestData() {
        // 1. Setup Alice (Merchant Owner)
        aliceOwner = new User();
        aliceOwner.setEmail("alice.merchant@vyom.internal");
        aliceOwner.setFullName("Alice Merchant");
        aliceOwner.setPasswordHash(passwordEncoder.encode("Password123!"));
        aliceOwner.setRole(Role.USER);
        aliceOwner = userRepository.save(aliceOwner);
        aliceToken = jwtTokenService.generateToken(aliceOwner.getId(), aliceOwner.getEmail(), aliceOwner.getFullName(), Role.USER);

        // 2. Setup Bob (Consumer Payer)
        bobCustomer = new User();
        bobCustomer.setEmail("bob.shopper@vyom.internal");
        bobCustomer.setFullName("Bob Shopper");
        bobCustomer.setPasswordHash(passwordEncoder.encode("Password123!"));
        bobCustomer.setRole(Role.USER);
        bobCustomer = userRepository.save(bobCustomer);
        bobToken = jwtTokenService.generateToken(bobCustomer.getId(), bobCustomer.getEmail(), bobCustomer.getFullName(), Role.USER);

        // 3. Setup Charlie (Third-party User)
        charlieUnrelated = new User();
        charlieUnrelated.setEmail("charlie.outsider@vyom.internal");
        charlieUnrelated.setFullName("Charlie Outsider");
        charlieUnrelated.setPasswordHash(passwordEncoder.encode("Password123!"));
        charlieUnrelated.setRole(Role.USER);
        charlieUnrelated = userRepository.save(charlieUnrelated);
        charlieToken = jwtTokenService.generateToken(charlieUnrelated.getId(), charlieUnrelated.getEmail(), charlieUnrelated.getFullName(), Role.USER);

        // 4. Setup Merchant Entity (Approved & Active)
        merchant = new Merchant(
                "MCH-100200",
                "Acme Retail",
                "Acme Retail Enterprises Pvt Ltd",
                BusinessType.PRIVATE_LIMITED,
                BusinessCategory.RETAIL,
                "Retail general merchandise store",
                "acme@store.internal",
                "+919876543210",
                "https://acme.store.internal",
                "IND",
                "123 MG Road",
                "Bengaluru",
                "Karnataka",
                "560001",
                aliceOwner.getId()
        );
        merchant.setOnboardingStatus(OnboardingStatus.APPROVED);
        merchant.setAccountStatus(AccountStatus.ACTIVE);
        merchant = merchantRepository.save(merchant);

        // 5. Setup Alice Membership as MERCHANT_OWNER
        MerchantMember member = new MerchantMember(merchant.getId(), aliceOwner.getId(), MerchantRole.MERCHANT_OWNER, aliceOwner.getId());
        member.setMembershipStatus(MembershipStatus.ACTIVE);
        merchantMemberRepository.save(member);

        // 6. Setup Bob's Customer Wallet with ₹1000.00 INR
        bobWallet = new Wallet(bobCustomer.getId(), "Bob Shopper", "INR", new BigDecimal("1000.0000"), AccountType.CUSTOMER);
        bobWallet = walletRepository.save(bobWallet);

        // 7. Seed deterministic commercial payable settlement wallet if not present
        Wallet payableWallet = walletRepository.findByAccountTypeAndCurrency(AccountType.MERCHANT_PAYABLE, "INR")
                .orElseGet(() -> {
                    Wallet w = new Wallet(null, "Commercial Payable INR", "INR", BigDecimal.ZERO, AccountType.MERCHANT_PAYABLE);
                    return walletRepository.save(w);
                });
    }

    private HttpHeaders createHeaders(String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    @Test
    @DisplayName("Create Payment Order: Success with order reference generation and verification")
    void testCreatePaymentOrder_Success() {
        CreatePaymentOrderRequest request = new CreatePaymentOrderRequest(
                "INV-2026-001",
                "CUST-99",
                new BigDecimal("250.0000"),
                "INR",
                "E-commerce purchase",
                30,
                null
        );

        HttpHeaders headers = createHeaders(aliceToken, "idemp-create-ord-1");
        HttpEntity<CreatePaymentOrderRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<PaymentOrderResponse> response = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.getId() + "/orders",
                HttpMethod.POST,
                entity,
                PaymentOrderResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        PaymentOrderResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.orderReference()).startsWith("ORD-");
        assertThat(body.merchantOrderId()).isEqualTo("INV-2026-001");
        assertThat(body.amount()).isEqualByComparingTo("250.0000");
        assertThat(body.currency()).isEqualTo("INR");
        assertThat(body.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(body.expiresAt()).isAfter(Instant.now());

        // Verify in DB
        PaymentOrder order = paymentOrderRepository.findByOrderReference(body.orderReference()).orElse(null);
        assertThat(order).isNotNull();
        assertThat(order.getMerchantId()).isEqualTo(merchant.getId());
    }

    @Test
    @DisplayName("Create Payment Order: Idempotency replay returns identical cached order without duplicate DB insert")
    void testCreatePaymentOrder_IdempotencyReplay() {
        CreatePaymentOrderRequest request = new CreatePaymentOrderRequest(
                "INV-2026-002",
                "CUST-100",
                new BigDecimal("350.0000"),
                "INR",
                "Idempotency Test",
                30,
                null
        );

        HttpHeaders headers = createHeaders(aliceToken, "idemp-replay-key-1");
        HttpEntity<CreatePaymentOrderRequest> entity = new HttpEntity<>(request, headers);

        // First call
        ResponseEntity<PaymentOrderResponse> res1 = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.getId() + "/orders",
                HttpMethod.POST,
                entity,
                PaymentOrderResponse.class
        );
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderRef1 = res1.getBody().orderReference();

        // Second call with identical key and payload
        ResponseEntity<PaymentOrderResponse> res2 = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.getId() + "/orders",
                HttpMethod.POST,
                entity,
                PaymentOrderResponse.class
        );
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res2.getBody().orderReference()).isEqualTo(orderRef1);

        // Verify single record in DB
        long count = paymentOrderRepository.countByMerchantId(merchant.getId());
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Create Payment Order: Duplicate merchantOrderId is rejected with 409 Conflict")
    void testCreatePaymentOrder_DuplicateMerchantOrderId_Conflict() {
        CreatePaymentOrderRequest request1 = new CreatePaymentOrderRequest(
                "INV-DUP-TEST",
                "CUST-1",
                new BigDecimal("100.0000"),
                "INR",
                "Initial Order",
                30,
                null
        );

        restTemplate.exchange(
                "/api/v1/merchants/" + merchant.getId() + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(request1, createHeaders(aliceToken, "idemp-dup-1")),
                PaymentOrderResponse.class
        );

        CreatePaymentOrderRequest request2 = new CreatePaymentOrderRequest(
                "INV-DUP-TEST",
                "CUST-2",
                new BigDecimal("200.0000"),
                "INR",
                "Duplicate Order",
                30,
                null
        );

        ResponseEntity<String> res2 = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.getId() + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(request2, createHeaders(aliceToken, "idemp-dup-2")),
                String.class
        );

        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Cancel Order: Order can be cancelled; paying a cancelled order is rejected")
    void testCancelOrder_AndRejectPayment() throws Exception {
        // 1. Create order
        CreatePaymentOrderRequest request = new CreatePaymentOrderRequest(
                "INV-CANCEL-1",
                null,
                new BigDecimal("150.0000"),
                "INR",
                "Cancel Test",
                30,
                null
        );
        ResponseEntity<PaymentOrderResponse> createRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.getId() + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, createHeaders(aliceToken, "idemp-cancel-create")),
                PaymentOrderResponse.class
        );
        PaymentOrderResponse createdOrder = createRes.getBody();

        // 2. Cancel order
        CancelPaymentOrderRequest cancelReq = new CancelPaymentOrderRequest("Out of stock");
        ResponseEntity<PaymentOrderResponse> cancelRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.getId() + "/orders/" + createdOrder.id() + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(cancelReq, createHeaders(aliceToken, null)),
                PaymentOrderResponse.class
        );

        assertThat(cancelRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelRes.getBody().status()).isEqualTo(OrderStatus.CANCELLED);

        // Verify outbox cancellation event
        List<OutboxEvent> cancelEvents = outboxEventRepository.findByMerchantId(merchant.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        OutboxEvent initialCancelEvent = cancelEvents.stream()
                .filter(e -> "order.cancelled".equals(e.getEventType()) && createdOrder.id().equals(e.getAggregateId()))
                .findFirst()
                .orElseThrow();
        assertThat(initialCancelEvent.getStatus()).isEqualTo(com.vyom.entity.enums.OutboxEventStatus.PENDING);
        JsonNode cancelPayload = objectMapper.readTree(initialCancelEvent.getPayload());
        assertThat(cancelPayload.size()).isEqualTo(3);
        assertThat(cancelPayload.get("orderReference").asText()).isEqualTo(createdOrder.orderReference());
        assertThat(cancelPayload.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelPayload.get("reason").asText()).isEqualTo("Out of stock");

        // 3. Attempt payment on cancelled order -> 400 Bad Request
        ExecuteCheckoutPaymentRequest payReq = new ExecuteCheckoutPaymentRequest(bobWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
        ResponseEntity<String> payRes = restTemplate.exchange(
                "/api/v1/checkout/orders/" + createdOrder.orderReference() + "/pay",
                HttpMethod.POST,
                new HttpEntity<>(payReq, createHeaders(bobToken, "idemp-pay-cancelled")),
                String.class
        );

        assertThat(payRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @MethodSource("cancellationReasonProvider")
    @DisplayName("Cancel Order: Outbox event JSON serialization preserves exact characters and escapes properly")
    void testCancelOrder_OutboxJsonSerialization_ExactReasonPreserved(String inputReason, String expectedReason) throws Exception {
        // 1. Create order
        String merchantOrderId = "INV-ESC-" + UUID.randomUUID().toString().substring(0, 8);
        CreatePaymentOrderRequest request = new CreatePaymentOrderRequest(
                merchantOrderId,
                null,
                new BigDecimal("200.0000"),
                "INR",
                "Escaping cancellation test",
                30,
                null
        );
        ResponseEntity<PaymentOrderResponse> createRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.getId() + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, createHeaders(aliceToken, "idemp-esc-create-" + merchantOrderId)),
                PaymentOrderResponse.class
        );
        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        PaymentOrderResponse createdOrder = createRes.getBody();
        assertThat(createdOrder).isNotNull();

        // 2. Cancel order with the specified reason
        CancelPaymentOrderRequest cancelReq = new CancelPaymentOrderRequest(inputReason);
        ResponseEntity<PaymentOrderResponse> cancelRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.getId() + "/orders/" + createdOrder.id() + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(cancelReq, createHeaders(aliceToken, null)),
                PaymentOrderResponse.class
        );
        assertThat(cancelRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelRes.getBody().status()).isEqualTo(OrderStatus.CANCELLED);

        // 3. Verify OutboxEvent via Repository
        List<OutboxEvent> events = outboxEventRepository.findByMerchantId(merchant.getId(), org.springframework.data.domain.PageRequest.of(0, 50)).getContent();
        OutboxEvent event = events.stream()
                .filter(e -> "order.cancelled".equals(e.getEventType()) && createdOrder.id().equals(e.getAggregateId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected order.cancelled outbox event for order " + createdOrder.id()));

        // Required assertions
        assertThat(event.getEventType()).isEqualTo("order.cancelled");
        assertThat(event.getAggregateType()).isEqualTo("ORDER");
        assertThat(event.getAggregateId()).isEqualTo(createdOrder.id());
        assertThat(event.getMerchantId()).isEqualTo(merchant.getId());
        assertThat(event.getStatus()).isEqualTo(com.vyom.entity.enums.OutboxEventStatus.PENDING);

        // 4. Parse payload using Jackson
        JsonNode rootNode = objectMapper.readTree(event.getPayload());
        assertThat(rootNode.size()).isEqualTo(3);
        assertThat(rootNode.hasNonNull("orderReference")).isTrue();
        assertThat(rootNode.hasNonNull("status")).isTrue();
        assertThat(rootNode.hasNonNull("reason")).isTrue();

        assertThat(rootNode.get("orderReference").asText()).isEqualTo(createdOrder.orderReference());
        assertThat(rootNode.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(rootNode.get("reason").asText()).isEqualTo(expectedReason);

        // 5. Verify PostgreSQL JSONB column persistence directly from database
        String dbPayload = jdbcTemplate.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE id = ?", String.class, event.getId());
        assertThat(dbPayload).isNotNull();
        JsonNode dbRootNode = objectMapper.readTree(dbPayload);
        assertThat(dbRootNode.get("reason").asText()).isEqualTo(expectedReason);
        assertThat(dbRootNode.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(dbRootNode.get("orderReference").asText()).isEqualTo(createdOrder.orderReference());
    }

    static java.util.stream.Stream<Arguments> cancellationReasonProvider() {
        return java.util.stream.Stream.of(
                Arguments.of("Out of stock", "Out of stock"),
                Arguments.of("Customer said \"cancel\"", "Customer said \"cancel\""),
                Arguments.of("C:\\new-folder", "C:\\new-folder"),
                Arguments.of("Line 1\nLine 2", "Line 1\nLine 2"),
                Arguments.of("Column 1\tColumn 2", "Column 1\tColumn 2"),
                Arguments.of("Carriage\rReturn and \b \f control chars", "Carriage\rReturn and \b \f control chars"),
                Arguments.of(null, "Cancelled by merchant")
        );
    }

    @Test
    @DisplayName("Checkout Resolve Order: Customer resolves order reference and sees wallet solvency")
    void testCheckoutResolveOrder_Success() {
        // Create order for ₹500
        CreatePaymentOrderRequest request = new CreatePaymentOrderRequest(
                "INV-RESOLVE-1",
                null,
                new BigDecimal("500.0000"),
                "INR",
                "Checkout Resolve Test",
                30,
                null
        );
        ResponseEntity<PaymentOrderResponse> createRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.getId() + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, createHeaders(aliceToken, "idemp-resolve-create")),
                PaymentOrderResponse.class
        );
        String orderRef = createRes.getBody().orderReference();

        // Bob visits checkout session
        ResponseEntity<CheckoutSessionResponse> resolveRes = restTemplate.exchange(
                "/api/v1/checkout/orders/" + orderRef,
                HttpMethod.GET,
                new HttpEntity<>(createHeaders(bobToken, null)),
                CheckoutSessionResponse.class
        );

        assertThat(resolveRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        CheckoutSessionResponse body = resolveRes.getBody();
        assertThat(body).isNotNull();
        assertThat(body.orderReference()).isEqualTo(orderRef);
        assertThat(body.merchantName()).isEqualTo("Acme Retail");
        assertThat(body.amount()).isEqualByComparingTo("500.0000");
        assertThat(body.availableCustomerWallets()).hasSize(1);
        assertThat(body.availableCustomerWallets().get(0).isSufficient()).isTrue();
    }

    @Test
    @DisplayName("Atomic Payment Execution: Complete flow with ledger invariant check and outbox event")
    void testAtomicPaymentExecution_Success() {
        BigDecimal payAmount = new BigDecimal("400.0000");

        // 1. Create order
        CreatePaymentOrderRequest request = new CreatePaymentOrderRequest(
                "INV-PAY-SUCCESS-1",
                "CUST-BOB",
                payAmount,
                "INR",
                "Electronic Gadgets",
                30,
                null
        );
        ResponseEntity<PaymentOrderResponse> createRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.getId() + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, createHeaders(aliceToken, "idemp-ord-exec-1")),
                PaymentOrderResponse.class
        );
        String orderRef = createRes.getBody().orderReference();

        // 2. Bob executes checkout payment
        ExecuteCheckoutPaymentRequest payRequest = new ExecuteCheckoutPaymentRequest(bobWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
        ResponseEntity<CheckoutPaymentReceiptResponse> payRes = restTemplate.exchange(
                "/api/v1/checkout/orders/" + orderRef + "/pay",
                HttpMethod.POST,
                new HttpEntity<>(payRequest, createHeaders(bobToken, "idemp-pay-exec-1")),
                CheckoutPaymentReceiptResponse.class
        );

        assertThat(payRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        CheckoutPaymentReceiptResponse receipt = payRes.getBody();
        assertThat(receipt).isNotNull();
        assertThat(receipt.orderReference()).isEqualTo(orderRef);
        assertThat(receipt.amount()).isEqualByComparingTo(payAmount);
        assertThat(receipt.status()).isEqualTo("SUCCESS");
        assertThat(receipt.merchantName()).isEqualTo("Acme Retail");

        // 3. Verify Customer Wallet: debited by ₹400 (initial ₹1000 - ₹400 = ₹600)
        Wallet updatedBobWallet = walletRepository.findById(bobWallet.getId()).orElseThrow();
        assertThat(updatedBobWallet.getBalance()).isEqualByComparingTo("600.0000");

        // 4. Verify Commercial Payable Settlement Wallet: credited by ₹400
        Wallet payableWallet = walletRepository.findByAccountTypeAndCurrency(AccountType.MERCHANT_PAYABLE, "INR").orElseThrow();
        assertThat(payableWallet.getBalance()).isEqualByComparingTo("400.0000");

        // 5. Verify Merchant Payables Subledger
        MerchantPayable payableSubledger = merchantPayableRepository.findByMerchantIdAndCurrency(merchant.getId(), "INR").orElseThrow();
        assertThat(payableSubledger.getCurrentBalance()).isEqualByComparingTo("400.0000");
        assertThat(payableSubledger.getTotalCollected()).isEqualByComparingTo("400.0000");

        // 6. Verify Transaction Entity
        Transaction tx = transactionRepository.findByReferenceNumber(receipt.transactionReference()).orElseThrow();
        assertThat(tx.getTransactionType()).isEqualTo(TransactionType.MERCHANT_PAYMENT);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(tx.getSourceWalletId()).isEqualTo(bobWallet.getId());
        assertThat(tx.getDestinationWalletId()).isEqualTo(payableWallet.getId());

        // 7. Verify Exactly Two Ledger Entries (V8 Double-Entry Invariant)
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(tx.getId());
        assertThat(entries).hasSize(2);
        LedgerEntry debitEntry = entries.stream().filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        LedgerEntry creditEntry = entries.stream().filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();
        assertThat(debitEntry.getWalletId()).isEqualTo(bobWallet.getId());
        assertThat(debitEntry.getAmount()).isEqualByComparingTo(payAmount);
        assertThat(creditEntry.getWalletId()).isEqualTo(payableWallet.getId());
        assertThat(creditEntry.getAmount()).isEqualByComparingTo(payAmount);

        // 8. Verify PaymentOrder status is PAID
        PaymentOrder order = paymentOrderRepository.findByOrderReference(orderRef).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);

        // 9. Verify PaymentAttempt status is SUCCESS
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByOrderIdOrderBySequenceNumberAsc(order.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo(AttemptStatus.SUCCESS);
        assertThat(attempts.get(0).getTransactionId()).isEqualTo(tx.getId());

        // 10. Verify OutboxEvent created for webhook dispatch
        List<OutboxEvent> outboxEvents = outboxEventRepository.findByMerchantId(merchant.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertThat(outboxEvents).hasSize(1);
        assertThat(outboxEvents.get(0).getEventType()).isEqualTo("order.paid");
        assertThat(outboxEvents.get(0).getPayload()).contains(orderRef);
    }

    @Test
    @DisplayName("Concurrency Protection: Concurrent double-pay on same order allows exactly one success")
    void testConcurrentPayment_ExactlyOneSucceeds() throws InterruptedException {
        BigDecimal payAmount = new BigDecimal("300.0000");

        // Create order
        CreatePaymentOrderRequest request = new CreatePaymentOrderRequest(
                "INV-CONCURRENCY-1",
                null,
                payAmount,
                "INR",
                "Concurrent Test",
                30,
                null
        );
        ResponseEntity<PaymentOrderResponse> createRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.getId() + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, createHeaders(aliceToken, "idemp-concur-create")),
                PaymentOrderResponse.class
        );
        String orderRef = createRes.getBody().orderReference();

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ExecuteCheckoutPaymentRequest payReq = new ExecuteCheckoutPaymentRequest(bobWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
                    ResponseEntity<String> res = restTemplate.exchange(
                            "/api/v1/checkout/orders/" + orderRef + "/pay",
                            HttpMethod.POST,
                            new HttpEntity<>(payReq, createHeaders(bobToken, "idemp-concur-pay-" + index)),
                            String.class
                    );
                    if (res.getStatusCode() == HttpStatus.OK) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);

        // Verify Bob's wallet was debited exactly once (1000 - 300 = 700)
        Wallet updatedWallet = walletRepository.findById(bobWallet.getId()).orElseThrow();
        assertThat(updatedWallet.getBalance()).isEqualByComparingTo("700.0000");
    }

    @Test
    @DisplayName("Solvency Check: Customer with insufficient funds fails payment")
    void testPaymentExecution_InsufficientFunds_Fails() {
        // Create order for ₹1500 (Bob only has ₹1000)
        CreatePaymentOrderRequest request = new CreatePaymentOrderRequest(
                "INV-INS-FUNDS",
                null,
                new BigDecimal("1500.0000"),
                "INR",
                "Expensive Item",
                30,
                null
        );
        ResponseEntity<PaymentOrderResponse> createRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.getId() + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, createHeaders(aliceToken, "idemp-ins-funds-create")),
                PaymentOrderResponse.class
        );
        String orderRef = createRes.getBody().orderReference();

        ExecuteCheckoutPaymentRequest payReq = new ExecuteCheckoutPaymentRequest(bobWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
        ResponseEntity<String> payRes = restTemplate.exchange(
                "/api/v1/checkout/orders/" + orderRef + "/pay",
                HttpMethod.POST,
                new HttpEntity<>(payReq, createHeaders(bobToken, "idemp-ins-funds-pay")),
                String.class
        );

        assertThat(payRes.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Bob's balance unchanged
        Wallet updatedWallet = walletRepository.findById(bobWallet.getId()).orElseThrow();
        assertThat(updatedWallet.getBalance()).isEqualByComparingTo("1000.0000");

        // Order remains CREATED
        PaymentOrder order = paymentOrderRepository.findByOrderReference(orderRef).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("Suspended Merchant: Payment is blocked if merchant account is SUSPENDED")
    void testPaymentExecution_SuspendedMerchant_Blocked() {
        // Suspend merchant
        merchant.setAccountStatus(AccountStatus.SUSPENDED);
        merchantRepository.save(merchant);

        // Create order in DB directly
        PaymentOrder order = new PaymentOrder(
                merchant.getId(),
                "ORD-SUSPEND-TEST",
                "INV-SUSPEND-1",
                null,
                new BigDecimal("100.0000"),
                "INR",
                "Suspended test",
                Instant.now().plus(1, ChronoUnit.HOURS),
                "{}",
                aliceOwner.getId()
        );
        paymentOrderRepository.save(order);

        ExecuteCheckoutPaymentRequest payReq = new ExecuteCheckoutPaymentRequest(bobWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
        ResponseEntity<String> payRes = restTemplate.exchange(
                "/api/v1/checkout/orders/ORD-SUSPEND-TEST/pay",
                HttpMethod.POST,
                new HttpEntity<>(payReq, createHeaders(bobToken, "idemp-susp-pay")),
                String.class
        );

        assertThat(payRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Receipt & History: Authorization checks enforce receipt confidentiality and customer history")
    void testReceiptAndHistory_AccessControl() {
        // 1. Pay order
        CreatePaymentOrderRequest request = new CreatePaymentOrderRequest(
                "INV-RECEIPT-1",
                null,
                new BigDecimal("200.0000"),
                "INR",
                "Receipt Test",
                30,
                null
        );
        ResponseEntity<PaymentOrderResponse> createRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.getId() + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, createHeaders(aliceToken, "idemp-receipt-create")),
                PaymentOrderResponse.class
        );
        String orderRef = createRes.getBody().orderReference();

        ExecuteCheckoutPaymentRequest payReq = new ExecuteCheckoutPaymentRequest(bobWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
        ResponseEntity<CheckoutPaymentReceiptResponse> payRes = restTemplate.exchange(
                "/api/v1/checkout/orders/" + orderRef + "/pay",
                HttpMethod.POST,
                new HttpEntity<>(payReq, createHeaders(bobToken, "idemp-receipt-pay")),
                CheckoutPaymentReceiptResponse.class
        );
        String txRef = payRes.getBody().transactionReference();

        // 2. Bob retrieves his receipt -> 200 OK
        ResponseEntity<CheckoutPaymentReceiptResponse> bobReceiptRes = restTemplate.exchange(
                "/api/v1/checkout/receipts/" + txRef,
                HttpMethod.GET,
                new HttpEntity<>(createHeaders(bobToken, null)),
                CheckoutPaymentReceiptResponse.class
        );
        assertThat(bobReceiptRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bobReceiptRes.getBody().orderReference()).isEqualTo(orderRef);

        // 3. Charlie (unrelated outsider) attempts to view Bob's receipt -> 403 Forbidden
        ResponseEntity<String> charlieReceiptRes = restTemplate.exchange(
                "/api/v1/checkout/receipts/" + txRef,
                HttpMethod.GET,
                new HttpEntity<>(createHeaders(charlieToken, null)),
                String.class
        );
        assertThat(charlieReceiptRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 4. Bob views payment history -> includes the attempt
        ResponseEntity<PagedResponse<CustomerPaymentHistoryResponse>> historyRes = restTemplate.exchange(
                "/api/v1/checkout/my-payments",
                HttpMethod.GET,
                new HttpEntity<>(createHeaders(bobToken, null)),
                new ParameterizedTypeReference<>() {}
        );
        assertThat(historyRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(historyRes.getBody().items()).isNotEmpty();
        assertThat(historyRes.getBody().items().get(0).orderReference()).isEqualTo(orderRef);
    }
}
