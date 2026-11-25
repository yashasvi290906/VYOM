package com.vyom.service;

import com.vyom.dto.response.TransactionReceiptResponse;
import com.vyom.entity.Transaction;
import com.vyom.entity.User;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.AccountType;
import com.vyom.entity.enums.Role;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;
import com.vyom.repository.DisputeRepository;
import com.vyom.repository.TransactionRepository;
import com.vyom.repository.UserRepository;
import com.vyom.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionReceiptAttributionTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DisputeRepository disputeRepository;

    private TransactionService transactionService;

    private User alice;
    private User bob;
    private User admin;

    private Wallet aliceWallet;
    private Wallet bobWallet;
    private Wallet settlementWallet;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, walletRepository, userRepository, disputeRepository);

        alice = new User("alice@vyom.internal", "hashedpwd", "Alice Smith", Role.USER);
        alice.setId(UUID.randomUUID());

        bob = new User("bob@vyom.internal", "hashedpwd", "Bob Jones", Role.USER);
        bob.setId(UUID.randomUUID());

        admin = new User("admin@vyom.internal", "hashedpwd", "System Administrator", Role.ADMIN);
        admin.setId(UUID.randomUUID());

        aliceWallet = new Wallet(alice.getId(), "Alice Smith", "INR", new BigDecimal("5000.0000"), AccountType.CUSTOMER);
        aliceWallet.setId(UUID.randomUUID());

        bobWallet = new Wallet(bob.getId(), "Bob Jones", "INR", new BigDecimal("2000.0000"), AccountType.CUSTOMER);
        bobWallet.setId(UUID.randomUUID());

        settlementWallet = new Wallet(null, "SYSTEM_SETTLEMENT_INR", "INR", new BigDecimal("-100000.0000"), AccountType.SYSTEM_SETTLEMENT);
        settlementWallet.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("F13: Transfer receipt correctly attributes Alice as sender and Bob as recipient")
    void transferReceipt_attributions() {
        UUID txnId = UUID.randomUUID();
        Transaction transferTxn = new Transaction(
                "TXN-20261119-TRANSFER000001",
                TransactionType.TRANSFER,
                TransactionStatus.SUCCESS,
                alice.getId(),
                bob.getId(),
                aliceWallet.getId(),
                bobWallet.getId(),
                new BigDecimal("500.0000"),
                "INR",
                "Dinner split"
        );
        transferTxn.setId(txnId);
        transferTxn.setCompletedAt(Instant.now());

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(transferTxn));
        when(disputeRepository.findByTransactionId(txnId)).thenReturn(Collections.emptyList());
        when(walletRepository.findById(aliceWallet.getId())).thenReturn(Optional.of(aliceWallet));
        when(walletRepository.findById(bobWallet.getId())).thenReturn(Optional.of(bobWallet));
        when(userRepository.findById(alice.getId())).thenReturn(Optional.of(alice));
        when(userRepository.findById(bob.getId())).thenReturn(Optional.of(bob));

        TransactionReceiptResponse receiptForAlice = transactionService.getTransactionReceipt(txnId, alice.getId());
        assertThat(receiptForAlice.sender().name()).isEqualTo("Alice Smith");
        assertThat(receiptForAlice.sender().maskedEmail()).isEqualTo("alice@vyom.internal");
        assertThat(receiptForAlice.recipient().name()).isEqualTo("Bob J.");
        assertThat(receiptForAlice.recipient().maskedEmail()).contains("***");
    }

    @Test
    @DisplayName("F13: Funding receipt attributes System Settlement Account as sender, not customer as both")
    void fundingReceipt_attributions() {
        UUID txnId = UUID.randomUUID();
        Transaction fundingTxn = new Transaction(
                "TXN-20261119-FUNDING00000001",
                TransactionType.FUNDING,
                TransactionStatus.SUCCESS,
                alice.getId(), // Initiator was Alice
                alice.getId(),
                settlementWallet.getId(), // Debited party is System Settlement
                aliceWallet.getId(),     // Credited party is Alice
                new BigDecimal("1000.0000"),
                "INR",
                "Wallet topup"
        );
        fundingTxn.setId(txnId);
        fundingTxn.setCompletedAt(Instant.now());

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(fundingTxn));
        when(disputeRepository.findByTransactionId(txnId)).thenReturn(Collections.emptyList());
        when(walletRepository.findById(settlementWallet.getId())).thenReturn(Optional.of(settlementWallet));
        when(walletRepository.findById(aliceWallet.getId())).thenReturn(Optional.of(aliceWallet));
        when(userRepository.findById(alice.getId())).thenReturn(Optional.of(alice));

        TransactionReceiptResponse receipt = transactionService.getTransactionReceipt(txnId, alice.getId());

        // Financial sender MUST be System Settlement Account, not Alice
        assertThat(receipt.sender().name()).isEqualTo("System Settlement Account");
        assertThat(receipt.sender().maskedEmail()).isEqualTo("settlement@vyom.internal");

        // Financial recipient is Alice
        assertThat(receipt.recipient().name()).isEqualTo("Alice Smith");
        assertThat(receipt.recipient().maskedEmail()).isEqualTo("alice@vyom.internal");

        // Operational actor is Alice
        assertThat(receipt.operationalActor()).isNotNull();
        assertThat(receipt.operationalActor().name()).isEqualTo("Alice Smith");
    }

    @Test
    @DisplayName("F13: Administrative reversal receipt attributes debited party as sender, admin as operational actor")
    void reversalReceipt_attributions() {
        UUID txnId = UUID.randomUUID();
        Transaction reversalTxn = new Transaction(
                "TXN-20261119-REVERSAL0000001",
                TransactionType.REVERSAL,
                TransactionStatus.SUCCESS,
                admin.getId(), // Admin initiated the reversal
                alice.getId(), // Original sender receives funds back
                bobWallet.getId(),   // Debited party: Bob returns funds
                aliceWallet.getId(), // Credited party: Alice gets restored
                new BigDecimal("500.0000"),
                "INR",
                "Administrative reversal"
        );
        reversalTxn.setId(txnId);
        reversalTxn.setCompletedAt(Instant.now());

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(reversalTxn));
        when(disputeRepository.findByTransactionId(txnId)).thenReturn(Collections.emptyList());
        when(walletRepository.findById(bobWallet.getId())).thenReturn(Optional.of(bobWallet));
        when(walletRepository.findById(aliceWallet.getId())).thenReturn(Optional.of(aliceWallet));
        when(userRepository.findById(bob.getId())).thenReturn(Optional.of(bob));
        when(userRepository.findById(alice.getId())).thenReturn(Optional.of(alice));
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));

        // When Alice views the reversal receipt
        TransactionReceiptResponse receiptForAlice = transactionService.getTransactionReceipt(txnId, alice.getId());

        // Debited party (financial sender) is Bob, not the administrator
        assertThat(receiptForAlice.sender().name()).isEqualTo("Bob J.");
        assertThat(receiptForAlice.sender().name()).doesNotContain("Admin");

        // Credited party is Alice
        assertThat(receiptForAlice.recipient().name()).isEqualTo("Alice Smith");

        // Operational actor is System Administrator
        assertThat(receiptForAlice.operationalActor()).isNotNull();
        assertThat(receiptForAlice.operationalActor().name()).isEqualTo("System A.");
    }
}
