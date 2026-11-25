package com.vyom.service;

import com.vyom.dto.response.MerchantAuditLogResponse;
import com.vyom.dto.response.PagedResponse;
import com.vyom.entity.MerchantAuditLog;
import com.vyom.entity.User;
import com.vyom.entity.enums.MerchantAuditEventType;
import com.vyom.repository.MerchantAuditLogRepository;
import com.vyom.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MerchantAuditService {

    private static final Logger log = LoggerFactory.getLogger(MerchantAuditService.class);

    private final MerchantAuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public MerchantAuditService(MerchantAuditLogRepository auditLogRepository,
                                UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvent(UUID merchantId, UUID actorUserId, MerchantAuditEventType eventType, String description, String metadata) {
        MerchantAuditLog entry = new MerchantAuditLog(merchantId, actorUserId, eventType, description, metadata);
        auditLogRepository.save(entry);
        log.info("Merchant Audit: merchant={}, actor={}, event={}, desc={}", merchantId, actorUserId, eventType, description);
    }

    @Transactional(readOnly = true)
    public PagedResponse<MerchantAuditLogResponse> getAuditLogs(UUID merchantId, Pageable pageable) {
        Page<MerchantAuditLog> page = auditLogRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);

        List<UUID> actorIds = page.getContent().stream()
                .map(MerchantAuditLog::getActorUserId)
                .distinct()
                .toList();

        Map<UUID, String> userNames = userRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName, (a, b) -> a));

        List<MerchantAuditLogResponse> dtoList = page.getContent().stream()
                .map(entry -> MerchantAuditLogResponse.from(entry, userNames.getOrDefault(entry.getActorUserId(), "Unknown Actor")))
                .toList();

        return new PagedResponse<>(
                dtoList,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext()
        );
    }
}
