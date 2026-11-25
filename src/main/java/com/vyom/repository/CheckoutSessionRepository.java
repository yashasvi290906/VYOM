package com.vyom.repository;

import com.vyom.entity.CheckoutSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CheckoutSessionRepository extends JpaRepository<CheckoutSession, UUID> {

    Optional<CheckoutSession> findBySessionToken(String sessionToken);

    Optional<CheckoutSession> findByOrderId(UUID orderId);
}
