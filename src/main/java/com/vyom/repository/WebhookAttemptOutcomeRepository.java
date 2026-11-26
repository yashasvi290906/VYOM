package com.vyom.repository;

import com.vyom.entity.WebhookAttemptOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookAttemptOutcomeRepository extends JpaRepository<WebhookAttemptOutcome, UUID> {

    Optional<WebhookAttemptOutcome> findByAttemptId(UUID attemptId);

    boolean existsByAttemptId(UUID attemptId);

    List<WebhookAttemptOutcome> findByAttemptIdIn(Collection<UUID> attemptIds);
}
