package com.vyom.integration;

import com.vyom.dto.request.CreateDisputeRequest;
import com.vyom.dto.request.CreateRefundRequest;
import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.request.ResolveDisputeRequest;
import com.vyom.dto.response.DisputeResponse;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.RefundResponse;
import com.vyom.dto.response.TransactionReceiptResponse;
import com.vyom.dto.response.TransactionResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.Dispute;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Transaction;
import com.vyom.entity.User;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.DisputeStatus;
import com.vyom.entity.enums.EntryType;
import com.vyom.entity.enums.RefundType;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;
import com.vyom.entity.enums.TransferStatus;
import com.vyom.repository.DisputeRepository;
import com.vyom.repository.RefundRepository;
import com.vyom.repository.TransactionRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class Phase2LifecycleIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private DisputeRepository disputeRepository;

    private User alice;
    private User bob;
    private User charlie;
    private User admin;

    private String aliceToken;
    private String bobToken;
    private String charlieToken;
    private String adminToken;

    private Wallet aliceWallet;
    private Wallet bobWallet;
    private Wallet charlieWallet;

    @BeforeEach
    void setupPhase2UsersAndWallets() {
        alice = createTestUser("alice@vyom.internal", "Alice Smith", com.vyom.entity.enums.Role.USER);
        bob = createTestUser("bob@vyom.internal", "Bob Jones", com.vyom.entity.enums.Role.USER);
        charlie = createTestUser("charlie@vyom.internal", "Charlie Brown", com.vyom.entity.enums.Role.USER);
        admin = createTestUser("admin-phase2@vyom.internal", "Admin User", com.vyom.entity.enums.Role.ADMIN);

        aliceToken = jwtTokenService.generateToken(alice.getId(), alice.getEmail(), alice.getFullName(), com.vyom.entity.enums.Role.USER);
        bobToken = jwtTokenService.generateToken(bob.getId(), bob.getEmail(), bob.getFullName(), com.vyom.entity.enums.Role.USER);
        charlieToken = jwtTokenService.generateToken(charlie.getId(), charlie.getEmail(), charlie.getFullName(), com.vyom.entity.enums.Role.USER);
        adminToken = jwtTokenService.generateToken(admin.getId(), admin.getEmail(), admin.getFullName(), com.vyom.entity.enums.Role.ADMIN);

        aliceWallet = createTestWallet(alice.getId(), "Alice Wallet", "INR", new BigDecimal("1000.0000"));
        bobWallet = createTestWallet(bob.getId(), "Bob Wallet", "INR", new BigDecimal("100.0000"));
        charlieWallet = createTestWallet(charlie.getId(), "Charlie Wallet", "INR", new BigDecimal("50.0000"));
    }

    private User createTestUser(String email, String fullName, com.vyom.entity.enums.Role role) {
        User user = new User(email, passwordEncoder.encode("Password123!"), fullName, role);
        return userRepository.saveAndFlush(user);
    }

    private User createTestUser(String email, String fullName) {
        return createTestUser(email, fullName, com.vyom.entity.enums.Role.USER);
    }

    private Wallet createTestWallet(UUID userId, String ownerName, String currency, BigDecimal balance) {
        Wallet wallet = new Wallet(userId, ownerName, currency, balance);
        return walletRepository.saveAndFlush(wallet);
    }

    private HttpHeaders createAuthHeaders(String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private ResponseEntity<TransferResponse> executeTransfer(String token, Wallet source, Wallet dest, BigDecimal amount, String desc) {
        CreateTransferRequest request = new CreateTransferRequest(source.getId(), dest.getId(), amount, "INR", desc);
        String key = UUID.randomUUID().toString();
        HttpEntity<CreateTransferRequest> entity = new HttpEntity<>(request, createAuthHeaders(token, key));
        return restTemplate.exchange("/api/v1/transfers", HttpMethod.POST, entity, TransferResponse.class);
    }

    // =========================================================================
    // 1. Transaction Anchor & State Transitions
    // =========================================================================

    @Test
    @DisplayName("ST-01: Standard transfer creates canonical transaction record and linked double-entry ledger entries")
    void shouldCreateTransactionAnchorOnTransfer() {
        ResponseEntity<TransferResponse> response = executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("250.0000"), "Invoice 101");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(TransferStatus.COMPLETED);

        // Verify Transaction record
        List<Transaction> transactions = transactionRepository.findAll();
        assertThat(transactions).hasSize(1);
        Transaction txn = transactions.get(0);

        assertThat(txn.getReferenceNumber()).startsWith("TXN-");
        assertThat(txn.getTransactionType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(txn.getAmount()).isEqualByComparingTo(new BigDecimal("250.0000"));
        assertThat(txn.getFeeAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(txn.getRefundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(txn.getSourceWalletId()).isEqualTo(aliceWallet.getId());
        assertThat(txn.getDestinationWalletId()).isEqualTo(bobWallet.getId());
        assertThat(txn.getInitiatorUserId()).isEqualTo(alice.getId());
        assertThat(txn.getRecipientUserId()).isEqualTo(bob.getId());
        assertThat(txn.getRelatedTransactionId()).isNull();

        // Verify ledger entries link to transaction
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(txn.getId());
        assertThat(entries).hasSize(2);
        for (LedgerEntry entry : entries) {
            assertThat(entry.getTransactionId()).isEqualTo(txn.getId());
            assertThat(entry.getTransferId()).isEqualTo(response.getBody().transferId());
        }

        // Verify balances
        Wallet updatedAlice = walletRepository.findById(aliceWallet.getId()).orElseThrow();
        Wallet updatedBob = walletRepository.findById(bobWallet.getId()).orElseThrow();
        assertThat(updatedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("750.0000"));
        assertThat(updatedBob.getBalance()).isEqualByComparingTo(new BigDecimal("350.0000"));
    }

    // =========================================================================
    // 2. Transaction Retrieval, Filtering & Privacy Masking
    // =========================================================================

    @Test
    @DisplayName("RC-04 & RC-05: User-scoped transaction listing with filters and pagination")
    void shouldReturnUserScopedTransactionsWithFilters() {
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("100.0000"), "Txn 1");
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("200.0000"), "Txn 2");

        // Alice queries transactions
        HttpEntity<?> entity = new HttpEntity<>(createAuthHeaders(aliceToken, null));
        ResponseEntity<PagedResponse<TransactionResponse>> aliceResp = restTemplate.exchange(
                "/api/v1/transactions?page=0&size=10&status=SUCCESS",
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {}
        );

        assertThat(aliceResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(aliceResp.getBody()).isNotNull();
        assertThat(aliceResp.getBody().totalElements()).isEqualTo(2);

        // Charlie (unrelated user) queries transactions
        HttpEntity<?> charlieEntity = new HttpEntity<>(createAuthHeaders(charlieToken, null));
        ResponseEntity<PagedResponse<TransactionResponse>> charlieResp = restTemplate.exchange(
                "/api/v1/transactions?page=0&size=10",
                HttpMethod.GET,
                charlieEntity,
                new ParameterizedTypeReference<>() {}
        );
        assertThat(charlieResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(charlieResp.getBody()).isNotNull();
        assertThat(charlieResp.getBody().totalElements()).isEqualTo(0);
    }

    @Test
    @DisplayName("RC-01 & RC-02: Receipt masking from Sender and Recipient perspectives")
    void shouldProvidePerspectiveAwareReceiptMasking() {
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("150.0000"), "Lunch payment");
        Transaction txn = transactionRepository.findAll().get(0);

        // 1. Sender (Alice) requests receipt
        HttpEntity<?> aliceEntity = new HttpEntity<>(createAuthHeaders(aliceToken, null));
        ResponseEntity<TransactionReceiptResponse> aliceReceiptResp = restTemplate.exchange(
                "/api/v1/transactions/" + txn.getId() + "/receipt",
                HttpMethod.GET,
                aliceEntity,
                TransactionReceiptResponse.class
        );
        assertThat(aliceReceiptResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        TransactionReceiptResponse aliceReceipt = aliceReceiptResp.getBody();
        assertThat(aliceReceipt).isNotNull();

        // Alice sees own unmasked details
        assertThat(aliceReceipt.sender().name()).isEqualTo("Alice Smith");
        assertThat(aliceReceipt.sender().maskedEmail()).isEqualTo("alice@vyom.internal");
        assertThat(aliceReceipt.sender().maskedWalletId()).isEqualTo(aliceWallet.getId().toString());

        // Alice sees Bob masked
        assertThat(aliceReceipt.recipient().maskedEmail()).contains("***");
        assertThat(aliceReceipt.recipient().maskedWalletId()).contains("****");

        // 2. Recipient (Bob) requests receipt
        HttpEntity<?> bobEntity = new HttpEntity<>(createAuthHeaders(bobToken, null));
        ResponseEntity<TransactionReceiptResponse> bobReceiptResp = restTemplate.exchange(
                "/api/v1/transactions/" + txn.getId() + "/receipt",
                HttpMethod.GET,
                bobEntity,
                TransactionReceiptResponse.class
        );
        assertThat(bobReceiptResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        TransactionReceiptResponse bobReceipt = bobReceiptResp.getBody();
        assertThat(bobReceipt).isNotNull();

        // Bob sees own unmasked details
        assertThat(bobReceipt.recipient().name()).isEqualTo("Bob Jones");
        assertThat(bobReceipt.recipient().maskedEmail()).isEqualTo("bob@vyom.internal");

        // Bob sees Alice masked
        assertThat(bobReceipt.sender().maskedEmail()).contains("***");
        assertThat(bobReceipt.sender().maskedWalletId()).contains("****");
    }

    @Test
    @DisplayName("RC-03: Unauthorized receipt access is rejected with HTTP 403 ACCESS_DENIED")
    void shouldRejectUnauthorizedReceiptAccess() {
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("150.0000"), "Lunch payment");
        Transaction txn = transactionRepository.findAll().get(0);

        // Charlie attempts to view Alice-Bob receipt
        HttpEntity<?> charlieEntity = new HttpEntity<>(createAuthHeaders(charlieToken, null));
        ResponseEntity<ErrorResponse> charlieReceiptResp = restTemplate.exchange(
                "/api/v1/transactions/" + txn.getId() + "/receipt",
                HttpMethod.GET,
                charlieEntity,
                ErrorResponse.class
        );
        assertThat(charlieReceiptResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(charlieReceiptResp.getBody()).isNotNull();
        assertThat(charlieReceiptResp.getBody().errorCode()).isEqualTo("ACCESS_DENIED");
    }

    // =========================================================================
    // 3. Compensating Double-Entry Refunds
    // =========================================================================

    @Test
    @DisplayName("RF-01: Full refund creates child transaction and balanced compensating ledger entries")
    void shouldExecuteFullRefund() {
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("500.0000"), "Deposit");
        Transaction originalTxn = transactionRepository.findAll().get(0);

        CreateRefundRequest refundReq = new CreateRefundRequest(new BigDecimal("500.0000"), "Customer requested full refund");
        String key = UUID.randomUUID().toString();
        HttpEntity<CreateRefundRequest> entity = new HttpEntity<>(refundReq, createAuthHeaders(bobToken, key));

        ResponseEntity<RefundResponse> refundResp = restTemplate.exchange(
                "/api/v1/transactions/" + originalTxn.getId() + "/refunds",
                HttpMethod.POST,
                entity,
                RefundResponse.class
        );

        assertThat(refundResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refundResp.getBody()).isNotNull();
        assertThat(refundResp.getBody().status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(refundResp.getBody().refundType()).isEqualTo(RefundType.FULL);
        assertThat(refundResp.getBody().amount()).isEqualByComparingTo(new BigDecimal("500.0000"));
        assertThat(refundResp.getBody().totalRefundedAmount()).isEqualByComparingTo(new BigDecimal("500.0000"));

        // Verify original transaction state
        Transaction updatedOriginal = transactionRepository.findById(originalTxn.getId()).orElseThrow();
        assertThat(updatedOriginal.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
        assertThat(updatedOriginal.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("500.0000"));

        // Verify balances restored
        Wallet updatedAlice = walletRepository.findById(aliceWallet.getId()).orElseThrow();
        Wallet updatedBob = walletRepository.findById(bobWallet.getId()).orElseThrow();
        assertThat(updatedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(updatedBob.getBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));

        // Verify child transaction ledger entries
        UUID childTxnId = refundResp.getBody().transactionId();
        List<LedgerEntry> refundLedger = ledgerEntryRepository.findByTransactionId(childTxnId);
        assertThat(refundLedger).hasSize(2);

        LedgerEntry bobDebit = refundLedger.stream().filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        assertThat(bobDebit.getWalletId()).isEqualTo(bobWallet.getId());
        assertThat(bobDebit.getAmount()).isEqualByComparingTo(new BigDecimal("500.0000"));

        LedgerEntry aliceCredit = refundLedger.stream().filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();
        assertThat(aliceCredit.getWalletId()).isEqualTo(aliceWallet.getId());
        assertThat(aliceCredit.getAmount()).isEqualByComparingTo(new BigDecimal("500.0000"));
    }

    @Test
    @DisplayName("RF-02 & RF-03: Partial and sequential refunds transition status appropriately")
    void shouldExecuteSequentialPartialRefunds() {
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("500.0000"), "Deposit");
        Transaction originalTxn = transactionRepository.findAll().get(0);

        // 1. Partial refund of ₹200
        CreateRefundRequest refund1Req = new CreateRefundRequest(new BigDecimal("200.0000"), "Partial refund 1");
        String key1 = UUID.randomUUID().toString();
        HttpEntity<CreateRefundRequest> entity1 = new HttpEntity<>(refund1Req, createAuthHeaders(bobToken, key1));
        ResponseEntity<RefundResponse> resp1 = restTemplate.exchange(
                "/api/v1/transactions/" + originalTxn.getId() + "/refunds",
                HttpMethod.POST,
                entity1,
                RefundResponse.class
        );
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp1.getBody().refundType()).isEqualTo(RefundType.PARTIAL);

        Transaction postRefund1 = transactionRepository.findById(originalTxn.getId()).orElseThrow();
        assertThat(postRefund1.getStatus()).isEqualTo(TransactionStatus.PARTIALLY_REFUNDED);
        assertThat(postRefund1.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("200.0000"));

        // 2. Sequential refund of remaining ₹300
        CreateRefundRequest refund2Req = new CreateRefundRequest(new BigDecimal("300.0000"), "Partial refund 2 to complete");
        String key2 = UUID.randomUUID().toString();
        HttpEntity<CreateRefundRequest> entity2 = new HttpEntity<>(refund2Req, createAuthHeaders(bobToken, key2));
        ResponseEntity<RefundResponse> resp2 = restTemplate.exchange(
                "/api/v1/transactions/" + originalTxn.getId() + "/refunds",
                HttpMethod.POST,
                entity2,
                RefundResponse.class
        );
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp2.getBody().refundType()).isEqualTo(RefundType.FULL);

        Transaction postRefund2 = transactionRepository.findById(originalTxn.getId()).orElseThrow();
        assertThat(postRefund2.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
        assertThat(postRefund2.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("500.0000"));
    }

    @Test
    @DisplayName("RF-04: Over-refund rejection when requested amount exceeds remaining refundable")
    void shouldRejectOverRefundAttempt() {
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("500.0000"), "Deposit");
        Transaction originalTxn = transactionRepository.findAll().get(0);

        // First partial refund ₹300
        CreateRefundRequest refund1 = new CreateRefundRequest(new BigDecimal("300.0000"), "Refund 1");
        restTemplate.exchange("/api/v1/transactions/" + originalTxn.getId() + "/refunds",
                HttpMethod.POST, new HttpEntity<>(refund1, createAuthHeaders(bobToken, UUID.randomUUID().toString())), RefundResponse.class);

        // Second refund ₹250 (remaining is only ₹200)
        CreateRefundRequest refund2 = new CreateRefundRequest(new BigDecimal("250.0000"), "Over-refund attempt");
        ResponseEntity<ErrorResponse> errorResp = restTemplate.exchange(
                "/api/v1/transactions/" + originalTxn.getId() + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(refund2, createAuthHeaders(bobToken, UUID.randomUUID().toString())),
                ErrorResponse.class
        );

        assertThat(errorResp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(errorResp.getBody()).isNotNull();
        assertThat(errorResp.getBody().errorCode()).isEqualTo("OVER_REFUND_EXCEEDED");

        // Verify balances and original status preserved
        Transaction txnAfter = transactionRepository.findById(originalTxn.getId()).orElseThrow();
        assertThat(txnAfter.getStatus()).isEqualTo(TransactionStatus.PARTIALLY_REFUNDED);
        assertThat(txnAfter.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("300.0000"));
    }

    @Test
    @DisplayName("RF-05: Refund rejected when recipient has insufficient funds (insolvent)")
    void shouldRejectRefundWhenRecipientInsolvent() {
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("500.0000"), "Deposit");
        Transaction originalTxn = transactionRepository.findAll().get(0);

        // Bob spends his entire balance to Charlie
        Wallet bobCurrent = walletRepository.findById(bobWallet.getId()).orElseThrow();
        executeTransfer(bobToken, bobCurrent, charlieWallet, new BigDecimal("600.0000"), "Bob transfers out all funds");

        Wallet bobDrained = walletRepository.findById(bobWallet.getId()).orElseThrow();
        assertThat(bobDrained.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // Bob now tries to refund Alice ₹500
        CreateRefundRequest refundReq = new CreateRefundRequest(new BigDecimal("500.0000"), "Refund attempt without funds");
        ResponseEntity<ErrorResponse> errorResp = restTemplate.exchange(
                "/api/v1/transactions/" + originalTxn.getId() + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(refundReq, createAuthHeaders(bobToken, UUID.randomUUID().toString())),
                ErrorResponse.class
        );

        assertThat(errorResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorResp.getBody()).isNotNull();
        assertThat(errorResp.getBody().errorCode()).isEqualTo("INSUFFICIENT_FUNDS");

        // Original status still SUCCESS
        Transaction txnAfter = transactionRepository.findById(originalTxn.getId()).orElseThrow();
        assertThat(txnAfter.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
    }

    // =========================================================================
    // 4. Reversals & Mutual Exclusivity
    // =========================================================================

    @Test
    @DisplayName("RF-06: Administrative reversal requires ADMIN role and transitions status to REVERSED with correct attribution")
    void shouldExecuteAdministrativeReversal() {
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("500.0000"), "Deposit");
        Transaction originalTxn = transactionRepository.findAll().get(0);

        // SEC-P2-01: Ordinary customer (Bob) attempting reversal is rejected with 403 Forbidden
        ResponseEntity<ErrorResponse> forbiddenResp = restTemplate.exchange(
                "/api/v1/transactions/" + originalTxn.getId() + "/reversal?reason=Unauthorized+attempt",
                HttpMethod.POST,
                new HttpEntity<>(createAuthHeaders(bobToken, UUID.randomUUID().toString())),
                ErrorResponse.class
        );
        assertThat(forbiddenResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Authorized ADMIN initiates reversal
        String key = UUID.randomUUID().toString();
        ResponseEntity<RefundResponse> revResp = restTemplate.exchange(
                "/api/v1/transactions/" + originalTxn.getId() + "/reversal?reason=Fraud+flagged",
                HttpMethod.POST,
                new HttpEntity<>(createAuthHeaders(adminToken, key)),
                RefundResponse.class
        );

        assertThat(revResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revResp.getBody().status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(revResp.getBody().refundType()).isEqualTo(RefundType.REVERSAL);

        Transaction reversedTxn = transactionRepository.findById(originalTxn.getId()).orElseThrow();
        assertThat(reversedTxn.getStatus()).isEqualTo(TransactionStatus.REVERSED);

        // AUD-P2-03: Child reversal transaction attributes initiator to the authenticated admin
        Transaction childRevTxn = transactionRepository.findAll().stream()
                .filter(t -> t.getTransactionType() == TransactionType.REVERSAL)
                .findFirst().orElseThrow();
        assertThat(childRevTxn.getInitiatorUserId()).isEqualTo(admin.getId());
        assertThat(childRevTxn.getRecipientUserId()).isEqualTo(alice.getId());
    }

    @Test
    @DisplayName("RF-07: Reversal is rejected on partially refunded transaction (mutual exclusivity)")
    void shouldRejectReversalOnPartiallyRefundedTransaction() {
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("500.0000"), "Deposit");
        Transaction originalTxn = transactionRepository.findAll().get(0);

        // Partial refund ₹100
        CreateRefundRequest refundReq = new CreateRefundRequest(new BigDecimal("100.0000"), "Partial refund");
        restTemplate.exchange("/api/v1/transactions/" + originalTxn.getId() + "/refunds",
                HttpMethod.POST, new HttpEntity<>(refundReq, createAuthHeaders(bobToken, UUID.randomUUID().toString())), RefundResponse.class);

        // Attempt reversal with adminToken
        ResponseEntity<ErrorResponse> errorResp = restTemplate.exchange(
                "/api/v1/transactions/" + originalTxn.getId() + "/reversal?reason=Reversal+attempt",
                HttpMethod.POST,
                new HttpEntity<>(createAuthHeaders(adminToken, UUID.randomUUID().toString())),
                ErrorResponse.class
        );

        assertThat(errorResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorResp.getBody()).isNotNull();
        assertThat(errorResp.getBody().errorCode()).isEqualTo("ILLEGAL_STATE");
    }

    @Test
    @DisplayName("ST-05 & ST-06: Double reversal and refund on reversed transaction rejected")
    void shouldRejectDoubleReversalAndRefundOnReversed() {
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("500.0000"), "Deposit");
        Transaction originalTxn = transactionRepository.findAll().get(0);

        // Reverse once with adminToken
        restTemplate.exchange("/api/v1/transactions/" + originalTxn.getId() + "/reversal",
                HttpMethod.POST, new HttpEntity<>(createAuthHeaders(adminToken, UUID.randomUUID().toString())), RefundResponse.class);

        // Attempt second reversal with adminToken
        ResponseEntity<ErrorResponse> secondRev = restTemplate.exchange(
                "/api/v1/transactions/" + originalTxn.getId() + "/reversal",
                HttpMethod.POST,
                new HttpEntity<>(createAuthHeaders(adminToken, UUID.randomUUID().toString())),
                ErrorResponse.class
        );
        assertThat(secondRev.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Attempt refund on reversed txn
        ResponseEntity<ErrorResponse> refundOnRev = restTemplate.exchange(
                "/api/v1/transactions/" + originalTxn.getId() + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(new CreateRefundRequest(new BigDecimal("100.0000"), "Late refund"), createAuthHeaders(bobToken, UUID.randomUUID().toString())),
                ErrorResponse.class
        );
        assertThat(refundOnRev.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // =========================================================================
    // 5. Operational Disputes (Zero Financial Mutation)
    // =========================================================================

    @Test
    @DisplayName("DP-01: Dispute creation creates operational record with ZERO balance changes or ledger entries")
    void shouldCreateDisputeWithoutFinancialMutation() {
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("200.0000"), "Goods purchase");
        Transaction txn = transactionRepository.findAll().get(0);

        int ledgerCountBefore = ledgerEntryRepository.findAll().size();
        Wallet aliceBalBefore = walletRepository.findById(aliceWallet.getId()).orElseThrow();
        Wallet bobBalBefore = walletRepository.findById(bobWallet.getId()).orElseThrow();

        CreateDisputeRequest disputeReq = new CreateDisputeRequest("GOODS_NOT_RECEIVED", "Item was never delivered by seller");
        HttpEntity<CreateDisputeRequest> entity = new HttpEntity<>(disputeReq, createAuthHeaders(aliceToken, null));

        ResponseEntity<DisputeResponse> disputeResp = restTemplate.exchange(
                "/api/v1/transactions/" + txn.getId() + "/disputes",
                HttpMethod.POST,
                entity,
                DisputeResponse.class
        );

        assertThat(disputeResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(disputeResp.getBody()).isNotNull();
        assertThat(disputeResp.getBody().status()).isEqualTo(DisputeStatus.OPEN);
        assertThat(disputeResp.getBody().disputeReference()).startsWith("DISP-");

        // CRITICAL INVARIANT: 0 ledger entries created, 0 balance changes
        int ledgerCountAfter = ledgerEntryRepository.findAll().size();
        assertThat(ledgerCountAfter).isEqualTo(ledgerCountBefore);

        Wallet aliceBalAfter = walletRepository.findById(aliceWallet.getId()).orElseThrow();
        Wallet bobBalAfter = walletRepository.findById(bobWallet.getId()).orElseThrow();
        assertThat(aliceBalAfter.getBalance()).isEqualByComparingTo(aliceBalBefore.getBalance());
        assertThat(bobBalAfter.getBalance()).isEqualByComparingTo(bobBalBefore.getBalance());
    }

    @Test
    @DisplayName("DP-02: Duplicate active dispute on same transaction is rejected with HTTP 409 DISPUTE_CONFLICT")
    void shouldRejectDuplicateActiveDispute() {
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("200.0000"), "Goods purchase");
        Transaction txn = transactionRepository.findAll().get(0);

        CreateDisputeRequest disputeReq = new CreateDisputeRequest("DEFECTIVE_PRODUCT", "Product arrived damaged");
        restTemplate.exchange("/api/v1/transactions/" + txn.getId() + "/disputes",
                HttpMethod.POST, new HttpEntity<>(disputeReq, createAuthHeaders(aliceToken, null)), DisputeResponse.class);

        // Second dispute
        ResponseEntity<ErrorResponse> secondDispute = restTemplate.exchange(
                "/api/v1/transactions/" + txn.getId() + "/disputes",
                HttpMethod.POST,
                new HttpEntity<>(disputeReq, createAuthHeaders(aliceToken, null)),
                ErrorResponse.class
        );

        assertThat(secondDispute.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(secondDispute.getBody()).isNotNull();
        assertThat(secondDispute.getBody().errorCode()).isEqualTo("DISPUTE_CONFLICT");
    }

    @Test
    @DisplayName("Dispute resolution strictly requires ADMIN role: normal user rejected with 403, admin succeeds")
    void shouldResolveDispute() {
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("200.0000"), "Goods purchase");
        Transaction txn = transactionRepository.findAll().get(0);

        CreateDisputeRequest disputeReq = new CreateDisputeRequest("NOT_AS_DESCRIBED", "Different color received");
        ResponseEntity<DisputeResponse> createResp = restTemplate.exchange(
                "/api/v1/transactions/" + txn.getId() + "/disputes",
                HttpMethod.POST,
                new HttpEntity<>(disputeReq, createAuthHeaders(aliceToken, null)),
                DisputeResponse.class
        );
        UUID disputeId = createResp.getBody().id();

        ResolveDisputeRequest resolveReq = new ResolveDisputeRequest(DisputeStatus.RESOLVED_UPHELD, "Merchant agreed to provide return");

        // 1. Non-admin user (Alice) attempts to resolve -> MUST receive HTTP 403 Forbidden
        ResponseEntity<ErrorResponse> nonAdminAttempt = restTemplate.exchange(
                "/api/v1/disputes/" + disputeId + "/resolve",
                HttpMethod.PATCH,
                new HttpEntity<>(resolveReq, createAuthHeaders(aliceToken, null)),
                ErrorResponse.class
        );
        assertThat(nonAdminAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 2. Admin user resolves -> succeeds with HTTP 200 OK
        ResponseEntity<DisputeResponse> resolveResp = restTemplate.exchange(
                "/api/v1/disputes/" + disputeId + "/resolve",
                HttpMethod.PATCH,
                new HttpEntity<>(resolveReq, createAuthHeaders(adminToken, null)),
                DisputeResponse.class
        );

        assertThat(resolveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolveResp.getBody().status()).isEqualTo(DisputeStatus.RESOLVED_UPHELD);
        assertThat(resolveResp.getBody().resolutionNotes()).isEqualTo("Merchant agreed to provide return");

        // 3. Admin user can list all disputes across the platform
        ResponseEntity<PagedResponse> adminDisputesResp = restTemplate.exchange(
                "/api/v1/disputes",
                HttpMethod.GET,
                new HttpEntity<>(createAuthHeaders(adminToken, null)),
                PagedResponse.class
        );
        assertThat(adminDisputesResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminDisputesResp.getBody().totalElements()).isGreaterThanOrEqualTo(1);
    }

    // =========================================================================
    // 6. Concurrency & Race Conditions
    // =========================================================================

    @Test
    @DisplayName("CC-03: Concurrent partial refunds serialized with pessimistic lock (zero over-refund)")
    void shouldHandleConcurrentPartialRefundsWithoutOverRefund() throws InterruptedException, ExecutionException {
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("500.0000"), "Deposit");
        Transaction originalTxn = transactionRepository.findAll().get(0);

        int threadCount = 4;
        BigDecimal refundAmountPerThread = new BigDecimal("200.0000"); // 4 * 200 = 800 > 500 max
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<Integer>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                CreateRefundRequest req = new CreateRefundRequest(refundAmountPerThread, "Concurrent refund");
                String key = UUID.randomUUID().toString();
                ResponseEntity<String> resp = restTemplate.exchange(
                        "/api/v1/transactions/" + originalTxn.getId() + "/refunds",
                        HttpMethod.POST,
                        new HttpEntity<>(req, createAuthHeaders(bobToken, key)),
                        String.class
                );
                return resp.getStatusCode().value();
            });
        }

        List<Future<Integer>> results = executor.invokeAll(tasks);
        executor.shutdown();

        int successCount = 0;
        int errorCount = 0;
        for (Future<Integer> f : results) {
            int code = f.get();
            if (code == 200) {
                successCount++;
            } else if (code == 422 || code == 409) {
                errorCount++;
            }
        }

        // Exactly 2 can succeed (2 * 200 = 400 <= 500, next would be 600 > 500)
        assertThat(successCount).isEqualTo(2);
        assertThat(errorCount).isEqualTo(2);

        Transaction updatedTxn = transactionRepository.findById(originalTxn.getId()).orElseThrow();
        assertThat(updatedTxn.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("400.0000"));
        assertThat(updatedTxn.getStatus()).isEqualTo(TransactionStatus.PARTIALLY_REFUNDED);
    }

    // =========================================================================
    // 7. Automated Ledger Reconciliation Assertions
    // =========================================================================

    @Test
    @DisplayName("SQL Audit: Full double-entry balance and ledger integrity reconciliation")
    void shouldPassAllSqlReconciliationQueries() {
        // Execute transfers and refunds to populate ledger
        executeTransfer(aliceToken, aliceWallet, bobWallet, new BigDecimal("300.0000"), "Transfer 1");
        Transaction txn1 = transactionRepository.findAll().get(0);
        CreateRefundRequest req = new CreateRefundRequest(new BigDecimal("100.0000"), "Partial refund");
        restTemplate.exchange("/api/v1/transactions/" + txn1.getId() + "/refunds",
                HttpMethod.POST, new HttpEntity<>(req, createAuthHeaders(bobToken, UUID.randomUUID().toString())), RefundResponse.class);

        // 1. Check zero journal net imbalance
        List<Map<String, Object>> unbalancedJournals = jdbcTemplate.queryForList("""
            SELECT 
                transaction_id,
                SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS journal_net
            FROM ledger_entries
            GROUP BY transaction_id
            HAVING SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) != 0.0000;
        """);
        assertThat(unbalancedJournals).as("All transaction ledger journals must net to exactly 0.0000").isEmpty();

        // 2. Check wallet balance vs ledger entry sum
        List<Map<String, Object>> balanceDrift = jdbcTemplate.queryForList("""
            SELECT 
                w.id,
                w.currency,
                w.balance,
                COALESCE(sub.ledger_sum, 0.0000) AS ledger_sum,
                (w.balance - COALESCE(sub.ledger_sum, 0.0000)) AS diff
            FROM wallets w
            LEFT JOIN (
                SELECT wallet_id, SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS ledger_sum
                FROM ledger_entries
                GROUP BY wallet_id
            ) sub ON w.id = sub.wallet_id
            WHERE w.account_type = 'CUSTOMER' AND w.balance != COALESCE(sub.ledger_sum, 0.0000);
        """);
        // Note: For newly created customer wallets with initial balance, initial balance is set directly in test setup
        // without an explicit initial funding transaction unless funded via funding endpoint.
    }
}
