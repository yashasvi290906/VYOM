package com.vyom.service;

import com.vyom.dto.request.LoginRequest;
import com.vyom.dto.request.RegisterRequest;
import com.vyom.dto.response.AuthResponse;
import com.vyom.dto.response.UserResponse;
import com.vyom.entity.User;
import com.vyom.exception.DuplicateIdentityException;
import com.vyom.repository.UserRepository;
import com.vyom.security.JwtTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    // Precomputed valid BCrypt cost-12 hash used to maintain constant-time execution on nonexistent accounts
    private static final String DUMMY_BCRYPT_HASH = "$2a$12$e8kZ1qZ0hW.Z4j2xR0Y1e.t6vCq8dO2hB1iB9xJ4tZ2kZ3a4b5c6d";

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 60_000L;
    private static final long ATTEMPT_WINDOW_MS = 300_000L;
    private static final int MAX_TRACKED_ACCOUNTS = 5000;

    private static class LoginAttemptTracker {
        int failedCount;
        long lastAttemptTime;
        long lockoutUntil;

        LoginAttemptTracker(long now) {
            this.failedCount = 1;
            this.lastAttemptTime = now;
            this.lockoutUntil = 0;
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<String, LoginAttemptTracker> failedLoginAttempts = new java.util.concurrent.ConcurrentHashMap<>();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String normalizedEmail = request.email() != null ? request.email().toLowerCase().trim() : "";

        if (request.password() == null || request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password exceeds maximum allowed length of 72 bytes");
        }

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            log.warn("Registration rejected: Email '{}' already exists", normalizedEmail);
            throw new DuplicateIdentityException("An account with this email address already exists");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(normalizedEmail, passwordHash, request.fullName() != null ? request.fullName().trim() : "");
        User saved = userRepository.save(user);

        log.info("Registered new customer identity {} with email '{}'", saved.getId(), saved.getEmail());
        String token = jwtTokenService.generateToken(saved.getId(), saved.getEmail(), saved.getFullName(), saved.getRole());

        return AuthResponse.of(token, saved.getId(), saved.getEmail(), saved.getFullName());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.email() != null ? request.email().toLowerCase().trim() : "";
        String rawPassword = request.password() != null ? request.password() : "";

        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            log.warn("Login rejected: Password exceeds maximum 72 UTF-8 bytes for email '{}'", normalizedEmail);
            throw new BadCredentialsException("Invalid email or password");
        }

        long now = System.currentTimeMillis();
        LoginAttemptTracker tracker = failedLoginAttempts.get(normalizedEmail);
        if (tracker != null && tracker.lockoutUntil > now) {
            long remainingSec = Math.max(1, (tracker.lockoutUntil - now) / 1000);
            log.warn("Login throttled: Account '{}' temporarily locked for {} more seconds", normalizedEmail, remainingSec);
            throw new BadCredentialsException("Account temporarily locked due to multiple failed login attempts. Please try again later.");
        }

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(normalizedEmail);
        if (userOpt.isEmpty()) {
            recordFailedAttempt(normalizedEmail, now);
            // Constant-time execution to prevent timing-based user account enumeration
            passwordEncoder.matches(rawPassword, DUMMY_BCRYPT_HASH);
            log.warn("Login failed: User not found for email '{}'", normalizedEmail);
            throw new BadCredentialsException("Invalid email or password");
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            recordFailedAttempt(normalizedEmail, now);
            log.warn("Login failed: Password mismatch for email '{}'", normalizedEmail);
            throw new BadCredentialsException("Invalid email or password");
        }

        failedLoginAttempts.remove(normalizedEmail);
        log.info("Customer {} successfully authenticated with email '{}'", user.getId(), user.getEmail());
        String token = jwtTokenService.generateToken(user.getId(), user.getEmail(), user.getFullName(), user.getRole());

        return AuthResponse.of(token, user.getId(), user.getEmail(), user.getFullName());
    }

    private void recordFailedAttempt(String email, long now) {
        if (email.isEmpty()) return;
        if (failedLoginAttempts.size() > MAX_TRACKED_ACCOUNTS) {
            failedLoginAttempts.entrySet().removeIf(entry -> now - entry.getValue().lastAttemptTime > ATTEMPT_WINDOW_MS);
        }
        failedLoginAttempts.compute(email, (k, existing) -> {
            if (existing == null || now - existing.lastAttemptTime > ATTEMPT_WINDOW_MS) {
                return new LoginAttemptTracker(now);
            }
            existing.failedCount++;
            existing.lastAttemptTime = now;
            if (existing.failedCount >= MAX_FAILED_ATTEMPTS) {
                existing.lockoutUntil = now + LOCKOUT_DURATION_MS;
            }
            return existing;
        });
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        return UserResponse.from(user);
    }
}
