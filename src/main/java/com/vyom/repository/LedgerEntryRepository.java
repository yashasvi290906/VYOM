package com.vyom.repository;

import com.vyom.entity.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    Page<LedgerEntry> findByWalletIdOrderByCreatedAtDescIdDesc(UUID walletId, Pageable pageable);

    List<LedgerEntry> findByTransferId(UUID transferId);

    List<LedgerEntry> findByFundingId(UUID fundingId);

    List<LedgerEntry> findByWalletId(UUID walletId);
}

