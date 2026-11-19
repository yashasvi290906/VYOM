package com.vyom.security;

import com.vyom.dto.request.CreateDisputeRequest;
import com.vyom.dto.request.CreateRefundRequest;
import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.request.ResolveDisputeRequest;
import com.vyom.dto.response.AuthResponse;
import com.vyom.dto.response.DisputeResponse;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.RefundResponse;
import com.vyom.dto.response.TransactionReceiptResponse;
import com.vyom.dto.response.TransactionResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.dto.response.WalletResponse;
import com.vyom.entity.Dispute;
import com.vyom.entity.Transaction;
import com.vyom.entity.User;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.DisputeStatus;
import com.vyom.entity.enums.Role;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;
import com.vyom.integration.BaseIntegrationTest;
import com.vyom.repository.DisputeRepository;
import com.vyom.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 5.1: Comprehensive Authorization & RBAC Security Verification Suite")
public class AuthorizationSecurityTest extends BaseIntegrationTest {

    @Autowired
    private TransactionRepository transactionRepository;

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
    void setupPrincipalsAndWallets() {
        alice = new User("alice.auth@vyom.internal", passwordEncoder.encode("Password123!"), "Alice Auth", Role.USER);
        alice = userRepository.saveAndFlush(alice);

        bob = new User("bob.auth@vyom.internal", passwordEncoder.encode("Password123!"), "Bob Auth", Role.USER);
        bob = userRepository.saveAndFlush(bob);

        admin = new User("admin.auth@vyom.internal", passwordEncoder.encode("Password123!"), "Admin Auth", Role.ADMIN);
        admin = userRepository.saveAndFlush(admin);

        aliceToken = jwtTokenService.generateToken(alice.getId(), alice.getEmail(), alice.getFullName(), Role.USER);
        bobToken = jwtTokenService.generateToken(bob.getId(), bob.getEmail(), bob.getFullName(), Role.USER);
        adminToken = jwtTokenService.generateToken(admin.getId(), admin.getEmail(), admin.getFullName(), Role.ADMIN);

        aliceWallet = new Wallet(alice.getId(), "Alice Wallet", "INR", new BigDecimal("2000.0000"));
        aliceWallet = walletRepository.saveAndFlush(aliceWallet);

        bobWallet = new Wallet(bob.getId(), "Bob Wallet", "INR", new BigDecimal("1000.0000"));
        bobWallet = walletRepository.saveAndFlush(bobWallet);
    }

    private HttpHeaders createHeaders(String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Transaction executeAliceToBobTransfer(BigDecimal amount) {
        CreateTransferRequest req = new CreateTransferRequest(aliceWallet.getId(), bobWallet.getId(), amount, "INR", "Authorization test transfer");
        ResponseEntity<TransferResponse> res = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(req, createHeaders(aliceToken, UUID.randomUUID().toString())),
                TransferResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return transactionRepository.findAll().stream()
                .filter(t -> t.getTransactionType() == TransactionType.TRANSFER)
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    @Test
    @DisplayName("1. User A accessing User B's wallet returns 403 Forbidden")
    void userCannotAccessOtherUserWallet() {
        ResponseEntity<ErrorResponse> res = restTemplate.exchange(
                "/api/v1/wallets/" + bobWallet.getId(),
                HttpMethod.GET,
                new HttpEntity<>(createHeaders(aliceToken, null)),
                ErrorResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().errorCode()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("2. User A funding User B's wallet returns 403 Forbidden")
    void userCannotFundOtherUserWallet() {
        FundWalletRequest req = new FundWalletRequest(new BigDecimal("100.0000"), "Unsolicited funding");
        ResponseEntity<ErrorResponse> res = restTemplate.exchange(
                "/api/v1/wallets/" + bobWallet.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(req, createHeaders(aliceToken, UUID.randomUUID().toString())),
                ErrorResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().errorCode()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("3. User A initiating transfer with User B's wallet as source returns 403 Forbidden")
    void userCannotTransferFromOtherUserWallet() {
        CreateTransferRequest req = new CreateTransferRequest(
                bobWallet.getId(),
                aliceWallet.getId(),
                new BigDecimal("50.0000"),
                "INR",
                "Theft attempt"
        );
        ResponseEntity<ErrorResponse> res = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(req, createHeaders(aliceToken, UUID.randomUUID().toString())),
                ErrorResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().errorCode()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("4. User A accessing User B's transaction details or receipt returns 403 Forbidden")
    void userCannotAccessUnrelatedTransactionOrReceipt() {
        User charlie = new User("charlie.auth@vyom.internal", passwordEncoder.encode("Password123!"), "Charlie Auth", Role.USER);
        charlie = userRepository.saveAndFlush(charlie);
        String charlieToken = jwtTokenService.generateToken(charlie.getId(), charlie.getEmail(), charlie.getFullName(), Role.USER);

        Transaction txn = executeAliceToBobTransfer(new BigDecimal("200.0000"));

        // Charlie (unrelated) attempts to view transaction details -> 403 Forbidden
        ResponseEntity<ErrorResponse> detailsRes = restTemplate.exchange(
                "/api/v1/transactions/" + txn.getId(),
                HttpMethod.GET,
                new HttpEntity<>(createHeaders(charlieToken, null)),
                ErrorResponse.class
        );
        assertThat(detailsRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(detailsRes.getBody().errorCode()).isEqualTo("ACCESS_DENIED");

        // Charlie (unrelated) attempts to view transaction receipt -> 403 Forbidden
        ResponseEntity<ErrorResponse> receiptRes = restTemplate.exchange(
                "/api/v1/transactions/" + txn.getId() + "/receipt",
                HttpMethod.GET,
                new HttpEntity<>(createHeaders(charlieToken, null)),
                ErrorResponse.class
        );
        assertThat(receiptRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(receiptRes.getBody().errorCode()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("5. User A attempting dispute resolution returns 403 Forbidden; Admin succeeds")
    void userCannotResolveDispute_adminCanResolveDispute() {
        Transaction txn = executeAliceToBobTransfer(new BigDecimal("150.0000"));

        // Alice (initiator) creates a dispute
        CreateDisputeRequest disReq = new CreateDisputeRequest("UNAUTHORIZED_TRANSACTION", "Unauthorized payment transaction");
        ResponseEntity<DisputeResponse> createdRes = restTemplate.exchange(
                "/api/v1/transactions/" + txn.getId() + "/disputes",
                HttpMethod.POST,
                new HttpEntity<>(disReq, createHeaders(aliceToken, null)),
                DisputeResponse.class
        );
        assertThat(createdRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID disputeId = createdRes.getBody().id();

        // Alice attempts to resolve dispute -> 403 Forbidden
        ResolveDisputeRequest resolveReq = new ResolveDisputeRequest(DisputeStatus.RESOLVED_UPHELD, "Self-resolving dispute");
        ResponseEntity<ErrorResponse> userResolveRes = restTemplate.exchange(
                "/api/v1/disputes/" + disputeId + "/resolve",
                HttpMethod.PATCH,
                new HttpEntity<>(resolveReq, createHeaders(aliceToken, null)),
                ErrorResponse.class
        );
        assertThat(userResolveRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Bob attempts to resolve dispute -> 403 Forbidden
        ResponseEntity<ErrorResponse> bobResolveRes = restTemplate.exchange(
                "/api/v1/disputes/" + disputeId + "/resolve",
                HttpMethod.PATCH,
                new HttpEntity<>(resolveReq, createHeaders(bobToken, null)),
                ErrorResponse.class
        );
        assertThat(bobResolveRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Admin resolves dispute -> 200 OK
        ResponseEntity<DisputeResponse> adminResolveRes = restTemplate.exchange(
                "/api/v1/disputes/" + disputeId + "/resolve",
                HttpMethod.PATCH,
                new HttpEntity<>(resolveReq, createHeaders(adminToken, null)),
                DisputeResponse.class
        );
        assertThat(adminResolveRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminResolveRes.getBody().status()).isEqualTo(DisputeStatus.RESOLVED_UPHELD);
    }

    @Test
    @DisplayName("6. Ordinary user attempting reversal returns 403 Forbidden; Admin succeeds")
    void ordinaryUserCannotReverseTransaction_adminCanReverse() {
        Transaction txn = executeAliceToBobTransfer(new BigDecimal("300.0000"));

        // Sender (Alice) attempts reversal -> 403 Forbidden
        ResponseEntity<ErrorResponse> senderRev = restTemplate.exchange(
                "/api/v1/transactions/" + txn.getId() + "/reversal",
                HttpMethod.POST,
                new HttpEntity<>(createHeaders(aliceToken, UUID.randomUUID().toString())),
                ErrorResponse.class
        );
        assertThat(senderRev.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(senderRev.getBody().errorCode()).isEqualTo("ACCESS_DENIED");

        // Recipient (Bob) attempts reversal -> 403 Forbidden
        ResponseEntity<ErrorResponse> recipientRev = restTemplate.exchange(
                "/api/v1/transactions/" + txn.getId() + "/reversal",
                HttpMethod.POST,
                new HttpEntity<>(createHeaders(bobToken, UUID.randomUUID().toString())),
                ErrorResponse.class
        );
        assertThat(recipientRev.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(recipientRev.getBody().errorCode()).isEqualTo("ACCESS_DENIED");

        // Admin initiates reversal -> 200 OK
        ResponseEntity<RefundResponse> adminRev = restTemplate.exchange(
                "/api/v1/transactions/" + txn.getId() + "/reversal?reason=Court+ordered+reversal",
                HttpMethod.POST,
                new HttpEntity<>(createHeaders(adminToken, UUID.randomUUID().toString())),
                RefundResponse.class
        );
        assertThat(adminRev.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminRev.getBody().status()).isEqualTo(TransactionStatus.SUCCESS);

        Transaction updatedTxn = transactionRepository.findById(txn.getId()).orElseThrow();
        assertThat(updatedTxn.getStatus()).isEqualTo(TransactionStatus.REVERSED);
    }

    @Test
    @DisplayName("7. Non-recipient user attempting refund returns 403 Forbidden; Recipient succeeds")
    void nonRecipientCannotRefundTransaction_recipientCanRefund() {
        Transaction txn = executeAliceToBobTransfer(new BigDecimal("250.0000"));

        // Sender (Alice) attempts refund -> 403 Forbidden (only recipient can issue refund)
        CreateRefundRequest refundReq = new CreateRefundRequest(new BigDecimal("100.0000"), "Sender trying to claw back");
        ResponseEntity<ErrorResponse> senderRefundRes = restTemplate.exchange(
                "/api/v1/transactions/" + txn.getId() + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(refundReq, createHeaders(aliceToken, UUID.randomUUID().toString())),
                ErrorResponse.class
        );
        assertThat(senderRefundRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(senderRefundRes.getBody().errorCode()).isEqualTo("ACCESS_DENIED");

        // Recipient (Bob) issues refund -> 200 OK
        CreateRefundRequest legitimateRefund = new CreateRefundRequest(new BigDecimal("100.0000"), "Merchant partial refund");
        ResponseEntity<RefundResponse> recipientRefundRes = restTemplate.exchange(
                "/api/v1/transactions/" + txn.getId() + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(legitimateRefund, createHeaders(bobToken, UUID.randomUUID().toString())),
                RefundResponse.class
        );
        assertThat(recipientRefundRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recipientRefundRes.getBody().status()).isEqualTo(TransactionStatus.SUCCESS);
    }

    @Test
    @DisplayName("8. Registration payload privilege escalation attempt is neutralized")
    void registrationPrivilegeEscalationAttempt_isAssignedRoleUser() {
        String forgedPayload = "{\"email\":\"attacker.test@vyom.internal\",\"password\":\"Password123!\",\"fullName\":\"Attacker\",\"role\":\"ADMIN\"}";
        HttpHeaders headers = anonymousHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<AuthResponse> regRes = restTemplate.exchange(
                "/api/v1/auth/register",
                HttpMethod.POST,
                new HttpEntity<>(forgedPayload, headers),
                AuthResponse.class
        );

        assertThat(regRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AuthResponse auth = regRes.getBody();
        assertThat(auth).isNotNull();

        User attacker = userRepository.findById(auth.userId()).orElseThrow();
        assertThat(attacker.getRole()).isEqualTo(Role.USER);
        assertThat(jwtTokenService.extractRole(auth.token())).isEqualTo(Role.USER);

        // Attacker attempting administrative reversal fails
        Transaction txn = executeAliceToBobTransfer(new BigDecimal("50.0000"));
        ResponseEntity<ErrorResponse> attackRev = restTemplate.exchange(
                "/api/v1/transactions/" + txn.getId() + "/reversal",
                HttpMethod.POST,
                new HttpEntity<>(createHeaders(auth.token(), UUID.randomUUID().toString())),
                ErrorResponse.class
        );
        assertThat(attackRev.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("9. System settlement account cannot be accessed or funded by regular user")
    void systemSettlementAccount_protectedFromCustomerAccess() {
        Wallet sysWallet = walletService.getOrCreateSystemSettlementWallet("INR");

        // Direct GET returns 404
        ResponseEntity<ErrorResponse> getRes = restTemplate.exchange(
                "/api/v1/wallets/" + sysWallet.getId(),
                HttpMethod.GET,
                new HttpEntity<>(createHeaders(aliceToken, null)),
                ErrorResponse.class
        );
        assertThat(getRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Direct Funding returns 400
        FundWalletRequest fundReq = new FundWalletRequest(new BigDecimal("100.0000"), "Direct system topup");
        ResponseEntity<ErrorResponse> fundRes = restTemplate.exchange(
                "/api/v1/wallets/" + sysWallet.getId() + "/fundings",
                HttpMethod.POST,
                new HttpEntity<>(fundReq, createHeaders(aliceToken, UUID.randomUUID().toString())),
                ErrorResponse.class
        );
        assertThat(fundRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
