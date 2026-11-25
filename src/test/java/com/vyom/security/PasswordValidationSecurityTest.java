package com.vyom.security;

import com.vyom.dto.request.LoginRequest;
import com.vyom.dto.request.RegisterRequest;
import com.vyom.entity.User;
import com.vyom.repository.UserRepository;
import com.vyom.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordValidationSecurityTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenService jwtTokenService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, jwtTokenService);
    }

    @Test
    @DisplayName("Should permit registration and login with valid password exactly 72 bytes")
    void shouldPermitPasswordAt72ByteBoundary() {
        String exact72BytePassword = "A".repeat(72);
        assertThat(exact72BytePassword.getBytes(StandardCharsets.UTF_8)).hasSize(72);

        when(userRepository.existsByEmailIgnoreCase("user72@vyom.internal")).thenReturn(false);
        when(passwordEncoder.encode(exact72BytePassword)).thenReturn("$2a$12$hashedPassword");

        User savedUser = new User("user72@vyom.internal", "$2a$12$hashedPassword", "Boundary User");
        savedUser.setId(UUID.randomUUID());
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtTokenService.generateToken(any(), any(), any(), any())).thenReturn("mock.token.jwt");

        // Register succeeds
        var authRes = userService.register(new RegisterRequest("user72@vyom.internal", exact72BytePassword, "Boundary User"));
        assertThat(authRes).isNotNull();

        // Login succeeds
        when(userRepository.findByEmailIgnoreCase("user72@vyom.internal")).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches(eq(exact72BytePassword), eq("$2a$12$hashedPassword"))).thenReturn(true);

        var loginRes = userService.login(new LoginRequest("user72@vyom.internal", exact72BytePassword));
        assertThat(loginRes).isNotNull();
    }

    @Test
    @DisplayName("Registration should reject password exceeding 72 bytes")
    void shouldRejectRegistrationPasswordExceeding72Bytes() {
        String oversizedPassword = "A".repeat(73);
        assertThat(oversizedPassword.getBytes(StandardCharsets.UTF_8)).hasSize(73);

        RegisterRequest request = new RegisterRequest("oversized@vyom.internal", oversizedPassword, "Oversized User");

        assertThatThrownBy(() -> userService.register(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("72 bytes");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Login should reject password exceeding 72 bytes without calling BCrypt matcher (F01)")
    void shouldRejectLoginPasswordExceeding72BytesWithoutMatching() {
        String oversizedPassword = "A".repeat(73);
        assertThat(oversizedPassword.getBytes(StandardCharsets.UTF_8)).hasSize(73);

        LoginRequest request = new LoginRequest("victim@vyom.internal", oversizedPassword);

        assertThatThrownBy(() -> userService.login(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");

        // Crucial verification: passwordEncoder.matches MUST NEVER be called with oversized password
        verify(passwordEncoder, never()).matches(any(), any());
        verify(userRepository, never()).findByEmailIgnoreCase(any());
    }

    @Test
    @DisplayName("Login should reject multibyte UTF-8 password that exceeds 72 bytes even if char count <= 72")
    void shouldRejectMultibyteUtf8PasswordExceeding72Bytes() {
        // '€' is 3 bytes in UTF-8. 25 characters = 75 bytes (> 72 bytes)
        String multibytePassword = "€".repeat(25);
        assertThat(multibytePassword.length()).isEqualTo(25);
        assertThat(multibytePassword.getBytes(StandardCharsets.UTF_8)).hasSize(75);

        LoginRequest request = new LoginRequest("multibyte@vyom.internal", multibytePassword);

        assertThatThrownBy(() -> userService.login(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");

        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    @DisplayName("Should prevent BCrypt truncation attack where longer password shares first 72 bytes")
    void shouldPreventBcryptTruncationAttack() {
        String base72 = "CorrectHorseBatteryStaple1234567890!@#$%^&*()_+-=[]{}|;:,.<>?123456789012345678";
        // Ensure base72 is exactly 72 bytes
        String exact72 = base72.substring(0, 72);
        assertThat(exact72.getBytes(StandardCharsets.UTF_8)).hasSize(72);

        String maliciousLonger = exact72 + "ExtraAttackerSuffix";
        assertThat(maliciousLonger.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);

        // Attacker attempts to login with maliciousLonger against victim's account
        LoginRequest attackRequest = new LoginRequest("victim@vyom.internal", maliciousLonger);

        assertThatThrownBy(() -> userService.login(attackRequest))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");

        // Verify attacker's payload never reached BCrypt matching
        verify(passwordEncoder, never()).matches(eq(maliciousLonger), any());
    }

    @Test
    @DisplayName("Should throttle account after 5 consecutive failed login attempts (F18)")
    void shouldThrottleAccountAfterMaxFailedAttempts() {
        String email = "brute@vyom.internal";
        when(userRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.empty());

        // Perform 5 failed attempts
        for (int i = 0; i < 5; i++) {
            final int attempt = i;
            assertThatThrownBy(() -> userService.login(new LoginRequest(email, "WrongPass" + attempt + "!")))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessage("Invalid email or password");
        }

        // 6th attempt should be throttled before repository lookup
        assertThatThrownBy(() -> userService.login(new LoginRequest(email, "AnyPassword!")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("temporarily locked");

        // Verify repository was only called 5 times
        verify(userRepository, times(5)).findByEmailIgnoreCase(email);
    }
}
