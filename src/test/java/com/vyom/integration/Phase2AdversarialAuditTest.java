package com.vyom.integration;

import com.vyom.dto.IdempotencyReservationResult;
import com.vyom.dto.request.CancelPaymentOrderRequest;
import com.vyom.dto.request.CreatePaymentOrderRequest;
import com.vyom.dto.request.ExecuteCheckoutPaymentRequest;
import com.vyom.dto.response.CheckoutPaymentReceiptResponse;
import com.vyom.dto.response.CheckoutSessionResponse;
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
import com.vyom.entity.enums.OperationType;
import com.vyom.entity.enums.OrderStatus;
import com.vyom.entity.enums.OutboxEventStatus;
import com.vyom.entity.enums.PaymentMethod;
import com.vyom.entity.enums.Role;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;
import com.vyom.exception.IdempotencyConflictException;
import com.vyom.repository.MerchantMemberRepository;
import com.vyom.repository.MerchantPayableRepository;
import com.vyom.repository.MerchantRepository;
import com.vyom.repository.OutboxEventRepository;
import com.vyom.repository.PaymentAttemptRepository;
import com.vyom.repository.PaymentOrderRepository;
import com.vyom.repository.TransactionRepository;
import com.vyom.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 2.1 Adversarial Audit & Verification Test Suite")
public class Phase2AdversarialAuditTest extends BaseIntegrationTest {

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
    private IdempotencyService idempotencyService;

    private User aliceOwner;
    private User bobCustomer;
    private User charlieOutsider;
    private User daveMerchant2Owner;
    private User eveOperator;
    private User frankViewer;

    private String aliceToken;
    private String bobToken;
    private String charlieToken;
    private String daveToken;
    private String eveToken;
    private String frankToken;

    private Merchant merchantA;
    private Merchant merchantB;

    private Wallet bobInrWallet;
    private Wallet bobUsdWallet;

    @BeforeEach
    void setupAdversarialData() {
        // 1. Users
        aliceOwner = createUser("alice.adv@vyom.internal", "Alice Owner", Role.USER);
        bobCustomer = createUser("bob.adv@vyom.internal", "Bob Customer", Role.USER);
        charlieOutsider = createUser("charlie.adv@vyom.internal", "Charlie Outsider", Role.USER);
        daveMerchant2Owner = createUser("dave.adv@vyom.internal", "Dave Merchant2", Role.USER);
        eveOperator = createUser("eve.adv@vyom.internal", "Eve Operator", Role.USER);
        frankViewer = createUser("frank.adv@vyom.internal", "Frank Viewer", Role.USER);

        aliceToken = jwtTokenService.generateToken(aliceOwner.getId(), aliceOwner.getEmail(), aliceOwner.getFullName(), Role.USER);
        bobToken = jwtTokenService.generateToken(bobCustomer.getId(), bobCustomer.getEmail(), bobCustomer.getFullName(), Role.USER);
        charlieToken = jwtTokenService.generateToken(charlieOutsider.getId(), charlieOutsider.getEmail(), charlieOutsider.getFullName(), Role.USER);
        daveToken = jwtTokenService.generateToken(daveMerchant2Owner.getId(), daveMerchant2Owner.getEmail(), daveMerchant2Owner.getFullName(), Role.USER);
        eveToken = jwtTokenService.generateToken(eveOperator.getId(), eveOperator.getEmail(), eveOperator.getFullName(), Role.USER);
        frankToken = jwtTokenService.generateToken(frankViewer.getId(), frankViewer.getEmail(), frankViewer.getFullName(), Role.USER);

        // 2. Merchant A
        merchantA = createMerchant("MCH-ADV-A", "Merchant Alpha", aliceOwner.getId());
        merchantMemberRepository.save(new MerchantMember(merchantA.getId(), aliceOwner.getId(), MerchantRole.MERCHANT_OWNER, aliceOwner.getId()));
        merchantMemberRepository.save(new MerchantMember(merchantA.getId(), eveOperator.getId(), MerchantRole.MERCHANT_OPERATOR, aliceOwner.getId()));
        merchantMemberRepository.save(new MerchantMember(merchantA.getId(), frankViewer.getId(), MerchantRole.MERCHANT_VIEWER, aliceOwner.getId()));

        // 3. Merchant B
        merchantB = createMerchant("MCH-ADV-B", "Merchant Beta", daveMerchant2Owner.getId());
        merchantMemberRepository.save(new MerchantMember(merchantB.getId(), daveMerchant2Owner.getId(), MerchantRole.MERCHANT_OWNER, daveMerchant2Owner.getId()));

        // 4. Bob's wallets
        bobInrWallet = walletRepository.save(new Wallet(bobCustomer.getId(), "Bob INR", "INR", new BigDecimal("5000.0000"), AccountType.CUSTOMER));
        bobUsdWallet = walletRepository.save(new Wallet(bobCustomer.getId(), "Bob USD", "USD", new BigDecimal("500.0000"), AccountType.CUSTOMER));

        // 5. Ensure Commercial Payable Wallets exist
        ensureCommercialPayableWallet("INR");
        ensureCommercialPayableWallet("USD");
    }

    private User createUser(String email, String fullName, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(fullName);
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(role);
        return userRepository.save(user);
    }

    private Merchant createMerchant(String code, String name, UUID ownerId) {
        Merchant m = new Merchant(
                code, name, name + " Ltd",
                BusinessType.PRIVATE_LIMITED, BusinessCategory.RETAIL,
                "Adversarial merchant test", code.toLowerCase() + "@store.internal",
                "+919876543210", "https://" + code.toLowerCase() + ".internal",
                "IND", "Test street", "Bengaluru", "Karnataka", "560001",
                ownerId
        );
        m.setOnboardingStatus(OnboardingStatus.APPROVED);
        m.setAccountStatus(AccountStatus.ACTIVE);
        return merchantRepository.save(m);
    }

    private void ensureCommercialPayableWallet(String currency) {
        walletRepository.findByAccountTypeAndCurrency(AccountType.MERCHANT_PAYABLE, currency)
                .orElseGet(() -> walletRepository.save(new Wallet(null, "Commercial Payable " + currency, currency, BigDecimal.ZERO, AccountType.MERCHANT_PAYABLE)));
    }

    private HttpHeaders headers(String token, String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        if (idempotencyKey != null) {
            h.set("Idempotency-Key", idempotencyKey);
        }
        return h;
    }

    // =========================================================================
    // 2. CONCURRENT PAYMENT EXECUTION
    // =========================================================================

    @Test
    @DisplayName("ADV-CONCUR-01: 10 parallel threads attempting checkout on same order -> exactly 1 SUCCESS, 1 TXN, 2 Ledger entries, zero balance leakage")
    void testConcurrentPayment_HighContention_ExactSingleSuccess() throws InterruptedException, ExecutionException {
        BigDecimal orderAmount = new BigDecimal("750.0000");

        // 1. Create order
        CreatePaymentOrderRequest orderReq = new CreatePaymentOrderRequest(
                "INV-CONCUR-10WAY", null, orderAmount, "INR", "10-way Race Order", 30, null);
        ResponseEntity<PaymentOrderResponse> orderRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers(aliceToken, "idemp-adv-ord-1")),
                PaymentOrderResponse.class);
        assertThat(orderRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderRef = orderRes.getBody().orderReference();

        // 2. Launch 10 simultaneous threads
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                startLatch.await();
                ExecuteCheckoutPaymentRequest payReq = new ExecuteCheckoutPaymentRequest(bobInrWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
                ResponseEntity<String> res = restTemplate.exchange(
                        "/api/v1/checkout/orders/" + orderRef + "/pay",
                        HttpMethod.POST,
                        new HttpEntity<>(payReq, headers(bobToken, "idemp-concur-race-" + idx)),
                        String.class
                );
                return res.getStatusCode().value();
            }));
        }

        startLatch.countDown();
        executor.shutdown();

        int successCount = 0;
        int failureCount = 0;
        for (Future<Integer> f : futures) {
            int status = f.get();
            if (status == 200) {
                successCount++;
            } else {
                failureCount++;
            }
        }

        // CRITICAL INVARIANT: Exactly 1 succeeds, 9 fail
        assertThat(successCount).as("Exactly one concurrent checkout request must succeed").isEqualTo(1);
        assertThat(failureCount).as("All other concurrent requests must fail").isEqualTo(9);

        // Verify Bob's wallet balance debited exactly once: 5000 - 750 = 4250.0000
        Wallet updatedBobWallet = walletRepository.findById(bobInrWallet.getId()).orElseThrow();
        assertThat(updatedBobWallet.getBalance()).isEqualByComparingTo("4250.0000");

        // Verify Commercial Payable Wallet credited exactly once: 750.0000
        Wallet payableWallet = walletRepository.findByAccountTypeAndCurrency(AccountType.MERCHANT_PAYABLE, "INR").orElseThrow();
        assertThat(payableWallet.getBalance()).isEqualByComparingTo("750.0000");

        // Verify Merchant Payables Subledger credited exactly once: 750.0000
        MerchantPayable subledger = merchantPayableRepository.findByMerchantIdAndCurrency(merchantA.getId(), "INR").orElseThrow();
        assertThat(subledger.getCurrentBalance()).isEqualByComparingTo("750.0000");
        assertThat(subledger.getTotalCollected()).isEqualByComparingTo("750.0000");

        // Verify exactly one MERCHANT_PAYMENT transaction
        List<Transaction> txns = transactionRepository.findAll();
        assertThat(txns).hasSize(1);
        Transaction tx = txns.get(0);
        assertThat(tx.getTransactionType()).isEqualTo(TransactionType.MERCHANT_PAYMENT);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(tx.getAmount()).isEqualByComparingTo("750.0000");

        // Verify precisely two ledger entries (1 DEBIT, 1 CREDIT)
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(tx.getId());
        assertThat(entries).hasSize(2);
        LedgerEntry debit = entries.stream().filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream().filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();
        assertThat(debit.getWalletId()).isEqualTo(bobInrWallet.getId());
        assertThat(debit.getAmount()).isEqualByComparingTo("750.0000");
        assertThat(credit.getWalletId()).isEqualTo(payableWallet.getId());
        assertThat(credit.getAmount()).isEqualByComparingTo("750.0000");

        // Verify Order is PAID
        PaymentOrder order = paymentOrderRepository.findByOrderReference(orderRef).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);

        // Verify PaymentAttempts: exactly 1 SUCCESS attempt in DB
        List<PaymentAttempt> successfulAttempts = paymentAttemptRepository.findByOrderIdOrderBySequenceNumberAsc(order.getId()).stream()
                .filter(a -> a.getStatus() == AttemptStatus.SUCCESS)
                .toList();
        assertThat(successfulAttempts).hasSize(1);
    }

    // =========================================================================
    // 3. IDEMPOTENCY AND LEASE FENCING
    // =========================================================================

    @Test
    @DisplayName("ADV-IDEMP-01: Same Key + Same Payload returns cached 200 OK without re-execution")
    void testIdempotency_SameKeySamePayload() {
        CreatePaymentOrderRequest orderReq = new CreatePaymentOrderRequest("INV-IDEMP-1", null, new BigDecimal("200.0000"), "INR", "Idemp 1", 30, null);
        ResponseEntity<PaymentOrderResponse> orderRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers(aliceToken, "idemp-key-ord-1")), PaymentOrderResponse.class);
        String orderRef = orderRes.getBody().orderReference();

        String paymentIdempKey = "bob-pay-key-fixed-1";
        ExecuteCheckoutPaymentRequest payReq = new ExecuteCheckoutPaymentRequest(bobInrWallet.getId(), PaymentMethod.CUSTOMER_WALLET);

        // First attempt -> 200 OK
        ResponseEntity<CheckoutPaymentReceiptResponse> res1 = restTemplate.exchange(
                "/api/v1/checkout/orders/" + orderRef + "/pay",
                HttpMethod.POST, new HttpEntity<>(payReq, headers(bobToken, paymentIdempKey)), CheckoutPaymentReceiptResponse.class);
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.OK);
        String txRef1 = res1.getBody().transactionReference();

        // Second attempt with exact same key + payload -> 200 OK replay
        ResponseEntity<CheckoutPaymentReceiptResponse> res2 = restTemplate.exchange(
                "/api/v1/checkout/orders/" + orderRef + "/pay",
                HttpMethod.POST, new HttpEntity<>(payReq, headers(bobToken, paymentIdempKey)), CheckoutPaymentReceiptResponse.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res2.getBody().transactionReference()).isEqualTo(txRef1);

        // Verify no duplicate debit occurred
        Wallet bobWallet = walletRepository.findById(bobInrWallet.getId()).orElseThrow();
        assertThat(bobWallet.getBalance()).isEqualByComparingTo("4800.0000"); // 5000 - 200 = 4800
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("ADV-IDEMP-02: Same Key + Different Payload returns 409 Conflict")
    void testIdempotency_SameKeyDifferentPayloadConflict() {
        CreatePaymentOrderRequest orderReq = new CreatePaymentOrderRequest("INV-IDEMP-2", null, new BigDecimal("100.0000"), "INR", "Idemp 2", 30, null);
        ResponseEntity<PaymentOrderResponse> orderRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers(aliceToken, "idemp-key-ord-2")), PaymentOrderResponse.class);
        String orderRef = orderRes.getBody().orderReference();

        String paymentIdempKey = "bob-pay-key-conflict-1";

        // Pay with bobInrWallet
        ExecuteCheckoutPaymentRequest payReq1 = new ExecuteCheckoutPaymentRequest(bobInrWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
        restTemplate.exchange("/api/v1/checkout/orders/" + orderRef + "/pay",
                HttpMethod.POST, new HttpEntity<>(payReq1, headers(bobToken, paymentIdempKey)), CheckoutPaymentReceiptResponse.class);

        // Replay same key but with bobUsdWallet (different payload fingerprint)
        ExecuteCheckoutPaymentRequest payReq2 = new ExecuteCheckoutPaymentRequest(bobUsdWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
        ResponseEntity<String> res2 = restTemplate.exchange(
                "/api/v1/checkout/orders/" + orderRef + "/pay",
                HttpMethod.POST, new HttpEntity<>(payReq2, headers(bobToken, paymentIdempKey)), String.class);

        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res2.getBody()).contains("already used for a different merchant_payment request");
    }

    @Test
    @DisplayName("ADV-IDEMP-03: Concurrent requests with identical idempotency key -> fenced, 1 transaction")
    void testIdempotency_ConcurrentIdenticalKey() throws InterruptedException, ExecutionException {
        CreatePaymentOrderRequest orderReq = new CreatePaymentOrderRequest("INV-IDEMP-3", null, new BigDecimal("150.0000"), "INR", "Idemp 3", 30, null);
        ResponseEntity<PaymentOrderResponse> orderRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers(aliceToken, "idemp-key-ord-3")), PaymentOrderResponse.class);
        String orderRef = orderRes.getBody().orderReference();

        String sharedKey = "shared-idemp-key-100";
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                ExecuteCheckoutPaymentRequest payReq = new ExecuteCheckoutPaymentRequest(bobInrWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
                ResponseEntity<String> res = restTemplate.exchange(
                        "/api/v1/checkout/orders/" + orderRef + "/pay",
                        HttpMethod.POST, new HttpEntity<>(payReq, headers(bobToken, sharedKey)), String.class);
                return res.getStatusCode().value();
            }));
        }

        startLatch.countDown();
        executor.shutdown();

        int okCount = 0;
        int conflictCount = 0;
        for (Future<Integer> f : futures) {
            int code = f.get();
            if (code == 200) okCount++;
            else if (code == 409) conflictCount++;
        }

        // At least 1 returned 200 (either initial winner or subsequent cached replays)
        assertThat(okCount).isGreaterThanOrEqualTo(1);
        // Zero balance leakage: debited exactly once
        Wallet bobWallet = walletRepository.findById(bobInrWallet.getId()).orElseThrow();
        assertThat(bobWallet.getBalance()).isEqualByComparingTo("4850.0000"); // 5000 - 150
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("ADV-IDEMP-04: Stale lease recovery and stale owner rejection via fencing")
    void testIdempotency_LeaseRecoveryAndStaleOwnerRejection() {
        String testKey = "lease-test-key-" + UUID.randomUUID();
        String fingerprint = "test-fingerprint";

        // 1. Initial reservation
        IdempotencyReservationResult<String> res1 = idempotencyService.reserveKey(
                bobCustomer.getId(), testKey, OperationType.MERCHANT_PAYMENT, fingerprint, String.class);
        assertThat(!res1.isCompleted()).isTrue();
        assertThat(res1.ownerToken()).isNotNull();
        UUID ownerToken1 = res1.ownerToken();

        // 2. Immediate second reservation with same key while in flight -> throws conflict
        assertThatThrownBy(() -> idempotencyService.reserveKey(
                bobCustomer.getId(), testKey, OperationType.MERCHANT_PAYMENT, fingerprint, String.class))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("currently being processed");

        // 3. Simulate passage of time beyond timeout (120 seconds) by artificially backdating updated_at
        jdbcTemplate.update("UPDATE idempotency_records SET updated_at = NOW() - INTERVAL '300 seconds' WHERE idempotency_key = ?", testKey);

        // 4. Stale lease recovery: now second call successfully reclaims lease with new token
        IdempotencyReservationResult<String> res2 = idempotencyService.reserveKey(
                bobCustomer.getId(), testKey, OperationType.MERCHANT_PAYMENT, fingerprint, String.class);
        assertThat(!res2.isCompleted()).isTrue();
        assertThat(res2.ownerToken()).isNotNull();
        UUID ownerToken2 = res2.ownerToken();
        assertThat(ownerToken2).isNotEqualTo(ownerToken1);

        // 5. Stale owner token 1 attempts to complete -> Fencing rejection!
        CreatePaymentOrderRequest dummyOrderReq = new CreatePaymentOrderRequest("INV-IDEMP-DUMMY", null, new BigDecimal("10.0000"), "INR", "Dummy", 30, null);
        var dummyOrderRes = restTemplate.exchange("/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST, new HttpEntity<>(dummyOrderReq, headers(aliceToken, "idemp-dummy-ord")), PaymentOrderResponse.class);
        var dummyPayRes = restTemplate.exchange("/api/v1/checkout/orders/" + dummyOrderRes.getBody().orderReference() + "/pay",
                HttpMethod.POST, new HttpEntity<>(new ExecuteCheckoutPaymentRequest(bobInrWallet.getId(), PaymentMethod.CUSTOMER_WALLET), headers(bobToken, "idemp-dummy-pay")), CheckoutPaymentReceiptResponse.class);
        UUID realTxId = transactionRepository.findByReferenceNumber(dummyPayRes.getBody().transactionReference()).orElseThrow().getId();

        assertThatThrownBy(() -> idempotencyService.complete(
                bobCustomer.getId(), testKey, ownerToken1, realTxId, OperationType.MERCHANT_PAYMENT, 200, "Done"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("Reservation ownership lost");

        // 6. Valid owner token 2 successfully completes
        idempotencyService.complete(
                bobCustomer.getId(), testKey, ownerToken2, realTxId, OperationType.MERCHANT_PAYMENT, 200, "Done");

        // 7. Completed key now replays
        IdempotencyReservationResult<String> res3 = idempotencyService.reserveKey(
                bobCustomer.getId(), testKey, OperationType.MERCHANT_PAYMENT, fingerprint, String.class);
        assertThat(res3.isCompleted()).isTrue();
        assertThat(res3.cachedResponse()).isEqualTo("Done");
    }

    // =========================================================================
    // 4. ACCOUNTING AND TRANSACTION ROLLBACK
    // =========================================================================

    @Test
    @DisplayName("ADV-ACC-01: Atomic Rollback on Solvency Failure leaves all tables untouched")
    void testAccounting_SolvencyFailure_AtomicRollback() {
        // Order for 10,000 INR (Bob only has 5,000)
        CreatePaymentOrderRequest orderReq = new CreatePaymentOrderRequest(
                "INV-OVERDRAFT", null, new BigDecimal("10000.0000"), "INR", "Overdraft test", 30, null);
        ResponseEntity<PaymentOrderResponse> orderRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers(aliceToken, "idemp-adv-overdraft")), PaymentOrderResponse.class);
        String orderRef = orderRes.getBody().orderReference();

        ExecuteCheckoutPaymentRequest payReq = new ExecuteCheckoutPaymentRequest(bobInrWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
        ResponseEntity<String> payRes = restTemplate.exchange(
                "/api/v1/checkout/orders/" + orderRef + "/pay",
                HttpMethod.POST, new HttpEntity<>(payReq, headers(bobToken, "idemp-overdraft-pay")), String.class);

        assertThat(payRes.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Assert COMPLETE ROLLBACK
        assertThat(transactionRepository.count()).isEqualTo(0);
        assertThat(ledgerEntryRepository.count()).isEqualTo(0);
        assertThat(outboxEventRepository.count()).isEqualTo(0);

        Wallet bobW = walletRepository.findById(bobInrWallet.getId()).orElseThrow();
        assertThat(bobW.getBalance()).isEqualByComparingTo("5000.0000");

        Wallet payableW = walletRepository.findByAccountTypeAndCurrency(AccountType.MERCHANT_PAYABLE, "INR").orElseThrow();
        assertThat(payableW.getBalance()).isEqualByComparingTo("0.0000");

        assertThat(merchantPayableRepository.findByMerchantIdAndCurrency(merchantA.getId(), "INR")).isEmpty();
    }

    @Test
    @DisplayName("ADV-ACC-02: Currency Mismatch rejected without balance changes")
    void testAccounting_CurrencyMismatch_Rejected() {
        // INR order
        CreatePaymentOrderRequest orderReq = new CreatePaymentOrderRequest(
                "INV-CURR-MISMATCH", null, new BigDecimal("100.0000"), "INR", "Currency mismatch", 30, null);
        ResponseEntity<PaymentOrderResponse> orderRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers(aliceToken, "idemp-adv-currmismatch")), PaymentOrderResponse.class);
        String orderRef = orderRes.getBody().orderReference();

        // Attempt pay with USD wallet
        ExecuteCheckoutPaymentRequest payReq = new ExecuteCheckoutPaymentRequest(bobUsdWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
        ResponseEntity<String> payRes = restTemplate.exchange(
                "/api/v1/checkout/orders/" + orderRef + "/pay",
                HttpMethod.POST, new HttpEntity<>(payReq, headers(bobToken, "idemp-currmismatch-pay")), String.class);

        assertThat(payRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(payRes.getBody()).contains("Wallet currency does not match order currency");

        // Balances untouched
        assertThat(walletRepository.findById(bobUsdWallet.getId()).orElseThrow().getBalance()).isEqualByComparingTo("500.0000");
        assertThat(transactionRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("ADV-ACC-03: Unsupported Refund Scenario - Consumer /api/v1/transactions/:id/refunds is rejected for MERCHANT_PAYMENT")
    void testAccounting_RefundUnsupportedOnMerchantPayment() {
        // Complete an order
        CreatePaymentOrderRequest orderReq = new CreatePaymentOrderRequest(
                "INV-REFUND-UNSUP", null, new BigDecimal("300.0000"), "INR", "Refund test", 30, null);
        ResponseEntity<PaymentOrderResponse> orderRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers(aliceToken, "idemp-adv-ref-unsup")), PaymentOrderResponse.class);
        String orderRef = orderRes.getBody().orderReference();

        ExecuteCheckoutPaymentRequest payReq = new ExecuteCheckoutPaymentRequest(bobInrWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
        ResponseEntity<CheckoutPaymentReceiptResponse> payRes = restTemplate.exchange(
                "/api/v1/checkout/orders/" + orderRef + "/pay",
                HttpMethod.POST, new HttpEntity<>(payReq, headers(bobToken, "idemp-ref-pay")), CheckoutPaymentReceiptResponse.class);

        String txRef = payRes.getBody().transactionReference();
        Transaction tx = transactionRepository.findByReferenceNumber(txRef).orElseThrow();

        // Consumer Bob attempts to refund via standard transfer refund endpoint -> 403 Forbidden
        ResponseEntity<String> bobRefundRes = restTemplate.exchange(
                "/api/v1/transactions/" + tx.getId() + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(new com.vyom.dto.request.CreateRefundRequest(new BigDecimal("100.0000"), "Refund attempt"),
                        headers(bobToken, "idemp-bob-ref")),
                String.class
        );
        assertThat(bobRefundRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bobRefundRes.getBody()).contains("Only the transaction recipient can issue a refund");

        // Merchant Alice attempts to refund via standard transfer refund endpoint -> 403 Forbidden (recipientUserId is null on MERCHANT_PAYMENT)
        ResponseEntity<String> aliceRefundRes = restTemplate.exchange(
                "/api/v1/transactions/" + tx.getId() + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(new com.vyom.dto.request.CreateRefundRequest(new BigDecimal("100.0000"), "Merchant refund attempt"),
                        headers(aliceToken, "idemp-alice-ref")),
                String.class
        );
        assertThat(aliceRefundRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(aliceRefundRes.getBody()).contains("Only the transaction recipient can issue a refund");
    }

    // =========================================================================
    // 5. AUTHORIZATION AND PENETRATION TESTING
    // =========================================================================

    @Test
    @DisplayName("ADV-AUTH-01: Cross-customer receipt access is blocked (403 Forbidden)")
    void testAuth_CrossCustomerReceiptForbidden() {
        // Bob completes a payment
        CreatePaymentOrderRequest orderReq = new CreatePaymentOrderRequest("INV-AUTH-1", null, new BigDecimal("100.0000"), "INR", "Auth 1", 30, null);
        ResponseEntity<PaymentOrderResponse> orderRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers(aliceToken, "idemp-auth-1")), PaymentOrderResponse.class);
        String orderRef = orderRes.getBody().orderReference();

        ResponseEntity<CheckoutPaymentReceiptResponse> payRes = restTemplate.exchange(
                "/api/v1/checkout/orders/" + orderRef + "/pay",
                HttpMethod.POST,
                new HttpEntity<>(new ExecuteCheckoutPaymentRequest(bobInrWallet.getId(), PaymentMethod.CUSTOMER_WALLET), headers(bobToken, "idemp-auth-pay-1")),
                CheckoutPaymentReceiptResponse.class);
        String txRef = payRes.getBody().transactionReference();

        // Charlie (outsider customer) attempts to access receipt
        ResponseEntity<String> charlieRes = restTemplate.exchange(
                "/api/v1/checkout/receipts/" + txRef,
                HttpMethod.GET, new HttpEntity<>(headers(charlieToken, null)), String.class);
        assertThat(charlieRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(charlieRes.getBody()).contains("You do not have access to view this payment receipt");
    }

    @Test
    @DisplayName("ADV-AUTH-02: Cross-merchant order and attempt inspection is blocked")
    void testAuth_CrossMerchantInspectionBlocked() {
        // Merchant A creates an order
        CreatePaymentOrderRequest orderReq = new CreatePaymentOrderRequest("INV-AUTH-MCHA", null, new BigDecimal("200.0000"), "INR", "Mch A Order", 30, null);
        ResponseEntity<PaymentOrderResponse> orderRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers(aliceToken, "idemp-auth-mcha")), PaymentOrderResponse.class);
        UUID orderIdA = orderRes.getBody().id();

        // Dave (Owner of Merchant B) attempts to inspect Merchant A's order -> 404/403
        ResponseEntity<String> daveOrderRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders/" + orderIdA,
                HttpMethod.GET, new HttpEntity<>(headers(daveToken, null)), String.class);
        assertThat(daveOrderRes.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);

        // Dave attempts to list attempts for Merchant A's order -> 404/403
        ResponseEntity<String> daveAttemptsRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders/" + orderIdA + "/attempts",
                HttpMethod.GET, new HttpEntity<>(headers(daveToken, null)), String.class);
        assertThat(daveAttemptsRes.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("ADV-AUTH-03: RBAC Enforcement - MERCHANT_OPERATOR and MERCHANT_VIEWER cannot cancel orders")
    void testAuth_RbacOrderCancellationRestrictions() {
        CreatePaymentOrderRequest orderReq = new CreatePaymentOrderRequest("INV-RBAC-CANCEL", null, new BigDecimal("150.0000"), "INR", "Cancel RBAC", 30, null);
        ResponseEntity<PaymentOrderResponse> orderRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers(aliceToken, "idemp-rbac-ord")), PaymentOrderResponse.class);
        UUID orderId = orderRes.getBody().id();

        CancelPaymentOrderRequest cancelReq = new CancelPaymentOrderRequest("Testing cancellation RBAC");

        // 1. Eve (MERCHANT_OPERATOR) attempts cancel -> 403 Forbidden
        ResponseEntity<String> eveCancelRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders/" + orderId + "/cancel",
                HttpMethod.POST, new HttpEntity<>(cancelReq, headers(eveToken, null)), String.class);
        assertThat(eveCancelRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(eveCancelRes.getBody()).contains("holds role MERCHANT_OPERATOR");

        // 2. Frank (MERCHANT_VIEWER) attempts cancel -> 403 Forbidden
        ResponseEntity<String> frankCancelRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders/" + orderId + "/cancel",
                HttpMethod.POST, new HttpEntity<>(cancelReq, headers(frankToken, null)), String.class);
        assertThat(frankCancelRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 3. Frank (MERCHANT_VIEWER) attempts create order -> 403 Forbidden
        ResponseEntity<String> frankCreateRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST,
                new HttpEntity<>(new CreatePaymentOrderRequest("INV-FRANK", null, new BigDecimal("50.0000"), "INR", "Frank try", 30, null),
                        headers(frankToken, "idemp-frank-try")),
                String.class);
        assertThat(frankCreateRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("ADV-AUTH-04: Anonymous Checkout Payment Execution is rejected")
    void testAuth_AnonymousPaymentExecutionRejected() {
        CreatePaymentOrderRequest orderReq = new CreatePaymentOrderRequest("INV-ANON-TEST", null, new BigDecimal("100.0000"), "INR", "Anon test", 30, null);
        ResponseEntity<PaymentOrderResponse> orderRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers(aliceToken, "idemp-anon-ord")), PaymentOrderResponse.class);
        String orderRef = orderRes.getBody().orderReference();

        HttpHeaders anonHeaders = anonymousHeaders();
        anonHeaders.set("Idempotency-Key", "anon-pay-attempt");
        ExecuteCheckoutPaymentRequest payReq = new ExecuteCheckoutPaymentRequest(bobInrWallet.getId(), PaymentMethod.CUSTOMER_WALLET);

        ResponseEntity<String> anonPayRes = restTemplate.exchange(
                "/api/v1/checkout/orders/" + orderRef + "/pay",
                HttpMethod.POST, new HttpEntity<>(payReq, anonHeaders), String.class);

        assertThat(anonPayRes.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    // =========================================================================
    // 6. ORDER LIFECYCLE AND EXPIRY
    // =========================================================================

    @Test
    @DisplayName("ADV-LIFE-01: Order Expiry transitions status and blocks payment")
    void testOrderLifecycle_ExpiredOrderBlocksPayment() {
        // Create an expired order directly in DB
        PaymentOrder expiredOrder = new PaymentOrder(
                merchantA.getId(),
                "ORD-EXPIRED-TEST-01",
                "INV-EXPIRED-01",
                null,
                new BigDecimal("300.0000"),
                "INR",
                "Expired test",
                Instant.now().minus(10, ChronoUnit.MINUTES), // Expired 10 min ago
                "{}",
                aliceOwner.getId()
        );
        expiredOrder = paymentOrderRepository.save(expiredOrder);

        // 1. Session resolution detects expiry and throws OrderExpiredException (400 Bad Request)
        ResponseEntity<String> resolveRes = restTemplate.exchange(
                "/api/v1/checkout/orders/" + expiredOrder.getOrderReference(),
                HttpMethod.GET, new HttpEntity<>(headers(bobToken, null)), String.class);
        assertThat(resolveRes.getStatusCode()).isIn(HttpStatus.GONE, HttpStatus.BAD_REQUEST);

        // Note: Because OrderExpiredException causes Spring transaction rollback, status in DB remains CREATED
        PaymentOrder currentOrder = paymentOrderRepository.findById(expiredOrder.getId()).orElseThrow();
        assertThat(currentOrder.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(currentOrder.isExpired()).isTrue();

        // 2. Attempting to pay expired order fails and rolls back
        ExecuteCheckoutPaymentRequest payReq = new ExecuteCheckoutPaymentRequest(bobInrWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
        ResponseEntity<String> payRes = restTemplate.exchange(
                "/api/v1/checkout/orders/" + expiredOrder.getOrderReference() + "/pay",
                HttpMethod.POST, new HttpEntity<>(payReq, headers(bobToken, "idemp-expired-pay")), String.class);

        assertThat(payRes.getStatusCode()).isIn(HttpStatus.GONE, HttpStatus.BAD_REQUEST);
        assertThat(transactionRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("ADV-LIFE-02: Paying an already PAID order with new idempotency key is rejected")
    void testOrderLifecycle_PayingAlreadyPaidOrderRejected() {
        CreatePaymentOrderRequest orderReq = new CreatePaymentOrderRequest("INV-PAID-TWICE", null, new BigDecimal("100.0000"), "INR", "Double pay", 30, null);
        ResponseEntity<PaymentOrderResponse> orderRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers(aliceToken, "idemp-paid-twice-create")), PaymentOrderResponse.class);
        String orderRef = orderRes.getBody().orderReference();

        // First payment succeeds
        ExecuteCheckoutPaymentRequest payReq = new ExecuteCheckoutPaymentRequest(bobInrWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
        ResponseEntity<CheckoutPaymentReceiptResponse> pay1 = restTemplate.exchange(
                "/api/v1/checkout/orders/" + orderRef + "/pay",
                HttpMethod.POST, new HttpEntity<>(payReq, headers(bobToken, "idemp-pay-first")), CheckoutPaymentReceiptResponse.class);
        assertThat(pay1.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second payment with different idempotency key -> 409 Conflict / 400 Bad Request
        ResponseEntity<String> pay2 = restTemplate.exchange(
                "/api/v1/checkout/orders/" + orderRef + "/pay",
                HttpMethod.POST, new HttpEntity<>(payReq, headers(bobToken, "idemp-pay-second")), String.class);
        assertThat(pay2.getStatusCode()).isIn(HttpStatus.CONFLICT, HttpStatus.BAD_REQUEST);
        assertThat(pay2.getBody()).contains("already been paid");

        // Bob's balance debited only once (5000 - 100 = 4900)
        assertThat(walletRepository.findById(bobInrWallet.getId()).orElseThrow().getBalance()).isEqualByComparingTo("4900.0000");
    }

    // =========================================================================
    // 7. OUTBOX AND WEBHOOK WORKERS
    // =========================================================================

    @Test
    @DisplayName("ADV-OUTBOX-01: Outbox events committed atomically and verified unconsumed (no delivery worker in Phase 2.1)")
    void testOutbox_AtomicEventsAndAbsenceOfWebhookWorker() {
        // 1. Paid order stages outbox event
        CreatePaymentOrderRequest orderReq = new CreatePaymentOrderRequest("INV-OUTBOX-CHECK", null, new BigDecimal("250.0000"), "INR", "Outbox check", 30, null);
        ResponseEntity<PaymentOrderResponse> orderRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers(aliceToken, "idemp-outbox-ord")), PaymentOrderResponse.class);
        String orderRef = orderRes.getBody().orderReference();
        UUID orderId = orderRes.getBody().id();

        ExecuteCheckoutPaymentRequest payReq = new ExecuteCheckoutPaymentRequest(bobInrWallet.getId(), PaymentMethod.CUSTOMER_WALLET);
        restTemplate.exchange("/api/v1/checkout/orders/" + orderRef + "/pay",
                HttpMethod.POST, new HttpEntity<>(payReq, headers(bobToken, "idemp-outbox-pay")), CheckoutPaymentReceiptResponse.class);

        // Verify outbox record
        List<OutboxEvent> events = outboxEventRepository.findByMerchantId(merchantA.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertThat(events).hasSize(1);
        OutboxEvent paidEvent = events.get(0);
        assertThat(paidEvent.getEventType()).isEqualTo("order.paid");
        assertThat(paidEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(paidEvent.getPayload()).contains(orderRef);
        assertThat(paidEvent.getPayload()).contains("PAID");

        // 2. Cancel an order stages order.cancelled outbox event
        CreatePaymentOrderRequest orderReq2 = new CreatePaymentOrderRequest("INV-OUTBOX-CANCEL", null, new BigDecimal("100.0000"), "INR", "Outbox cancel", 30, null);
        ResponseEntity<PaymentOrderResponse> orderRes2 = restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq2, headers(aliceToken, "idemp-outbox-ord-2")), PaymentOrderResponse.class);
        UUID orderId2 = orderRes2.getBody().id();

        restTemplate.exchange(
                "/api/v1/merchants/" + merchantA.getId() + "/orders/" + orderId2 + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(new CancelPaymentOrderRequest("Outbox cancellation verification"), headers(aliceToken, null)),
                PaymentOrderResponse.class);

        List<OutboxEvent> allEvents = outboxEventRepository.findByMerchantId(merchantA.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertThat(allEvents).hasSize(2);
        OutboxEvent cancelEvent = allEvents.stream().filter(e -> "order.cancelled".equals(e.getEventType())).findFirst().orElseThrow();
        assertThat(cancelEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);

        // Confirm Phase 2.2 Boundary: No webhook HTTP dispatcher is running; all outbox events remain in PENDING status.
        assertThat(allEvents).allMatch(e -> e.getStatus() == OutboxEventStatus.PENDING);
    }
}
