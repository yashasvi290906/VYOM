package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.IdempotencyRecord;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.User;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.EntryType;
import com.vyom.entity.enums.IdempotencyStatus;
import com.vyom.entity.enums.OperationType;
import com.vyom.entity.enums.Role;
import com.vyom.entity.enums.TransferStatus;
import com.vyom.repository.IdempotencyRecordRepository;
import com.vyom.repository.LedgerEntryRepository;
import com.vyom.repository.TransactionRepository;
import com.vyom.repository.TransferRepository;
import com.vyom.repository.UserRepository;
import com.vyom.repository.WalletRepository;
import com.vyom.security.JwtTokenService;
import com.vyom.service.IdempotencyService;
import com.vyom.service.TransferExecutionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class AmbiguousTimeoutRecoveryIntegrationTest extends BaseIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AmbiguousTimeoutRecoveryIntegrationTest.class);

    @LocalServerPort
    private int backendPort;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private JwtTokenService jwtTokenService;

    private static Process gatewayProcess;
    private static int gatewayPort;
    private static String gatewayBaseUrl;
    private static RestTemplate gatewayClient;

    private User testUser;
    private String jwtToken;
    private Wallet sourceWallet;
    private Wallet destWallet;

    @BeforeEach
    void setUpTestData() throws Exception {
        // Reset test latches
        if (TransferExecutionService.testPauseLatch != null) {
            TransferExecutionService.testPauseLatch.countDown();
        }
        TransferExecutionService.testEnteredLatch = null;
        TransferExecutionService.testPauseLatch = null;

        ledgerEntryRepository.deleteAll();
        transferRepository.deleteAll();
        transactionRepository.deleteAll();
        idempotencyRecordRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(new User(
                "timeout.user@vyom.internal",
                "$2a$10$abcdefghijklmnopqrstuvwxyz1234567890",
                "Timeout Test User",
                Role.USER
        ));
        jwtToken = jwtTokenService.generateToken(testUser.getId(), testUser.getEmail(), testUser.getRole().name());

        sourceWallet = walletRepository.save(new Wallet(
                testUser.getId(),
                "Timeout Source",
                "INR",
                new BigDecimal("5000.0000")
        ));

        destWallet = walletRepository.save(new Wallet(
                testUser.getId(),
                "Timeout Dest",
                "INR",
                new BigDecimal("1000.0000")
        ));

        ensureGatewayStarted();
    }

    private synchronized void ensureGatewayStarted() throws Exception {
        if (gatewayClient == null) {
            gatewayClient = new RestTemplate();
            gatewayClient.setErrorHandler(new DefaultResponseErrorHandler() {
                @Override
                public boolean hasError(ClientHttpResponse response) {
                    return false; // Do not throw on 4xx/5xx to allow assert
                }
            });
        }

        if (gatewayProcess != null && gatewayProcess.isAlive()) {
            return;
        }

        try (ServerSocket socket = new ServerSocket(0)) {
            gatewayPort = socket.getLocalPort();
        }
        gatewayBaseUrl = "http://localhost:" + gatewayPort;

        log.info("Starting Express Gateway on port {} proxying to backend on port {}", gatewayPort, backendPort);

        ProcessBuilder pb = new ProcessBuilder("node", "gateway/src/server.js");
        pb.directory(new File("."));
        Map<String, String> env = pb.environment();
        env.put("PORT", String.valueOf(gatewayPort));
        env.put("BACKEND_BASE_URL", "http://localhost:" + backendPort);
        env.put("BACKEND_TIMEOUT_MS", "5000"); // Strict production timeout
        env.put("RATE_LIMIT_MAX", "10000");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        gatewayProcess = pb.start();

        gatewayClient = new RestTemplate();
        gatewayClient.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false; // Do not throw on 4xx/5xx to allow assert
            }
        });

        long deadline = System.currentTimeMillis() + 10000;
        boolean ready = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                ResponseEntity<String> healthResp = gatewayClient.getForEntity(gatewayBaseUrl + "/health", String.class);
                if (healthResp.getStatusCode().is2xxSuccessful()) {
                    ready = true;
                    break;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(150);
        }

        if (!ready) {
            throw new IllegalStateException("Gateway failed to become ready on port " + gatewayPort);
        }
        log.info("Express Gateway successfully initialized and healthy on {}", gatewayBaseUrl);
    }

    @AfterEach
    void tearDownLatches() {
        if (TransferExecutionService.testPauseLatch != null) {
            TransferExecutionService.testPauseLatch.countDown();
        }
        TransferExecutionService.testEnteredLatch = null;
        TransferExecutionService.testPauseLatch = null;
    }

    @AfterAll
    static void stopGateway() {
        if (gatewayProcess != null && gatewayProcess.isAlive()) {
            log.info("Stopping Express Gateway process on port {}", gatewayPort);
            gatewayProcess.destroyForcibly();
        }
    }

    protected HttpHeaders gatewayAuthHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + jwtToken);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    @Test
    @DisplayName("Finding 3: Genuine ambiguous gateway timeout (504), in-flight conflict (409), and idempotent recovery (200)")
    void shouldRecoverCommittedTransferAfterGenuineAmbiguousGatewayTimeoutViaIdempotentRetry() throws Exception {
        String idempotencyKey = "ambig-real-" + UUID.randomUUID();
        BigDecimal transferAmount = new BigDecimal("250.0000");

        CreateTransferRequest request = new CreateTransferRequest(
                sourceWallet.getId(),
                destWallet.getId(),
                transferAmount,
                "INR",
                "Genuine ambiguous timeout fault injection test"
        );

        // Set up synchronization barriers
        TransferExecutionService.testEnteredLatch = new CountDownLatch(1);
        TransferExecutionService.testPauseLatch = new CountDownLatch(1);

        HttpEntity<CreateTransferRequest> requestEntity = new HttpEntity<>(request, gatewayAuthHeaders(idempotencyKey));

        log.info("Step 1: Submitting transfer through Express gateway with 5000ms production timeout...");
        CompletableFuture<ResponseEntity<String>> initialAttempt = CompletableFuture.supplyAsync(() ->
                gatewayClient.exchange(gatewayBaseUrl + "/api/v1/transfers", HttpMethod.POST, requestEntity, String.class)
        );

        // Wait until backend has locked wallets and paused at the barrier
        boolean entered = TransferExecutionService.testEnteredLatch.await(10, TimeUnit.SECONDS);
        assertThat(entered).as("Backend should have entered transfer execution and acquired locks").isTrue();
        log.info("Backend has entered execution and is held at synchronization barrier.");

        // Step 2: Gateway reaches 5000ms timeout and returns 504 Gateway Timeout
        ResponseEntity<String> timeoutResponse = initialAttempt.get(15, TimeUnit.SECONDS);
        log.info("Step 2 Result: Received gateway response status: {}", timeoutResponse.getStatusCode());
        assertThat(timeoutResponse.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(timeoutResponse.getBody()).contains("GATEWAY_TIMEOUT");
        assertThat(timeoutResponse.getBody()).contains("Upstream Spring Boot service timed out after 5000ms");

        // Step 3: While backend is still paused at barrier, submit an in-flight retry with the SAME idempotency key
        log.info("Step 3: Submitting in-flight retry with same idempotency key while backend is held...");
        ResponseEntity<String> inFlightResponse = gatewayClient.exchange(
                gatewayBaseUrl + "/api/v1/transfers",
                HttpMethod.POST,
                requestEntity,
                String.class
        );
        log.info("Step 3 Result: In-flight retry response status: {}", inFlightResponse.getStatusCode());
        assertThat(inFlightResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(inFlightResponse.getBody()).contains("IDEMPOTENCY_CONFLICT");
        assertThat(inFlightResponse.getBody()).contains("currently being processed");

        // Step 4: Release backend barrier to allow transaction to complete and commit
        log.info("Step 4: Releasing backend barrier to let original transaction commit...");
        TransferExecutionService.testPauseLatch.countDown();

        // Wait for transaction commit (poll DB up to 5 seconds)
        long deadline = System.currentTimeMillis() + 5000;
        boolean committed = false;
        while (System.currentTimeMillis() < deadline) {
            if (transferRepository.count() == 1) {
                committed = true;
                break;
            }
            Thread.sleep(100);
        }
        assertThat(committed).as("Original transaction should have committed to database").isTrue();

        // Step 5: Submit idempotent retry through Express gateway with identical key and payload
        log.info("Step 5: Submitting post-completion idempotent retry through Express gateway...");
        ResponseEntity<TransferResponse> retryResponse = gatewayClient.exchange(
                gatewayBaseUrl + "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, gatewayAuthHeaders(idempotencyKey)),
                TransferResponse.class
        );

        log.info("Step 5 Result: Post-completion retry response status: {}", retryResponse.getStatusCode());
        assertThat(retryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        TransferResponse body = retryResponse.getBody();
        assertThat(body).isNotNull();
        assertThat(body.transferId()).isNotNull();
        assertThat(body.amount()).isEqualByComparingTo(transferAmount);
        assertThat(body.currency()).isEqualTo("INR");
        assertThat(body.status()).isEqualTo(TransferStatus.COMPLETED);

        // Step 6: Critical Financial Invariants Verification
        log.info("Step 6: Verifying financial and ledger immutability invariants...");
        assertThat(transferRepository.count()).as("Exactly 1 transfer record must exist").isEqualTo(1);
        assertThat(transactionRepository.count()).as("Exactly 1 transaction record must exist").isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).as("Exactly 2 ledger entries must exist").isEqualTo(2);

        // Step 7: Verify balances mutated exactly once
        Wallet sourceAfter = walletRepository.findById(sourceWallet.getId()).orElseThrow();
        Wallet destAfter = walletRepository.findById(destWallet.getId()).orElseThrow();
        log.info("Final Balances - Source: {}, Dest: {}", sourceAfter.getBalance(), destAfter.getBalance());
        assertThat(sourceAfter.getBalance()).isEqualByComparingTo("4750.0000");
        assertThat(destAfter.getBalance()).isEqualByComparingTo("1250.0000");

        // Verify ledger entries match the balances exactly
        List<LedgerEntry> entries = ledgerEntryRepository.findAll();
        LedgerEntry debit = entries.stream().filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream().filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();
        assertThat(debit.getWalletId()).isEqualTo(sourceWallet.getId());
        assertThat(debit.getAmount()).isEqualByComparingTo(transferAmount);
        assertThat(debit.getBalanceAfter()).isEqualByComparingTo("4750.0000");

        assertThat(credit.getWalletId()).isEqualTo(destWallet.getId());
        assertThat(credit.getAmount()).isEqualByComparingTo(transferAmount);
        assertThat(credit.getBalanceAfter()).isEqualByComparingTo("1250.0000");
    }

    @Test
    @DisplayName("Finding 3: Retrying an ambiguous request with a modified payload must be rejected with 409 Conflict")
    void shouldRejectPayloadMismatchOnRetryWithSameIdempotencyKey() {
        String idempotencyKey = "payload-mismatch-" + UUID.randomUUID();

        CreateTransferRequest originalRequest = new CreateTransferRequest(
                sourceWallet.getId(),
                destWallet.getId(),
                new BigDecimal("100.0000"),
                "INR",
                "Original transfer"
        );

        // Initial request succeeds
        ResponseEntity<TransferResponse> initialResponse = gatewayClient.exchange(
                gatewayBaseUrl + "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(originalRequest, gatewayAuthHeaders(idempotencyKey)),
                TransferResponse.class
        );
        assertThat(initialResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Adversarial/faulty retry with modified amount (300.00 instead of 100.00) using same idempotency key
        CreateTransferRequest tamperedRequest = new CreateTransferRequest(
                sourceWallet.getId(),
                destWallet.getId(),
                new BigDecimal("300.0000"),
                "INR",
                "Original transfer"
        );

        ResponseEntity<ErrorResponse> tamperedResponse = gatewayClient.exchange(
                gatewayBaseUrl + "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(tamperedRequest, gatewayAuthHeaders(idempotencyKey)),
                ErrorResponse.class
        );

        assertThat(tamperedResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tamperedResponse.getBody()).isNotNull();
        assertThat(tamperedResponse.getBody().errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(tamperedResponse.getBody().message()).contains("already used for a different transfer request");
    }

    @Test
    @DisplayName("Finding 3: Concurrent retry while transfer is actively PROCESSING returns 409 Conflict")
    void shouldRejectConcurrentRetryWhileTransferIsStillProcessing() {
        String idempotencyKey = "inflight-key-" + UUID.randomUUID();

        CreateTransferRequest request = new CreateTransferRequest(
                sourceWallet.getId(),
                destWallet.getId(),
                new BigDecimal("75.0000"),
                "INR",
                "In-flight test"
        );
        String fingerprint = idempotencyService.computeTransferFingerprint(testUser.getId(), request);

        // Simulate an in-flight PROCESSING record with an active owner token and recent timestamp
        IdempotencyRecord inFlightRecord = new IdempotencyRecord(
                testUser.getId(),
                idempotencyKey,
                OperationType.TRANSFER,
                fingerprint,
                IdempotencyStatus.PROCESSING,
                UUID.randomUUID()
        );
        inFlightRecord.setUpdatedAt(Instant.now().minus(2, ChronoUnit.SECONDS));
        idempotencyRecordRepository.saveAndFlush(inFlightRecord);

        // Retry while still in-flight
        ResponseEntity<ErrorResponse> response = gatewayClient.exchange(
                gatewayBaseUrl + "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, gatewayAuthHeaders(idempotencyKey)),
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(response.getBody().message()).contains("currently being processed");
    }

    @Test
    @DisplayName("Finding 3 / Phase 5.1: Uncommitted timeout allows safe retry with same key and commits exactly once")
    void shouldSafelyExecuteTransferAfterPreviousUncommittedTimeoutOrFailure() {
        String idempotencyKey = "uncommitted-timeout-" + UUID.randomUUID();
        BigDecimal transferAmount = new BigDecimal("120.0000");

        CreateTransferRequest request = new CreateTransferRequest(
                sourceWallet.getId(),
                destWallet.getId(),
                transferAmount,
                "INR",
                "Uncommitted timeout retry test"
        );
        String fingerprint = idempotencyService.computeTransferFingerprint(testUser.getId(), request);

        // Simulate an initial attempt that timed out on the client/gateway before committing:
        // Record was created in PROCESSING status but abandoned without committing.
        // Its timestamp is set beyond the processing timeout (stale lease) directly in DB to bypass @PreUpdate.
        UUID priorOwnerToken = UUID.randomUUID();
        IdempotencyRecord staleProcessingRecord = new IdempotencyRecord(
                testUser.getId(),
                idempotencyKey,
                OperationType.TRANSFER,
                fingerprint,
                IdempotencyStatus.PROCESSING,
                priorOwnerToken
        );
        staleProcessingRecord = idempotencyRecordRepository.saveAndFlush(staleProcessingRecord);
        jdbcTemplate.update("UPDATE idempotency_records SET updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(idempotencyService.getProcessingTimeout().plusSeconds(30))),
                staleProcessingRecord.getId());

        // Verify zero transfers or ledger entries exist prior to retry
        assertThat(transferRepository.count()).isEqualTo(0);
        assertThat(ledgerEntryRepository.count()).isEqualTo(0);

        // User retries with the SAME original idempotency key through the Express Gateway
        ResponseEntity<TransferResponse> retryResponse = gatewayClient.exchange(
                gatewayBaseUrl + "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, gatewayAuthHeaders(idempotencyKey)),
                TransferResponse.class
        );

        // Retry succeeds
        assertThat(retryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        TransferResponse body = retryResponse.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(body.amount()).isEqualByComparingTo(transferAmount);

        // Exactly one transfer, one transaction, two ledger entries created
        assertThat(transferRepository.count()).isEqualTo(1);
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);

        // Balances updated accurately exactly once
        Wallet sourceAfter = walletRepository.findById(sourceWallet.getId()).orElseThrow();
        Wallet destAfter = walletRepository.findById(destWallet.getId()).orElseThrow();
        assertThat(sourceAfter.getBalance()).isEqualByComparingTo("4880.0000"); // 5000 - 120
        assertThat(destAfter.getBalance()).isEqualByComparingTo("1120.0000"); // 1000 + 120
    }
}
