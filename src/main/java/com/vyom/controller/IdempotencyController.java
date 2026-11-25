package com.vyom.controller;

import com.vyom.dto.response.IdempotencyStatusResponse;
import com.vyom.security.SecurityUtils;
import com.vyom.service.IdempotencyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/idempotency")
public class IdempotencyController {

    private final IdempotencyService idempotencyService;

    public IdempotencyController(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/status/{key}")
    public ResponseEntity<IdempotencyStatusResponse> getStatus(@PathVariable("key") String key) {
        UUID userId = SecurityUtils.getCurrentUserId();
        return idempotencyService.getStatus(userId, key)
                .map(status -> ResponseEntity.ok(status))
                .orElseGet(() -> ResponseEntity.status(404).body(IdempotencyStatusResponse.notFound(key)));
    }
}
