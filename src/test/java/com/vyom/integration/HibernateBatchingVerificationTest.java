package com.vyom.integration;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.User;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.Role;
import com.vyom.repository.IdempotencyRecordRepository;
import com.vyom.repository.LedgerEntryRepository;
import com.vyom.repository.TransactionRepository;
import com.vyom.repository.TransferRepository;
import com.vyom.repository.UserRepository;
import com.vyom.repository.WalletRepository;
import com.vyom.security.JwtTokenService;
import com.vyom.service.TransferService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.engine.jdbc.batch.spi.BatchBuilder;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class HibernateBatchingVerificationTest extends BaseIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HibernateBatchingVerificationTest.class);

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TransferService transferService;

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
    private JwtTokenService jwtTokenService;

    private User testUser;
    private Wallet sourceWallet;
    private Wallet destWallet;

    @BeforeEach
    void setUpTestData() {
        ledgerEntryRepository.deleteAll();
        transferRepository.deleteAll();
        transactionRepository.deleteAll();
        idempotencyRecordRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(new User(
                "batching.user@vyom.internal",
                "$2a$10$abcdefghijklmnopqrstuvwxyz1234567890",
                "Batching Test User",
                Role.USER
        ));

        sourceWallet = walletRepository.save(new Wallet(
                testUser.getId(),
                "Batching Source",
                "INR",
                new BigDecimal("50000.0000")
        ));

        destWallet = walletRepository.save(new Wallet(
                testUser.getId(),
                "Batching Dest",
                "INR",
                new BigDecimal("10000.0000")
        ));
    }

    @Test
    @DisplayName("Finding 2: Verify Hibernate JDBC batch configuration and entity persistence")
    void shouldVerifyHibernateBatchConfigurationAndExecution() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        SessionFactoryImplementor sfi = entityManagerFactory.unwrap(SessionFactoryImplementor.class);

        // Verify BatchBuilder configuration
        BatchBuilder batchBuilder = sfi.getServiceRegistry().getService(BatchBuilder.class);
        assertThat(batchBuilder).isNotNull();
        log.info("Active Hibernate BatchBuilder: {}", batchBuilder.getClass().getName());

        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        // Execute a transfer operation
        String key = "batch-test-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(
                sourceWallet.getId(),
                destWallet.getId(),
                new BigDecimal("100.0000"),
                "INR",
                "Batching verification transfer"
        );

        TransferResponse response = transferService.transfer(testUser.getId(), key, request);
        assertThat(response).isNotNull();
        assertThat(response.transferId()).isNotNull();

        // Inspect Statistics
        log.info("=== HIBERNATE STATISTICS AFTER TRANSFER ===");
        log.info("Entity Insert Count: {}", statistics.getEntityInsertCount());
        log.info("Entity Update Count: {}", statistics.getEntityUpdateCount());
        log.info("Prepare Statement Count: {}", statistics.getPrepareStatementCount());
        log.info("Flush Count: {}", statistics.getFlushCount());

        // In a transfer:
        // Entities inserted: IdempotencyRecord (1 in reserveKey), Transfer (1), Transaction (1), LedgerEntry (2) = 5 entity inserts
        assertThat(statistics.getEntityInsertCount()).isEqualTo(5);
        // Entities updated: Source Wallet (1), Dest Wallet (1) = 2 entity updates
        assertThat(statistics.getEntityUpdateCount()).isEqualTo(2);

        // Verify database counts
        assertThat(transferRepository.count()).isEqualTo(1);
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }
}
