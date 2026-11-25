package com.vyom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyom.dto.response.FundingResponse;
import com.vyom.dto.response.RefundResponse;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.Funding;
import com.vyom.entity.IdempotencyRecord;
import com.vyom.entity.Refund;
import com.vyom.entity.Transaction;
import com.vyom.entity.Transfer;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.IdempotencyStatus;
import com.vyom.entity.enums.TransactionType;
import com.vyom.repository.FundingRepository;
import com.vyom.repository.IdempotencyRecordRepository;
import com.vyom.repository.RefundRepository;
import com.vyom.repository.TransactionRepository;
import com.vyom.repository.TransferRepository;
import com.vyom.repository.WalletRepository;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@ConditionalOnProperty(name = "vyom.idempotency.janitor.enabled", havingValue = "true", matchIfMissing = true)
public class IdempotencyJanitorService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyJanitorService.class);
    public static final long JANITOR_ADVISORY_LOCK_ID = 8899112233L;
    private static final int BATCH_SIZE = 100;

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final TransferRepository transferRepository;
    private final FundingRepository fundingRepository;
    private final TransactionRepository transactionRepository;
    private final WalletRepository walletRepository;
    private final RefundRepository refundRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final Duration timeoutDuration;

    public IdempotencyJanitorService(IdempotencyRecordRepository idempotencyRecordRepository,
                                     TransferRepository transferRepository,
                                     FundingRepository fundingRepository,
                                     TransactionRepository transactionRepository,
                                     WalletRepository walletRepository,
                                     RefundRepository refundRepository,
                                     ObjectMapper objectMapper,
                                     JdbcTemplate jdbcTemplate,
                                     @Value("${vyom.idempotency.timeout-seconds:120}") long timeoutSeconds) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.transferRepository = transferRepository;
        this.fundingRepository = fundingRepository;
        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
        this.refundRepository = refundRepository;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.timeoutDuration = Duration.ofSeconds(timeoutSeconds);
    }

    @Scheduled(
            fixedDelayString = "${vyom.idempotency.janitor.interval-ms:60000}",
            initialDelayString = "${vyom.idempotency.janitor.initial-delay-ms:10000}"
    )
    @Transactional
    public int sweepStaleProcessingRecords() {
        return sweepStaleProcessingRecords(timeoutDuration);
    }

    @Transactional
    public int sweepStaleProcessingRecords(Duration timeout) {
        // Multi-instance distributed coordination via PostgreSQL transaction-level advisory lock
        if (jdbcTemplate != null) {
            Boolean lockAcquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, JANITOR_ADVISORY_LOCK_ID);
            if (!Boolean.TRUE.equals(lockAcquired)) {
                log.info("Idempotency janitor: another cluster instance is currently executing the sweep. Skipping this run.");
                return 0;
            }
        }

        Instant now = Instant.now();
        Instant staleThreshold = now.minus(timeout);

        // Process in bounded batches (F13 / F55)
        List<IdempotencyRecord> staleRecords = idempotencyRecordRepository.findAbandonedProcessingRecords(
                staleThreshold, PageRequest.of(0, BATCH_SIZE, Sort.by("updatedAt").ascending()));

        if (staleRecords.isEmpty()) {
            log.trace("Idempotency janitor: no stale PROCESSING records found older than {}", staleThreshold);
            return 0;
        }

        log.info("Idempotency janitor: found {} stale PROCESSING records older than {}", staleRecords.size(), staleThreshold);
        int sweptCount = 0;

        for (IdempotencyRecord record : staleRecords) {
            boolean entityCommitted = false;
            String reconstructedBody = null;

            if (record.getTransferId() != null) {
                Transfer transfer = transferRepository.findById(record.getTransferId()).orElse(null);
                if (transfer != null) {
                    entityCommitted = true;
                    try {
                        reconstructedBody = objectMapper.writeValueAsString(TransferResponse.from(transfer));
                    } catch (Exception e) {
                        log.warn("Could not serialize TransferResponse for record id={}", record.getId(), e);
                    }
                }
            } else if (record.getFundingId() != null) {
                Funding funding = fundingRepository.findById(record.getFundingId()).orElse(null);
                if (funding != null) {
                    entityCommitted = true;
                    try {
                        BigDecimal balanceAfter = walletRepository.findById(funding.getWalletId())
                                .map(Wallet::getBalance)
                                .orElse(funding.getAmount());
                        reconstructedBody = objectMapper.writeValueAsString(FundingResponse.from(funding, balanceAfter));
                    } catch (Exception e) {
                        log.warn("Could not serialize FundingResponse for record id={}", record.getId(), e);
                    }
                }
            } else if (record.getTransactionId() != null) {
                Transaction txn = transactionRepository.findById(record.getTransactionId()).orElse(null);
                if (txn != null) {
                    entityCommitted = true;
                    try {
                        if (txn.getTransactionType() == TransactionType.REFUND || txn.getTransactionType() == TransactionType.REVERSAL) {
                            Refund refund = refundRepository.findByTransactionId(txn.getId()).orElse(null);
                            if (refund != null) {
                                Transaction orig = txn.getRelatedTransactionId() != null 
                                        ? transactionRepository.findById(txn.getRelatedTransactionId()).orElse(null) 
                                        : null;
                                BigDecimal totalRef = orig != null ? orig.getRefundedAmount() : txn.getAmount();
                                BigDecimal remaining = orig != null ? orig.getAmount().subtract(totalRef) : BigDecimal.ZERO.setScale(4);
                                RefundResponse refundResp = new RefundResponse(
                                        refund.getId(),
                                        txn.getId(),
                                        refund.getOriginalTransactionId(),
                                        txn.getReferenceNumber(),
                                        refund.getRefundType(),
                                        txn.getAmount(),
                                        txn.getCurrency(),
                                        totalRef,
                                        remaining,
                                        refund.getReason(),
                                        txn.getStatus(),
                                        txn.getCreatedAt()
                                );
                                reconstructedBody = objectMapper.writeValueAsString(refundResp);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Could not serialize RefundResponse for record id={}", record.getId(), e);
                    }
                }
            }

            if (entityCommitted) {
                log.warn("Idempotency janitor: found committed entity for stale record id={}, key={}. Marking COMPLETED.",
                        record.getId(), record.getIdempotencyKey());
                record.setStatus(IdempotencyStatus.COMPLETED);
                if (record.getResponseBody() == null && reconstructedBody != null) {
                    record.setResponseBody(reconstructedBody);
                    record.setResponseStatusCode(200);
                }
            } else {
                log.info("Idempotency janitor: marking uncommitted abandoned record id={}, key={}, userId={} as FAILED.",
                        record.getId(), record.getIdempotencyKey(), record.getUserId());
                record.setStatus(IdempotencyStatus.FAILED);
                record.setResponseStatusCode(504);
                record.setResponseBody("{\"status\":504,\"error\":\"PROCESSING_TIMEOUT\",\"message\":\"Transaction processing timed out and was abandoned\"}");
            }
            record.setUpdatedAt(now);
            // Optimistic locking handled automatically by Hibernate via @Version

            try {
                idempotencyRecordRepository.saveAndFlush(record);
                sweptCount++;
            } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
                log.info("Idempotency janitor: record id={}, key={} was concurrently modified. Skipping.",
                        record.getId(), record.getIdempotencyKey());
            }
        }

        log.info("Idempotency janitor successfully processed {} stale records", sweptCount);
        return sweptCount;
    }
}
