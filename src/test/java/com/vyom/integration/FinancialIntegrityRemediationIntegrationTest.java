package com.vyom.integration;

import com.vyom.dto.request.CreateDisputeRequest;
import com.vyom.dto.request.CreateRefundRequest;
import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.request.ResolveDisputeRequest;
import com.vyom.dto.request.ReversalRequest;
import com.vyom.dto.response.DisputeResponse;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.RefundResponse;
import com.vyom.dto.response.TransactionResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.Dispute;
import com.vyom.entity.Transaction;
import com.vyom.entity.User;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.DisputeStatus;
import com.vyom.entity.enums.Role;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class FinancialIntegrityRemediationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private DisputeRepository disputeRepository;

    private User alice;
    private User bob;
    private User admin;

    private String aliceToken;
    private String bobToken;
    private String adminToken;

    private Wallet aliceWallet;
    private Wallet bobWallet;

    @BeforeEach
    void setupUsersAndWallets() {
        alice = userRepository.saveAndFlush(new User("alice-c@vyom.internal", passwordEncoder.encode("Password123!"), "Alice C", Role.USER));
        bob = userRepository.saveAndFlush(new User("bob-c@vyom.internal", passwordEncoder.encode("Password123!"), "Bob C", Role.USER));
        admin = userRepository.saveAndFlush(new User("admin-c@vyom.internal", passwordEncoder.encode("Password123!"), "Admin C", Role.ADMIN));

        aliceToken = jwtTokenService.generateToken(alice.getId(), alice.getEmail(), alice.getFullName(), Role.USER);
        bobToken = jwtTokenService.generateToken(bob.getId(), bob.getEmail(), bob.getFullName(), Role.USER);
        adminToken = jwtTokenService.generateToken(admin.getId(), admin.getEmail(), admin.getFullName(), Role.ADMIN);

        aliceWallet = walletRepository.saveAndFlush(new Wallet(alice.getId(), "Alice C Wallet", "INR", new BigDecimal("1000.0000")));
        bobWallet = walletRepository.saveAndFlush(new Wallet(bob.getId(), "Bob C Wallet", "INR", new BigDecimal("500.0000")));
    }

    private HttpHeaders createAuthHeaders(String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private Transaction executeTransfer(BigDecimal amount, String description) {
        CreateTransferRequest req = new CreateTransferRequest(aliceWallet.getId(), bobWallet.getId(), amount, "INR", description);
        HttpEntity<CreateTransferRequest> entity = new HttpEntity<>(req, createAuthHeaders(aliceToken, UUID.randomUUID().toString()));
        ResponseEntity<TransferResponse> resp = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST, entity, TransferResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return transactionRepository.findAll().stream()
                .filter(t -> t.getTransactionType() == TransactionType.TRANSFER)
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    @Test
    @DisplayName("F46: Reversals and refunds on compensation transactions (REFUND/REVERSAL) are strictly rejected")
    void shouldRejectCompensationOfCompensationTransactions() {
        Transaction original = executeTransfer(new BigDecimal("100.0000"), "Transfer to refund");

        // Bob performs a partial refund
        CreateRefundRequest refundReq = new CreateRefundRequest(new BigDecimal("50.0000"), "Partial refund");
        ResponseEntity<RefundResponse> refundResp = restTemplate.exchange(
                "/api/v1/transactions/" + original.getId() + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(refundReq, createAuthHeaders(bobToken, UUID.randomUUID().toString())),
                RefundResponse.class
        );
        assertThat(refundResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID refundTxnId = refundResp.getBody().transactionId();

        // Attempt 1: Alice tries to refund the refund child transaction -> rejected with 409 CONFLICT (IllegalStateException)
        ResponseEntity<ErrorResponse> refundOnRefund = restTemplate.exchange(
                "/api/v1/transactions/" + refundTxnId + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(new CreateRefundRequest(new BigDecimal("25.0000"), "Cascading refund"), createAuthHeaders(aliceToken, UUID.randomUUID().toString())),
                ErrorResponse.class
        );
        assertThat(refundOnRefund.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refundOnRefund.getBody().errorCode()).isEqualTo("ILLEGAL_STATE");

        // Attempt 2: Admin tries to reverse the refund child transaction -> rejected with 409 CONFLICT
        ResponseEntity<ErrorResponse> revOnRefund = restTemplate.exchange(
                "/api/v1/transactions/" + refundTxnId + "/reversal",
                HttpMethod.POST,
                new HttpEntity<>(new ReversalRequest("Reverse the refund"), createAuthHeaders(adminToken, UUID.randomUUID().toString())),
                ErrorResponse.class
        );
        assertThat(revOnRefund.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(revOnRefund.getBody().errorCode()).isEqualTo("ILLEGAL_STATE");
    }

    @Test
    @DisplayName("F47: Administrative reversal automatically transitions active dispute to RESOLVED_UPHELD with attribution")
    void shouldAutoResolveActiveDisputeOnAdministrativeReversal() {
        Transaction original = executeTransfer(new BigDecimal("200.0000"), "Payment with dispute");

        // Alice opens a dispute
        CreateDisputeRequest disputeReq = new CreateDisputeRequest("FRAUD", "Unauthorized transaction reported");
        ResponseEntity<DisputeResponse> disputeResp = restTemplate.exchange(
                "/api/v1/transactions/" + original.getId() + "/disputes",
                HttpMethod.POST,
                new HttpEntity<>(disputeReq, createAuthHeaders(aliceToken, null)),
                DisputeResponse.class
        );
        assertThat(disputeResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID disputeId = disputeResp.getBody().id();
        assertThat(disputeResp.getBody().status()).isEqualTo(DisputeStatus.OPEN);

        // Admin reverses transaction with JSON body
        ReversalRequest revReq = new ReversalRequest("Fraud confirmed by investigator");
        ResponseEntity<RefundResponse> revResp = restTemplate.exchange(
                "/api/v1/transactions/" + original.getId() + "/reversal",
                HttpMethod.POST,
                new HttpEntity<>(revReq, createAuthHeaders(adminToken, UUID.randomUUID().toString())),
                RefundResponse.class
        );
        assertThat(revResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revResp.getBody().reason()).isEqualTo("Fraud confirmed by investigator");

        // Verify dispute was automatically transitioned to RESOLVED_UPHELD with admin attribution
        Dispute updatedDispute = disputeRepository.findById(disputeId).orElseThrow();
        assertThat(updatedDispute.getStatus()).isEqualTo(DisputeStatus.RESOLVED_UPHELD);
        assertThat(updatedDispute.getResolvedBy()).isEqualTo(admin.getId());
        assertThat(updatedDispute.getResolvedAt()).isNotNull();
        assertThat(updatedDispute.getResolutionNotes()).contains("administrative reversal");
    }

    @Test
    @DisplayName("F36: Dispute terminal state immutability - cannot re-resolve resolved or cancelled disputes")
    void shouldRejectReResolutionOfTerminalDisputes() {
        Transaction original = executeTransfer(new BigDecimal("150.0000"), "Transfer for dispute test");

        CreateDisputeRequest disputeReq = new CreateDisputeRequest("SERVICE_NOT_RENDERED", "Service was not provided");
        ResponseEntity<DisputeResponse> createResp = restTemplate.exchange(
                "/api/v1/transactions/" + original.getId() + "/disputes",
                HttpMethod.POST,
                new HttpEntity<>(disputeReq, createAuthHeaders(aliceToken, null)),
                DisputeResponse.class
        );
        UUID disputeId = createResp.getBody().id();

        // Admin resolves as REJECTED
        ResolveDisputeRequest resolveReq = new ResolveDisputeRequest(DisputeStatus.RESOLVED_REJECTED, "Proof of service provided");
        ResponseEntity<DisputeResponse> resolveResp = restTemplate.exchange(
                "/api/v1/disputes/" + disputeId + "/resolve",
                HttpMethod.PATCH,
                new HttpEntity<>(resolveReq, createAuthHeaders(adminToken, null)),
                DisputeResponse.class
        );
        assertThat(resolveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolveResp.getBody().status()).isEqualTo(DisputeStatus.RESOLVED_REJECTED);
        assertThat(resolveResp.getBody().resolvedBy()).isEqualTo(admin.getId());

        // Attempt to re-resolve must fail with 409 CONFLICT
        ResolveDisputeRequest reResolveReq = new ResolveDisputeRequest(DisputeStatus.RESOLVED_UPHELD, "Attempt to overwrite resolution");
        ResponseEntity<ErrorResponse> reResolveResp = restTemplate.exchange(
                "/api/v1/disputes/" + disputeId + "/resolve",
                HttpMethod.PATCH,
                new HttpEntity<>(reResolveReq, createAuthHeaders(adminToken, null)),
                ErrorResponse.class
        );
        assertThat(reResolveResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("F37: Reversal transaction is visible to the debited recipient in transaction history")
    void shouldExposeReversalTransactionToDebitedRecipient() {
        Transaction original = executeTransfer(new BigDecimal("300.0000"), "Transfer to reverse");

        // Admin reverses transaction
        ReversalRequest revReq = new ReversalRequest("Administrative correction");
        ResponseEntity<RefundResponse> revResp = restTemplate.exchange(
                "/api/v1/transactions/" + original.getId() + "/reversal",
                HttpMethod.POST,
                new HttpEntity<>(revReq, createAuthHeaders(adminToken, UUID.randomUUID().toString())),
                RefundResponse.class
        );
        assertThat(revResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String reversalRef = revResp.getBody().referenceNumber();

        // Bob (the original recipient whose wallet was debited by the reversal) lists his transactions
        ResponseEntity<PagedResponse<TransactionResponse>> bobTxns = restTemplate.exchange(
                "/api/v1/transactions",
                HttpMethod.GET,
                new HttpEntity<>(createAuthHeaders(bobToken, null)),
                new ParameterizedTypeReference<PagedResponse<TransactionResponse>>() {}
        );
        assertThat(bobTxns.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bobTxns.getBody()).isNotNull();

        // Bob MUST be able to see the REVERSAL transaction in his list
        boolean bobSeesReversal = bobTxns.getBody().items().stream()
                .anyMatch(t -> t.referenceNumber().equals(reversalRef) && t.transactionType() == TransactionType.REVERSAL);
        assertThat(bobSeesReversal).as("Debited recipient Bob must see the reversal transaction in transaction history").isTrue();
    }
}
