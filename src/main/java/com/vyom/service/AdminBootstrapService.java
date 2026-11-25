package com.vyom.service;

import com.vyom.entity.User;
import com.vyom.entity.enums.Role;
import com.vyom.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Service
public class AdminBootstrapService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);

    private static final Pattern HAS_UPPER = Pattern.compile("[A-Z]");
    private static final Pattern HAS_LOWER = Pattern.compile("[a-z]");
    private static final Pattern HAS_DIGIT = Pattern.compile("[0-9]");
    private static final Pattern HAS_SPECIAL = Pattern.compile("[^A-Za-z0-9]");

    // PostgreSQL transactional advisory lock ID for serializing multi-instance admin bootstrap
    private static final long BOOTSTRAP_ADVISORY_LOCK_ID = 7429103L;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final boolean bootstrapEnabled;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminFullName;

    @org.springframework.beans.factory.annotation.Autowired
    public AdminBootstrapService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate,
            @Value("${vyom.admin.bootstrap.enabled:${ADMIN_BOOTSTRAP_ENABLED:false}}") boolean bootstrapEnabled,
            @Value("${vyom.admin.bootstrap.email:${ADMIN_BOOTSTRAP_EMAIL:${ADMIN_EMAIL:}}}") String adminEmail,
            @Value("${vyom.admin.bootstrap.password:${VYOM_ADMIN_BOOTSTRAP_PASSWORD:${ADMIN_BOOTSTRAP_PASSWORD:${ADMIN_PASSWORD:}}}}") String adminPassword,
            @Value("${vyom.admin.bootstrap.full-name:${ADMIN_BOOTSTRAP_FULL_NAME:System Administrator}}") String adminFullName) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.bootstrapEnabled = bootstrapEnabled;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminFullName = adminFullName;
    }

    public AdminBootstrapService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            boolean bootstrapEnabled,
            String adminEmail,
            String adminPassword,
            String adminFullName) {
        this(userRepository, passwordEncoder, null, bootstrapEnabled, adminEmail, adminPassword, adminFullName);
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!bootstrapEnabled) {
            log.debug("Administrator bootstrap is disabled.");
            return;
        }

        // Concurrency lock: Acquire transaction-scoped advisory lock to serialize multi-instance startup
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + BOOTSTRAP_ADVISORY_LOCK_ID + ")");
                log.debug("Acquired PostgreSQL advisory transaction lock {} for admin bootstrap serialization", BOOTSTRAP_ADVISORY_LOCK_ID);
            } catch (Exception e) {
                log.warn("Could not acquire advisory lock for admin bootstrap: {}", e.getMessage());
            }
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
                || adminPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !HAS_UPPER.matcher(adminPassword).find()
                || !HAS_LOWER.matcher(adminPassword).find()
                || !HAS_DIGIT.matcher(adminPassword).find()
                || !HAS_SPECIAL.matcher(adminPassword).find()) {
            throw new IllegalStateException("Bootstrap administrator password must be at least 12 characters (and at most 72 bytes) and contain uppercase, lowercase, digit, and special character.");
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
