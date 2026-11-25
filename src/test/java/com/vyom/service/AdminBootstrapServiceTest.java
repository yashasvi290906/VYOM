package com.vyom.service;

import com.vyom.dto.request.RegisterRequest;
import com.vyom.entity.User;
import com.vyom.entity.enums.Role;
import com.vyom.repository.UserRepository;
import com.vyom.security.JwtTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private JwtTokenService jwtTokenService;

    @Test
    @DisplayName("1. Bootstrap disabled: does nothing")
    void shouldDoNothingWhenBootstrapDisabled() {
        AdminBootstrapService service = new AdminBootstrapService(
                userRepository, passwordEncoder, jdbcTemplate,
                false, "admin@vyom.internal", "SecureAdminP@ss123!", "Admin"
        );

        service.run();

        verifyNoInteractions(userRepository);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("2. Bootstrap enabled with valid configuration: provisions admin")
    void shouldProvisionAdminWhenEnabledAndValid() {
        AdminBootstrapService service = new AdminBootstrapService(
                userRepository, passwordEncoder, jdbcTemplate,
                true, "admin@vyom.internal", "SecureAdminP@ss123!", "System Admin"
        );

        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("admin@vyom.internal")).thenReturn(false);
        when(passwordEncoder.encode("SecureAdminP@ss123!")).thenReturn("$2a$12$hashedAdminPassword");

        service.run();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getEmail()).isEqualTo("admin@vyom.internal");
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getFullName()).isEqualTo("System Admin");
    }

    @Test
    @DisplayName("3. Missing password: skips provisioning with warning")
    void shouldSkipWhenPasswordIsMissing() {
        AdminBootstrapService service = new AdminBootstrapService(
                userRepository, passwordEncoder, jdbcTemplate,
                true, "admin@vyom.internal", "", "System Admin"
        );

        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);

        service.run();

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("4. Weak password: fails with exception")
    void shouldThrowExceptionWhenPasswordIsWeak() {
        AdminBootstrapService service = new AdminBootstrapService(
                userRepository, passwordEncoder, jdbcTemplate,
                true, "admin@vyom.internal", "weak", "System Admin"
        );

        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);

        assertThatThrownBy(service::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 12 characters");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("5. Existing administrator: skips bootstrap")
    void shouldSkipWhenAdminAlreadyExists() {
        AdminBootstrapService service = new AdminBootstrapService(
                userRepository, passwordEncoder, jdbcTemplate,
                true, "admin@vyom.internal", "SecureAdminP@ss123!", "System Admin"
        );

        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(true);

        service.run();

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("6. Concurrent bootstrap attempts acquire advisory lock")
    void shouldAcquireAdvisoryLockOnBootstrap() {
        AdminBootstrapService service = new AdminBootstrapService(
                userRepository, passwordEncoder, jdbcTemplate,
                true, "admin@vyom.internal", "SecureAdminP@ss123!", "System Admin"
        );

        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("admin@vyom.internal")).thenReturn(false);
        when(passwordEncoder.encode("SecureAdminP@ss123!")).thenReturn("$2a$12$hashedAdminPassword");

        service.run();

        verify(jdbcTemplate).execute(contains("pg_advisory_xact_lock(7429103)"));
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("7. Existing non-admin user with same email: will not silently elevate to admin")
    void shouldNotElevateExistingNonAdminUser() {
        AdminBootstrapService service = new AdminBootstrapService(
                userRepository, passwordEncoder, jdbcTemplate,
                true, "existing@vyom.internal", "SecureAdminP@ss123!", "System Admin"
        );

        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("existing@vyom.internal")).thenReturn(true);

        service.run();

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("8. Normal customer registration cannot assign ADMIN role")
    void normalRegistrationCannotObtainAdminRole() {
        UserService userService = new UserService(userRepository, passwordEncoder, jwtTokenService);

        RegisterRequest request = new RegisterRequest("normal@vyom.internal", "CustomerP@ss123!", "Customer User");
        when(userRepository.existsByEmailIgnoreCase("normal@vyom.internal")).thenReturn(false);
        when(passwordEncoder.encode("CustomerP@ss123!")).thenReturn("$2a$12$hashedCustomerPassword");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(userCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtTokenService.generateToken(any(), any(), any(), eq(Role.USER))).thenReturn("token.user");

        userService.register(request);

        User registered = userCaptor.getValue();
        assertThat(registered.getRole()).isEqualTo(Role.USER);
    }
}
