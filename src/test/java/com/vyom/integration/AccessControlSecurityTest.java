package com.vyom.integration;

import com.vyom.dto.request.CreateRefundRequest;
import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.request.RegisterRequest;
import com.vyom.dto.response.AuthResponse;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.RefundResponse;
import com.vyom.dto.response.TransactionReceiptResponse;
import com.vyom.dto.response.TransactionResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.dto.response.WalletResponse;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Transaction;
import com.vyom.entity.User;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.EntryType;
import com.vyom.entity.enums.Role;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;
import com.vyom.repository.TransactionRepository;
import com.vyom.service.RefundExecutionService;
import com.vyom.util.ReferenceGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 2.3: Access Control & Security Remediation Test Suite")
public class AccessControlSecurityTest extends BaseIntegrationTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private RefundExecutionService refundExecutionService;

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

    @BeforeEach
    void setupSecurityPrincipalsAndWallets() {
        alice = createUser("alice-sec@vyom.internal", "Alice Security", Role.USER);
        bob = createUser("bob-sec@vyom.internal", "Bob Security", Role.USER);
        charlie = createUser("charlie-sec@vyom.internal", "Charlie Security", Role.USER);
        admin = createUser("admin-sec@vyom.internal", "Admin Security", Role.ADMIN);

        aliceToken = jwtTokenService.generateToken(alice.getId(), alice.getEmail(), alice.getFullName(), Role.USER);
        bobToken = jwtTokenService.generateToken(bob.getId(), bob.getEmail(), bob.getFullName(), Role.USER);
        charlieToken = jwtTokenService.generateToken(charlie.getId(), charlie.getEmail(), charlie.getFullName(), Role.USER);
        adminToken = jwtTokenService.generateToken(admin.getId(), admin.getEmail(), admin.getFullName(), Role.ADMIN);

        aliceWallet = createWallet(alice.getId(), "Alice Wallet", "INR", new BigDecimal("1000.0000"));
        bobWallet = createWallet(bob.getId(), "Bob Wallet", "INR", new BigDecimal("500.0000"));
    }

    private User createUser(String email, String fullName, Role role) {
        User u = new User(email, passwordEncoder.encode("Password123!"), fullName, role);
        return userRepository.saveAndFlush(u);
    }

    private Wallet createWallet(UUID userId, String name, String currency, BigDecimal balance) {
        Wallet w = new Wallet(userId, name, currency, balance);
        return walletRepository.saveAndFlush(w);
    }

    private HttpHeaders createHeaders(String token, String key) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (key != null) {
            h.set("Idempotency-Key", key);
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private Transaction executeAliceToBobTransfer(BigDecimal amount) {
        CreateTransferRequest req = new CreateTransferRequest(aliceWallet.getId(), bobWallet.getId(), amount, "INR", "Sec transfer");
        String key = UUID.randomUUID().toString();
        ResponseEntity<TransferResponse> res = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(req, createHeaders(aliceToken, key)),
                TransferResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return transactionRepository.findAll().stream()
                .filter(t -> t.getTransactionType() == TransactionType.TRANSFER)
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    // =========================================================================
    // 1. Reversal Authorization (SEC-P2-01)
    // =========================================================================

    @Nested
    @DisplayName("1. SEC-P2-01: Administrative Reversal Authorization")
    class ReversalAuthorizationTests {

        @Test
        @DisplayName("Unauthenticated reversal request returns 401 Unauthorized")
        void unauthenticatedReversal_shouldReturn401() {
            Transaction txn = executeAliceToBobTransfer(new BigDecimal("200.0000"));

            HttpHeaders headers = anonymousHeaders();
            headers.set("Idempotency-Key", UUID.randomUUID().toString());

            ResponseEntity<ErrorResponse> res = restTemplate.exchange(
                    "/api/v1/transactions/" + txn.getId() + "/reversal",
                    HttpMethod.POST,
                    new HttpEntity<>(headers),
                    ErrorResponse.class
            );

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(res.getBody()).isNotNull();
            assertThat(res.getBody().errorCode()).isEqualTo("UNAUTHORIZED");
        }

        @Test
        @DisplayName("Ordinary USER (sender, recipient, or third-party) reversal request returns 403 Forbidden")
        void ordinaryUserReversal_shouldReturn403() {
            Transaction txn = executeAliceToBobTransfer(new BigDecimal("200.0000"));

            // 1. Recipient (Bob) attempts reversal -> 403
            ResponseEntity<ErrorResponse> bobRes = restTemplate.exchange(
                    "/api/v1/transactions/" + txn.getId() + "/reversal",
                    HttpMethod.POST,
                    new HttpEntity<>(createHeaders(bobToken, UUID.randomUUID().toString())),
                    ErrorResponse.class
            );
            assertThat(bobRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bobRes.getBody().errorCode()).isEqualTo("ACCESS_DENIED");

            // 2. Sender (Alice) attempts reversal -> 403
            ResponseEntity<ErrorResponse> aliceRes = restTemplate.exchange(
                    "/api/v1/transactions/" + txn.getId() + "/reversal",
                    HttpMethod.POST,
                    new HttpEntity<>(createHeaders(aliceToken, UUID.randomUUID().toString())),
                    ErrorResponse.class
            );
            assertThat(aliceRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(aliceRes.getBody().errorCode()).isEqualTo("ACCESS_DENIED");

            // 3. Unrelated third-party (Charlie) attempts reversal -> 403
            ResponseEntity<ErrorResponse> charlieRes = restTemplate.exchange(
                    "/api/v1/transactions/" + txn.getId() + "/reversal",
                    HttpMethod.POST,
                    new HttpEntity<>(createHeaders(charlieToken, UUID.randomUUID().toString())),
                    ErrorResponse.class
            );
            assertThat(charlieRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(charlieRes.getBody().errorCode()).isEqualTo("ACCESS_DENIED");
        }

        @Test
        @DisplayName("Authorized ADMIN reversal request succeeds subject to financial rules")
        void authorizedAdminReversal_shouldSucceed() {
            Transaction txn = executeAliceToBobTransfer(new BigDecimal("200.0000"));

            String key = UUID.randomUUID().toString();
            ResponseEntity<RefundResponse> res = restTemplate.exchange(
                    "/api/v1/transactions/" + txn.getId() + "/reversal?reason=Fraudulent+activity+confirmed",
                    HttpMethod.POST,
                    new HttpEntity<>(createHeaders(adminToken, key)),
                    RefundResponse.class
            );

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).isNotNull();
            assertThat(res.getBody().status()).isEqualTo(TransactionStatus.SUCCESS);
            assertThat(res.getBody().refundType()).isEqualTo(com.vyom.entity.enums.RefundType.REVERSAL);
            assertThat(res.getBody().amount()).isEqualByComparingTo("200.0000");

            Transaction updatedTxn = transactionRepository.findById(txn.getId()).orElseThrow();
            assertThat(updatedTxn.getStatus()).isEqualTo(TransactionStatus.REVERSED);
        }

        @Test
        @DisplayName("Ordinary registration cannot grant or forge ADMIN privileges")
        void ordinaryRegistration_cannotCreateAdmin() {
            // Attempt registration with forged payload containing role
            String payloadWithForgedRole = "{\"email\":\"attacker@vyom.internal\",\"password\":\"Password123!\",\"fullName\":\"Attacker\",\"role\":\"ADMIN\"}";
            HttpHeaders headers = anonymousHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<AuthResponse> regRes = restTemplate.exchange(
                    "/api/v1/auth/register",
                    HttpMethod.POST,
                    new HttpEntity<>(payloadWithForgedRole, headers),
                    AuthResponse.class
            );

            assertThat(regRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            AuthResponse auth = regRes.getBody();
            assertThat(auth).isNotNull();

            // Verify in database that role is USER, not ADMIN
            User attacker = userRepository.findById(auth.userId()).orElseThrow();
            assertThat(attacker.getRole()).isEqualTo(Role.USER);

            // Verify the generated token has role USER
            Role extractedRole = jwtTokenService.extractRole(auth.token());
            assertThat(extractedRole).isEqualTo(Role.USER);

            // Attempt reversal with attacker token -> must return 403 Forbidden
            Transaction txn = executeAliceToBobTransfer(new BigDecimal("100.0000"));
            ResponseEntity<ErrorResponse> revRes = restTemplate.exchange(
                    "/api/v1/transactions/" + txn.getId() + "/reversal",
                    HttpMethod.POST,
                    new HttpEntity<>(createHeaders(auth.token(), UUID.randomUUID().toString())),
                    ErrorResponse.class
            );
            assertThat(revRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("Defense in depth: service layer executeReversal blocks non-admin callers even if invoked directly")
        void serviceLayer_enforcesAdminAuthorization() {
            Transaction txn = executeAliceToBobTransfer(new BigDecimal("150.0000"));
            String key = UUID.randomUUID().toString();

            // Direct invocation of executeReversal with ordinary user UUID throws AccessDeniedException
            assertThatThrownBy(() -> refundExecutionService.executeReversal(
                    bob.getId(), key, UUID.randomUUID(), txn.getId(), "Direct bypass attempt"
            )).isInstanceOf(AccessDeniedException.class)
              .hasMessageContaining("Only an authorized administrator can perform reversals");
        }
    }

    // =========================================================================
    // 2. Legacy Authorization Bypass Removal (SEC-P2-02)
    // =========================================================================

    @Nested
    @DisplayName("2. SEC-P2-02: Legacy Authorization Bypass Removal")
    class LegacyAuthorizationBypassTests {

        @Test
        @DisplayName("Ordinary user cannot access unrelated legacy transaction or receipt")
        void ordinaryUser_cannotAccessUnrelatedLegacyTransaction() {
            // Seed a legacy transaction owned by DEFAULT_TEST_USER_ID with valid wallets
            Wallet legSrc = createWallet(DEFAULT_TEST_USER_ID, "Leg Src", "INR", new BigDecimal("100.0000"));
            Wallet legDst = createWallet(DEFAULT_TEST_USER_ID, "Leg Dst", "INR", new BigDecimal("100.0000"));

            Transaction legacyTxn = new Transaction(
                    ReferenceGenerator.generateTransactionReference(),
                    TransactionType.TRANSFER,
                    TransactionStatus.SUCCESS,
                    DEFAULT_TEST_USER_ID,
                    DEFAULT_TEST_USER_ID,
                    legSrc.getId(),
                    legDst.getId(),
                    new BigDecimal("50.0000"),
                    "INR",
                    "Legacy transfer"
            );
            legacyTxn = transactionRepository.saveAndFlush(legacyTxn);

            // Ordinary user (Alice) attempting to view legacy transaction -> 403 Forbidden
            ResponseEntity<ErrorResponse> txRes = restTemplate.exchange(
                    "/api/v1/transactions/" + legacyTxn.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(createHeaders(aliceToken, null)),
                    ErrorResponse.class
            );
            assertThat(txRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            // Ordinary user (Alice) attempting to view legacy receipt -> 403 Forbidden
            ResponseEntity<ErrorResponse> receiptRes = restTemplate.exchange(
                    "/api/v1/transactions/" + legacyTxn.getId() + "/receipt",
                    HttpMethod.GET,
                    new HttpEntity<>(createHeaders(aliceToken, null)),
                    ErrorResponse.class
            );
            assertThat(receiptRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("DEFAULT_USER_ID principal cannot bypass ownership checks on other users' transactions")
        void legacyDefaultUserId_cannotBypassOwnershipChecks() {
            // Alice transfers to Bob
            Transaction aliceBobTxn = executeAliceToBobTransfer(new BigDecimal("75.0000"));

            // Legacy user attempts to access Alice/Bob's transaction -> 403 Forbidden
            ResponseEntity<ErrorResponse> txRes = restTemplate.exchange(
                    "/api/v1/transactions/" + aliceBobTxn.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(createHeaders(defaultAuthToken, null)),
                    ErrorResponse.class
            );
            assertThat(txRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            // Legacy user attempts to access Alice/Bob's receipt -> 403 Forbidden
            ResponseEntity<ErrorResponse> receiptRes = restTemplate.exchange(
                    "/api/v1/transactions/" + aliceBobTxn.getId() + "/receipt",
                    HttpMethod.GET,
                    new HttpEntity<>(createHeaders(defaultAuthToken, null)),
                    ErrorResponse.class
            );
            assertThat(receiptRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("Legitimate authenticated access to own legacy records succeeds")
        void legitimateAccessToOwnedLegacyRecords_succeeds() {
            // Create a legacy wallet owned by DEFAULT_TEST_USER_ID
            Wallet legacyWallet = createWallet(DEFAULT_TEST_USER_ID, "Legacy Wallet", "INR", new BigDecimal("300.0000"));

            // Authenticated legacy user accesses its own wallet -> 200 OK
            ResponseEntity<WalletResponse> ownWalletRes = restTemplate.exchange(
                    "/api/v1/wallets/" + legacyWallet.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(createHeaders(defaultAuthToken, null)),
                    WalletResponse.class
            );
            assertThat(ownWalletRes.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(ownWalletRes.getBody().walletId()).isEqualTo(legacyWallet.getId());

            // Alice attempting to access legacy wallet -> 403 Forbidden
            ResponseEntity<ErrorResponse> aliceWalletRes = restTemplate.exchange(
                    "/api/v1/wallets/" + legacyWallet.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(createHeaders(aliceToken, null)),
                    ErrorResponse.class
            );
            assertThat(aliceWalletRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // =========================================================================
    // 3. Reversal Initiator Attribution & Financial Invariants (AUD-P2-03)
    // =========================================================================

    @Nested
    @DisplayName("3. AUD-P2-03: Reversal Initiator Attribution & Financial Integrity")
    class ReversalAttributionAndIntegrityTests {

        @Test
        @DisplayName("Child reversal transaction accurately records authenticated ADMIN as initiator and preserves financial direction")
        void reversalAttribution_recordsAdminAsInitiator() {
            BigDecimal transferAmount = new BigDecimal("400.0000");
            Transaction txn = executeAliceToBobTransfer(transferAmount);

            BigDecimal aliceBalBefore = walletRepository.findById(aliceWallet.getId()).orElseThrow().getBalance();
            BigDecimal bobBalBefore = walletRepository.findById(bobWallet.getId()).orElseThrow().getBalance();

            String key = UUID.randomUUID().toString();
            ResponseEntity<RefundResponse> revRes = restTemplate.exchange(
                    "/api/v1/transactions/" + txn.getId() + "/reversal?reason=Compliance+action",
                    HttpMethod.POST,
                    new HttpEntity<>(createHeaders(adminToken, key)),
                    RefundResponse.class
            );
            assertThat(revRes.getStatusCode()).isEqualTo(HttpStatus.OK);

            // Verify child reversal transaction attribution
            Transaction childRev = transactionRepository.findAll().stream()
                    .filter(t -> t.getTransactionType() == TransactionType.REVERSAL)
                    .findFirst().orElseThrow();

            // Initiator is the ADMIN (AUD-P2-03 fixed)
            assertThat(childRev.getInitiatorUserId()).isEqualTo(admin.getId());
            // Recipient is the original sender who receives back the funds
            assertThat(childRev.getRecipientUserId()).isEqualTo(alice.getId());
            // Wallets: Bob is debited (source), Alice is credited (destination)
            assertThat(childRev.getSourceWalletId()).isEqualTo(bobWallet.getId());
            assertThat(childRev.getDestinationWalletId()).isEqualTo(aliceWallet.getId());
            // Related transaction points to original
            assertThat(childRev.getRelatedTransactionId()).isEqualTo(txn.getId());

            // Balances: Bob debited 400, Alice credited 400
            BigDecimal aliceBalAfter = walletRepository.findById(aliceWallet.getId()).orElseThrow().getBalance();
            BigDecimal bobBalAfter = walletRepository.findById(bobWallet.getId()).orElseThrow().getBalance();

            assertThat(aliceBalAfter).isEqualByComparingTo(aliceBalBefore.add(transferAmount));
            assertThat(bobBalAfter).isEqualByComparingTo(bobBalBefore.subtract(transferAmount));

            // Verify ledger entries for child reversal transaction are balanced
            List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(childRev.getId());
            assertThat(entries).hasSize(2);

            BigDecimal debitSum = entries.stream()
                    .filter(e -> e.getEntryType() == EntryType.DEBIT)
                    .map(LedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal creditSum = entries.stream()
                    .filter(e -> e.getEntryType() == EntryType.CREDIT)
                    .map(LedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(debitSum).isEqualByComparingTo(transferAmount);
            assertThat(creditSum).isEqualByComparingTo(transferAmount);
            assertThat(debitSum.subtract(creditSum)).isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("Reversal idempotency: replay returns cached response; different key on reversed txn is rejected")
        void reversalIdempotency_replaysSafelyAndRejectsDuplicates() {
            Transaction txn = executeAliceToBobTransfer(new BigDecimal("300.0000"));
            String key = UUID.randomUUID().toString();

            // First reversal request
            ResponseEntity<RefundResponse> first = restTemplate.exchange(
                    "/api/v1/transactions/" + txn.getId() + "/reversal",
                    HttpMethod.POST,
                    new HttpEntity<>(createHeaders(adminToken, key)),
                    RefundResponse.class
            );
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
            UUID reversalId = first.getBody().refundId();

            // Replay with identical key returns cached response
            ResponseEntity<RefundResponse> replay = restTemplate.exchange(
                    "/api/v1/transactions/" + txn.getId() + "/reversal",
                    HttpMethod.POST,
                    new HttpEntity<>(createHeaders(adminToken, key)),
                    RefundResponse.class
            );
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(replay.getBody().refundId()).isEqualTo(reversalId);

            // Attempt with different key on now-reversed transaction returns 409 Conflict
            ResponseEntity<ErrorResponse> diffKey = restTemplate.exchange(
                    "/api/v1/transactions/" + txn.getId() + "/reversal",
                    HttpMethod.POST,
                    new HttpEntity<>(createHeaders(adminToken, UUID.randomUUID().toString())),
                    ErrorResponse.class
            );
            assertThat(diffKey.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(diffKey.getBody().errorCode()).isEqualTo("ILLEGAL_STATE");
        }
    }
}
