package com.vyom.service;

import com.vyom.dto.request.CreateDisputeRequest;
import com.vyom.dto.request.ResolveDisputeRequest;
import com.vyom.dto.response.DisputeResponse;
import com.vyom.entity.Dispute;
import com.vyom.entity.Transaction;
import com.vyom.entity.User;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.AccountType;
import com.vyom.entity.enums.DisputeStatus;
import com.vyom.entity.enums.Role;
import com.vyom.entity.enums.TransactionStatus;
import com.vyom.entity.enums.TransactionType;
import com.vyom.exception.DisputeConflictException;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisputeConcurrencyIntegrationTest {

    @Mock
    private DisputeRepository disputeRepository;

    @Mock
    private TransactionRepository transactionRepository;

    private DisputeService disputeService;

    private User alice;
    private User bob;
    private UUID transactionId;
    private Transaction transaction;

    @BeforeEach
    void setUp() {
        disputeService = new DisputeService(disputeRepository, transactionRepository);

        alice = new User("alice@vyom.internal", "hashedpwd", "Alice Smith", Role.USER);
        alice.setId(UUID.randomUUID());

        bob = new User("bob@vyom.internal", "hashedpwd", "Bob Jones", Role.USER);
        bob.setId(UUID.randomUUID());

        transactionId = UUID.randomUUID();
        transaction = new Transaction(
                "TXN-20261119-TRANSFER99999",
                TransactionType.TRANSFER,
                TransactionStatus.SUCCESS,
                alice.getId(),
                bob.getId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("250.0000"),
                "INR",
                "Payment for services"
        );
        transaction.setId(transactionId);
    }

    @Test
    @DisplayName("F14: createDispute acquires row lock on Transaction via findByIdForUpdate")
    void createDispute_acquiresPessimisticRowLock() {
        when(transactionRepository.findByIdForUpdate(transactionId)).thenReturn(Optional.of(transaction));
        when(disputeRepository.existsByTransactionIdAndStatusIn(any(), any())).thenReturn(false);

        Dispute savedDispute = new Dispute("DISP-20261119-0000000000000001", transactionId, alice.getId(), "UNRECOGNIZED", "Never made this transfer");
        when(disputeRepository.save(any(Dispute.class))).thenReturn(savedDispute);

        CreateDisputeRequest request = new CreateDisputeRequest("UNRECOGNIZED", "Never made this transfer");
        DisputeResponse response = disputeService.createDispute(alice.getId(), transactionId, request);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(DisputeStatus.OPEN);

        // Verify that findByIdForUpdate was explicitly called rather than findById
        verify(transactionRepository).findByIdForUpdate(transactionId);
    }

    @Test
    @DisplayName("F14: createDispute rejects creation if active dispute already exists")
    void createDispute_rejectsWhenActiveDisputePresent() {
        when(transactionRepository.findByIdForUpdate(transactionId)).thenReturn(Optional.of(transaction));
        when(disputeRepository.existsByTransactionIdAndStatusIn(any(), any())).thenReturn(true);

        CreateDisputeRequest request = new CreateDisputeRequest("UNRECOGNIZED", "Duplicate dispute");
        assertThatThrownBy(() -> disputeService.createDispute(alice.getId(), transactionId, request))
                .isInstanceOf(DisputeConflictException.class)
                .hasMessageContaining("An active dispute already exists");
    }

    @Test
    @DisplayName("F14: resolveDispute acquires row lock via findByIdForUpdate and blocks terminal dispute re-resolution")
    void resolveDispute_acquiresRowLock_andRejectsTerminalStatus() {
        UUID adminId = UUID.randomUUID();
        com.vyom.security.UserPrincipal adminPrincipal = new com.vyom.security.UserPrincipal(
                adminId, "admin@vyom.internal", "hashedpwd", "System Administrator", Role.ADMIN
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminPrincipal, null, adminPrincipal.getAuthorities())
        );

        UUID disputeId = UUID.randomUUID();
        Dispute terminalDispute = new Dispute("DISP-20261119-0000000000000002", transactionId, alice.getId(), "UNRECOGNIZED", "Resolved already");
        terminalDispute.setStatus(DisputeStatus.RESOLVED_UPHELD);

        when(disputeRepository.findByIdForUpdate(disputeId)).thenReturn(Optional.of(terminalDispute));

        ResolveDisputeRequest request = new ResolveDisputeRequest(DisputeStatus.RESOLVED_REJECTED, "Attempt to overturn");
        assertThatThrownBy(() -> disputeService.resolveDispute(disputeId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already resolved and in terminal status");

        verify(disputeRepository).findByIdForUpdate(disputeId);
        SecurityContextHolder.clearContext();
    }
}
