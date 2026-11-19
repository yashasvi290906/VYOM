package com.vyom.service;

import com.vyom.entity.IdempotencyRecord;
import com.vyom.entity.enums.IdempotencyStatus;
import com.vyom.repository.FundingRepository;
import com.vyom.repository.IdempotencyRecordRepository;
import com.vyom.repository.TransactionRepository;
import com.vyom.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@ConditionalOnProperty(name = "vyom.idempotency.janitor.enabled", havingValue = "true", matchIfMissing = true)
public class IdempotencyJanitorService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyJanitorService.class);
    public static final long JANITOR_ADVISORY_LOCK_ID = 8899112233L;

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final TransferRepository transferRepository;
    private final FundingRepository fundingRepository;
    private final TransactionRepository transactionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Duration timeoutDuration;

    public IdempotencyJanitorService(IdempotencyRecordRepository idempotencyRecordRepository,
                                     TransferRepository transferRepository,
                                     FundingRepository fundingRepository,
                                     TransactionRepository transactionRepository,
                                     JdbcTemplate jdbcTemplate,
                                     @Value("${vyom.idempotency.timeout-seconds:120}") long timeoutSeconds) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.transferRepository = transferRepository;
        this.fundingRepository = fundingRepository;
        this.transactionRepository = transactionRepository;
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

        List<IdempotencyRecord> staleRecords = idempotencyRecordRepository.findAbandonedProcessingRecords(staleThreshold);
        if (staleRecords.isEmpty()) {
            log.trace("Idempotency janitor: no stale PROCESSING records found older than {}", staleThreshold);
            return 0;
        }

        log.info("Idempotency janitor: found {} stale PROCESSING records older than {}", staleRecords.size(), staleThreshold);
        int sweptCount = 0;

        for (IdempotencyRecord record : staleRecords) {
            boolean entityCommitted = false;
            if (record.getTransferId() != null && transferRepository.existsById(record.getTransferId())) {
                entityCommitted = true;
            } else if (record.getFundingId() != null && fundingRepository.existsById(record.getFundingId())) {
                entityCommitted = true;
            } else if (record.getTransactionId() != null && transactionRepository.existsById(record.getTransactionId())) {
                entityCommitted = true;
            }

            if (entityCommitted) {
                log.warn("Idempotency janitor: found committed entity for stale record id={}, key={}. Marking COMPLETED.",
                        record.getId(), record.getIdempotencyKey());
                record.setStatus(IdempotencyStatus.COMPLETED);
            } else {
                log.info("Idempotency janitor: marking uncommitted abandoned record id={}, key={}, userId={} as FAILED.",
                        record.getId(), record.getIdempotencyKey(), record.getUserId());
                record.setStatus(IdempotencyStatus.FAILED);
                record.setResponseStatusCode(504);
                record.setResponseBody("{\"status\":504,\"error\":\"PROCESSING_TIMEOUT\",\"message\":\"Transaction processing timed out and was abandoned\"}");
            }
            record.setUpdatedAt(now);
            record.setVersion(record.getVersion() + 1);
            idempotencyRecordRepository.save(record);
            sweptCount++;
        }

        log.info("Idempotency janitor successfully processed {} stale records", sweptCount);
        return sweptCount;
    }
}
