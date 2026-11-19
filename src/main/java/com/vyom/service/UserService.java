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

import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

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
        String normalizedEmail = request.email().toLowerCase().trim();

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            log.warn("Registration rejected: Email '{}' already exists", normalizedEmail);
            throw new DuplicateIdentityException("An account with this email address already exists");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(normalizedEmail, passwordHash, request.fullName().trim());
        User saved = userRepository.save(user);

        log.info("Registered new customer identity {} with email '{}'", saved.getId(), saved.getEmail());
        String token = jwtTokenService.generateToken(saved.getId(), saved.getEmail(), saved.getFullName(), saved.getRole());

        return AuthResponse.of(token, saved.getId(), saved.getEmail(), saved.getFullName());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.email().toLowerCase().trim();

        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> {
                    log.warn("Login failed: User not found for email '{}'", normalizedEmail);
                    return new BadCredentialsException("Invalid email or password");
                });

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Login failed: Password mismatch for email '{}'", normalizedEmail);
            throw new BadCredentialsException("Invalid email or password");
        }

        log.info("Customer {} successfully authenticated with email '{}'", user.getId(), user.getEmail());
        String token = jwtTokenService.generateToken(user.getId(), user.getEmail(), user.getFullName(), user.getRole());

        return AuthResponse.of(token, user.getId(), user.getEmail(), user.getFullName());
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        return UserResponse.from(user);
    }
}
