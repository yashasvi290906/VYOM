package com.vyom.service;

import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.LedgerEntry;
import com.vyom.entity.Transfer;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.EntryType;
import com.vyom.entity.enums.OperationType;
import com.vyom.entity.enums.TransferStatus;
import com.vyom.exception.CurrencyMismatchException;
import com.vyom.exception.InsufficientFundsException;
import com.vyom.exception.InvalidTransferException;
import com.vyom.exception.WalletNotFoundException;
import com.vyom.repository.LedgerEntryRepository;
import com.vyom.repository.TransferRepository;
import com.vyom.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class TransferExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TransferExecutionService.class);

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyService idempotencyService;

    public TransferExecutionService(WalletRepository walletRepository,
                                    TransferRepository transferRepository,
                                    LedgerEntryRepository ledgerEntryRepository,
                                    IdempotencyService idempotencyService) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyService = idempotencyService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TransferResponse execute(String idempotencyKey, UUID ownerToken, CreateTransferRequest request) {
        UUID sourceId = request.sourceWalletId();
        UUID destId = request.destinationWalletId();
        BigDecimal amount = request.amount().setScale(4);

        if (sourceId.equals(destId)) {
            throw new InvalidTransferException("Source and destination wallets must be different");
        }

        // Deterministic lock acquisition ordering: smaller UUID first, larger UUID second.
        // Prevents circular-wait deadlocks among wallet rows accessed through this transfer path.
        UUID firstLockId = sourceId.compareTo(destId) < 0 ? sourceId : destId;
        UUID secondLockId = sourceId.compareTo(destId) < 0 ? destId : sourceId;

        log.debug("Acquiring pessimistic write locks in order: {} then {}", firstLockId, secondLockId);
        Wallet firstWallet = walletRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new WalletNotFoundException(firstLockId));
        Wallet secondWallet = walletRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new WalletNotFoundException(secondLockId));

        Wallet sourceWallet = firstWallet.getId().equals(sourceId) ? firstWallet : secondWallet;
        Wallet destWallet = firstWallet.getId().equals(destId) ? firstWallet : secondWallet;

        // Currency validations
        if (!sourceWallet.getCurrency().equalsIgnoreCase(destWallet.getCurrency())) {
            throw new CurrencyMismatchException(String.format(
                    "Currency mismatch between source wallet %s (%s) and destination wallet %s (%s)",
                    sourceId, sourceWallet.getCurrency(), destId, destWallet.getCurrency()));
        }

        if (!request.currency().equalsIgnoreCase(sourceWallet.getCurrency())) {
            throw new CurrencyMismatchException(String.format(
                    "Transfer currency (%s) does not match wallet currency (%s)",
                    request.currency(), sourceWallet.getCurrency()));
        }

        // Solvency check
        if (sourceWallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(sourceId, sourceWallet.getBalance(), amount);
        }

        // Balance mutations
        BigDecimal newSourceBalance = sourceWallet.getBalance().subtract(amount);
        sourceWallet.setBalance(newSourceBalance);

        BigDecimal newDestBalance = destWallet.getBalance().add(amount);
        destWallet.setBalance(newDestBalance);

        walletRepository.save(sourceWallet);
        walletRepository.save(destWallet);

        // Record Transfer
        Transfer transfer = new Transfer(
                sourceId,
                destId,
                amount,
                sourceWallet.getCurrency(),
                request.description(),
                TransferStatus.COMPLETED
        );
        Transfer savedTransfer = transferRepository.saveAndFlush(transfer);

        // Record immutable Double-Entry Ledger Entries
        LedgerEntry debitEntry = LedgerEntry.forTransfer(
                savedTransfer.getId(),
                sourceId,
                EntryType.DEBIT,
                amount,
                newSourceBalance
        );
        LedgerEntry creditEntry = LedgerEntry.forTransfer(
                savedTransfer.getId(),
                destId,
                EntryType.CREDIT,
                amount,
                newDestBalance
        );
        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        TransferResponse response = TransferResponse.from(savedTransfer);

        // Record idempotency completion with ownership token verification
        idempotencyService.complete(idempotencyKey, ownerToken, savedTransfer.getId(), OperationType.TRANSFER, 200, response);

        log.info("Transfer {} completed successfully: {} {} from {} to {}",
                savedTransfer.getId(), amount, sourceWallet.getCurrency(), sourceId, destId);

        return response;
    }
}

