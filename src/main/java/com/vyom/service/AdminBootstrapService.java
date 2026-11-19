package com.vyom.service;

import com.vyom.entity.User;
import com.vyom.entity.enums.Role;
import com.vyom.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

@Service
public class AdminBootstrapService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);

    private static final Pattern HAS_UPPER = Pattern.compile("[A-Z]");
    private static final Pattern HAS_LOWER = Pattern.compile("[a-z]");
    private static final Pattern HAS_DIGIT = Pattern.compile("[0-9]");
    private static final Pattern HAS_SPECIAL = Pattern.compile("[^A-Za-z0-9]");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean bootstrapEnabled;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminFullName;

    public AdminBootstrapService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${vyom.admin.bootstrap.enabled:false}") boolean bootstrapEnabled,
            @Value("${vyom.admin.bootstrap.email:}") String adminEmail,
            @Value("${vyom.admin.bootstrap.password:}") String adminPassword,
            @Value("${vyom.admin.bootstrap.full-name:System Administrator}") String adminFullName) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapEnabled = bootstrapEnabled;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminFullName = adminFullName;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!bootstrapEnabled) {
            log.debug("Administrator bootstrap is disabled.");
            return;
        }

        // Rule: Bootstrap cannot silently recreate or reset an existing administrator.
        if (userRepository.existsByRole(Role.ADMIN)) {
            log.info("Auditable Event: Administrator bootstrap skipped. One or more administrator identities already exist in the database.");
            return;
        }

        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            log.warn("Administrator bootstrap enabled but email or password not specified; skipping provisioning.");
            return;
        }

        if (adminPassword.length() < 12
                || !HAS_UPPER.matcher(adminPassword).find()
                || !HAS_LOWER.matcher(adminPassword).find()
                || !HAS_DIGIT.matcher(adminPassword).find()
                || !HAS_SPECIAL.matcher(adminPassword).find()) {
            throw new IllegalStateException("Bootstrap administrator password must be at least 12 characters and contain uppercase, lowercase, digit, and special character.");
        }

        String normalizedEmail = adminEmail.toLowerCase().trim();
        String maskedEmail = maskEmail(normalizedEmail);

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            log.warn("Auditable Event: Bootstrap failed. User with email '{}' already exists with non-admin role. Will not silently elevate or reset.", maskedEmail);
            return;
        }

        User newAdmin = new User(
                normalizedEmail,
                passwordEncoder.encode(adminPassword),
                adminFullName != null ? adminFullName.trim() : "System Administrator",
                Role.ADMIN
        );
        userRepository.save(newAdmin);
        log.info("Auditable Event: Initial system administrator provisioned with email '{}' and role ADMIN. Credentials are not logged.", maskedEmail);
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int atIdx = email.indexOf('@');
        String prefix = email.substring(0, Math.min(2, atIdx));
        return prefix + "***" + email.substring(atIdx);
    }
}
