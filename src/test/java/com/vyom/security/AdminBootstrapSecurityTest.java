package com.vyom.security;

import com.vyom.dto.request.RegisterRequest;
import com.vyom.dto.response.AuthResponse;
import com.vyom.entity.User;
import com.vyom.entity.enums.Role;
import com.vyom.integration.BaseIntegrationTest;
import com.vyom.repository.UserRepository;
import com.vyom.service.AdminBootstrapService;
import com.vyom.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AdminBootstrapSecurityTest extends BaseIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Admin Bootstrap: Disabled by default produces zero accounts")
    void bootstrapDisabledByDefaultProducesNoAccounts() {
        AdminBootstrapService service = new AdminBootstrapService(
                userRepository, passwordEncoder, false, "bootstrap@vyom.internal", "SecureAdminPass123!", "Admin"
        );
        service.run();
        assertThat(userRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("Admin Bootstrap: Enforces 12-char length and character complexity")
    void bootstrapEnforcesPasswordComplexity() {
        // Less than 12 chars
        AdminBootstrapService shortPassService = new AdminBootstrapService(
                userRepository, passwordEncoder, true, "bootstrap@vyom.internal", "Short1!", "Admin"
        );
        assertThatThrownBy(shortPassService::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 12 characters");

        // Missing special char
        AdminBootstrapService noSpecialService = new AdminBootstrapService(
                userRepository, passwordEncoder, true, "bootstrap@vyom.internal", "NoSpecialChar12345", "Admin"
        );
        assertThatThrownBy(noSpecialService::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("uppercase, lowercase, digit, and special character");

        // Missing digit
        AdminBootstrapService noDigitService = new AdminBootstrapService(
                userRepository, passwordEncoder, true, "bootstrap@vyom.internal", "NoDigitInThisPass!", "Admin"
        );
        assertThatThrownBy(noDigitService::run)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Admin Bootstrap: Does not overwrite or recreate an existing administrator")
    void bootstrapCannotOverwriteExistingAdmin() {
        // Pre-create an admin
        User existingAdmin = new User(
                "existing.admin@vyom.internal",
                passwordEncoder.encode("ExistingAdminPass123!"),
                "Existing Administrator",
                Role.ADMIN
        );
        userRepository.save(existingAdmin);

        String originalHash = existingAdmin.getPasswordHash();

        // Attempt bootstrap with new credentials
        AdminBootstrapService service = new AdminBootstrapService(
                userRepository, passwordEncoder, true, "new.admin@vyom.internal", "BrandNewPass123!", "New Admin"
        );
        service.run();

        // Should not have created new.admin and should not have altered existingAdmin
        assertThat(userRepository.existsByEmailIgnoreCase("new.admin@vyom.internal")).isFalse();
        User adminInDb = userRepository.findByEmailIgnoreCase("existing.admin@vyom.internal").orElseThrow();
        assertThat(adminInDb.getPasswordHash()).isEqualTo(originalHash);
        assertThat(adminInDb.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("Admin Bootstrap: Successful controlled one-time provisioning when enabled")
    void bootstrapProvisionsInitialAdminOnce() {
        AdminBootstrapService service = new AdminBootstrapService(
                userRepository, passwordEncoder, true, "root.admin@vyom.internal", "ComplexAdminPassword123#", "Root Admin"
        );
        service.run();

        assertThat(userRepository.countByRole(Role.ADMIN)).isEqualTo(1);
        User created = userRepository.findByEmailIgnoreCase("root.admin@vyom.internal").orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.ADMIN);
        assertThat(created.getFullName()).isEqualTo("Root Admin");
        assertThat(passwordEncoder.matches("ComplexAdminPassword123#", created.getPasswordHash())).isTrue();

        // Calling run again must be an idempotent no-op
        service.run();
        assertThat(userRepository.countByRole(Role.ADMIN)).isEqualTo(1);
    }

    @Test
    @DisplayName("Privilege Escalation Defense: Normal registration cannot assign ADMIN role")
    void normalRegistrationCannotAssignAdminRole() {
        RegisterRequest request = new RegisterRequest(
                "customer.attempt@vyom.test",
                "CustomerPass123!",
                "Inquisitive Customer"
        );
        AuthResponse response = userService.register(request);

        User savedUser = userRepository.findById(response.userId()).orElseThrow();
        assertThat(savedUser.getRole()).isEqualTo(Role.USER);
        assertThat(savedUser.getRole()).isNotEqualTo(Role.ADMIN);
    }
}
