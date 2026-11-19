package com.vyom.repository;

import com.vyom.entity.Wallet;
import com.vyom.entity.enums.AccountType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") UUID id);

    List<Wallet> findByUserId(UUID userId);

    Optional<Wallet> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByIdAndUserId(UUID id, UUID userId);

    Optional<Wallet> findByCurrencyAndAccountType(String currency, AccountType accountType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.currency = :currency AND w.accountType = com.vyom.entity.enums.AccountType.SYSTEM_SETTLEMENT")
    Optional<Wallet> findSystemSettlementWalletForUpdate(@Param("currency") String currency);

    @Query(value = "SELECT currency FROM wallets WHERE id = :walletId", nativeQuery = true)
    Optional<String> findCurrencyByWalletId(@Param("walletId") UUID walletId);

    @Query(value = "SELECT id FROM wallets WHERE currency = :currency AND account_type = 'SYSTEM_SETTLEMENT'", nativeQuery = true)
    Optional<UUID> findSystemSettlementWalletIdByCurrency(@Param("currency") String currency);
}
