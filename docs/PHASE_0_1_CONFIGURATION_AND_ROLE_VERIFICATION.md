# VYOM — Phase 0.1: Configuration & Database Role Verification Report

**Phase:** Phase 0.1 (Independent Acceptance Audit & Foundation Gate)  
**Date:** 2026-11-19  
**Auditor:** Independent Systems & Security Audit  
**Target Repository:** `D:\VYOM`  
**Configuration Files:** `src/main/resources/application.yml`, `gateway/src/config/index.js`  
**Provisioning Script:** `scripts/setup_least_privilege_user.sql`  
**Verification Suites:** `PortResolutionConfigurationTest.java`, `portResolution.test.js`, `DatabaseLeastPrivilegeTest.java`  

---

## 1. Executive Summary

This report documents the resolution and verification of two fundamental infrastructure controls in VYOM:
1. **Port Resolution & Process Isolation:** Eliminating configuration ambiguities and port collision hazards between the Express API Gateway and the Spring Boot backend.
2. **Database Role Segregation & Least Privilege:** Enforcing and empirically proving role-based access control (RBAC) in PostgreSQL to ensure the runtime application cannot bypass ledger immutability, tamper with user identity credentials, or execute arbitrary DDL.

### Verification Verdict: **ALL CHECKS PASSED**
- **Port Resolution:** Disambiguated and verified. Spring Boot binds strictly to `${BACKEND_PORT:8080}` and ignores generic `PORT=3000`. Gateway implements strict port validation (`1..65535`).
- **Database Least Privilege:** Confirmed via PostgreSQL catalog privileges and direct integration testing. `vyom_app` has zero DDL privileges, cannot alter or delete ledger entries, and cannot modify security columns on the `users` table.

---

## 2. Port Resolution & Process Isolation Audit

### 2.1 The Port Collision Hazard
In distributed and containerized architectures, web services frequently rely on environment variables to assign HTTP listening ports.
Prior to this remediation, VYOM exhibited a latent port collision vulnerability:
- **Gateway Configuration (`gateway/src/config/index.js`):**
  ```javascript
  port: parseInt(process.env.GATEWAY_PORT, 10) || parseInt(process.env.PORT, 10) || 3000
  ```
- **Backend Configuration (`src/main/resources/application.yml`):**
  ```yaml
  server:
    port: ${BACKEND_PORT:${PORT:8080}}
  ```

**Collision Mechanism:**
When an environment variable `PORT=3000` was present in the shell (a standard convention in Node.js environments and cloud platforms like Heroku and Render):
1. The Express Gateway evaluated `parseInt(process.env.PORT, 10)` and bound to port 3000.
2. The Spring Boot backend evaluated `${PORT:8080}`, resolved `PORT` to `3000`, and attempted to bind to port 3000.
3. This resulted in an immediate `java.net.BindException: Address already in use: bind`, crashing the backend service during boot.

---

### 2.2 Remediation Implementation

#### 1. Backend Port Precedence Hardening (`application.yml`)
The port resolution expression in `src/main/resources/application.yml` was updated:
```yaml
server:
  port: ${BACKEND_PORT:${SERVER_PORT:8080}}
  address: ${SERVER_ADDRESS:127.0.0.1}
```
**Precedence Hierarchy:**
1. `BACKEND_PORT`: Explicit, dedicated environment variable for the backend service.
2. `SERVER_PORT`: Standard Spring Boot environment property.
3. `8080`: Default listening port.
*Note: The generic environment variable `PORT` is completely decoupled from Spring Boot, eliminating collision if `PORT=3000` is defined in the environment.*

#### 2. Gateway Port Resolution & Validation Hardening (`gateway/src/config/index.js`)
Implemented dedicated `resolvePort` function with range validation:
```javascript
function resolvePort(explicitPort, fallbackPort, defaultPort) {
  const candidate = (explicitPort !== undefined && explicitPort !== null && String(explicitPort).trim() !== '')
    ? explicitPort
    : ((fallbackPort !== undefined && fallbackPort !== null && String(fallbackPort).trim() !== '') ? fallbackPort : defaultPort);

  const parsed = Number(candidate);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65535) {
    throw new Error(`FATAL: Port configuration value "${candidate}" is invalid. Must be an integer between 1 and 65535.`);
  }
  return parsed;
}
```
**Gateway Precedence:**
1. `GATEWAY_PORT`: Explicit gateway port override.
2. `PORT`: Generic port environment variable.
3. `3000`: Default listening port.
4. Range validation: Rejects values `< 1`, `> 65535`, decimals, and non-numeric strings with a fatal startup error.

---

### 2.3 Automated Test Evidence

#### Backend Configuration Tests (`PortResolutionConfigurationTest.java`)
```text
[INFO] Running com.vyom.integration.PortResolutionConfigurationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.401 s
```
- **Scenario 1 (Default):** Resolves to `8080`.
- **Scenario 2 (Generic PORT=3000):** Ignored by backend; resolves to `8080`.
- **Scenario 3 (SERVER_PORT=9090):** Resolves to `9090`.
- **Scenario 4 (BACKEND_PORT=7070, SERVER_PORT=9090, PORT=3000):** `BACKEND_PORT` takes precedence; resolves to `7070`.

#### Gateway Configuration Tests (`gateway/tests/portResolution.test.js`)
```text
▶ Gateway & Backend Port Resolution and Validation
  ✔ Scenario 1: Default configuration returns port 3000 (0.7351ms)
  ✔ Scenario 2: Explicit GATEWAY_PORT takes highest precedence (0.138ms)
  ✔ Scenario 3: Generic PORT used when GATEWAY_PORT is unset (0.1045ms)
  ✔ Scenario 3b: Empty or whitespace GATEWAY_PORT falls back to PORT (0.1072ms)
  ✔ Scenario 4: Boundary values 1 and 65535 are accepted (1.3033ms)
  ✔ Scenario 5: Out of range ports throw fatal configuration error (0.5628ms)
  ✔ Scenario 6: Non-numeric and decimal port values throw fatal error (0.247ms)
✔ Gateway & Backend Port Resolution and Validation (4.3151ms)
```

---

## 3. Database Role Segregation & Least Privilege Audit

### 3.1 Role Architecture & Security Design
VYOM enforces a two-tier database security model defined in `scripts/setup_least_privilege_user.sql`:

1. **Migration Role (`vyom_flyway`):**
   - **Role Purpose:** Dedicated database migration identity used during CI/CD and deployment.
   - **Privileges:** `USAGE, CREATE` on schema `public`. Full DDL privileges (table creation, alteration, index creation, trigger management, routine creation). Table owner of all relations.
2. **Runtime Application Role (`vyom_app`):**
   - **Role Purpose:** Least-privilege identity used by the running Spring Boot application during normal request processing.
   - **Privileges:** `USAGE` on schema `public` only. Strict DML privileges bounded by principle of least privilege. Zero DDL privileges.

---

### 3.2 Privilege Matrix

The following table documents the verified PostgreSQL catalog permissions (`information_schema.role_table_grants` and `information_schema.column_privileges`) for `vyom_app`:

| Table Name | Granted Permissions | Forbidden Operations | Security Rationale |
| :--- | :--- | :--- | :--- |
| `ledger_entries` | `SELECT`, `INSERT` | `UPDATE`, `DELETE`, `TRUNCATE`, `DROP` | **Ledger Immutability:** The financial ledger is strictly append-only. No historical entries can be modified or purged at the database layer. |
| `users` | `SELECT`, `INSERT`, `UPDATE (full_name, updated_at)` | `UPDATE (role, password_hash, email)`, `DELETE` | **Identity Protection:** Column-level security ensures the application cannot be coerced (e.g. via SQL injection) into escalating user roles or altering password hashes. |
| `wallets` | `SELECT`, `INSERT`, `UPDATE` | `DELETE`, `TRUNCATE`, `DROP` | Financial balance tracking. |
| `transfers` | `SELECT`, `INSERT`, `UPDATE` | `DELETE`, `TRUNCATE`, `DROP` | Transfer audit records. |
| `fundings` | `SELECT`, `INSERT`, `UPDATE` | `DELETE`, `TRUNCATE`, `DROP` | Funding records. |
| `transactions` | `SELECT`, `INSERT`, `UPDATE` | `DELETE`, `TRUNCATE`, `DROP` | Master transaction logs. |
| `refunds` | `SELECT`, `INSERT`, `UPDATE` | `DELETE`, `TRUNCATE`, `DROP` | Refund transaction logs. |
| `disputes` | `SELECT`, `INSERT`, `UPDATE` | `DELETE`, `TRUNCATE`, `DROP` | Dispute lifecycle. |
| `idempotency_records`| `SELECT`, `INSERT`, `UPDATE`, `DELETE` | `TRUNCATE`, `DROP` | Idempotency token storage and janitor cleanup. |
| `flyway_schema_history` | `SELECT` | `INSERT`, `UPDATE`, `DELETE`, `DROP` | **Migration Metadata Protection:** Prevents application runtime from tampering with applied migration hashes. |

---

### 3.3 Application Configuration Decoupling
In `src/main/resources/application.yml`, the Flyway datasource user was decoupled from the runtime datasource user:
```yaml
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    clean-disabled: true
    user: ${DB_MIGRATION_USER:${FLYWAY_USER:${spring.datasource.username}}}
    password: ${DB_MIGRATION_PASSWORD:${FLYWAY_PASSWORD:${spring.datasource.password}}}
```
- In local development, `spring.datasource.username` defaults to `postgres` (or developer superuser), allowing Flyway to initialize schemas seamlessly.
- In staging and production environments, the system administrator configures `DB_MIGRATION_USER=vyom_flyway` and `DB_USER=vyom_app`, guaranteeing strict role segregation between deployment and runtime.

---

### 3.4 Automated Least-Privilege Verification (`DatabaseLeastPrivilegeTest.java`)

All 5 security scenarios were executed directly against PostgreSQL 18.4:
```text
[INFO] Running com.vyom.integration.DatabaseLeastPrivilegeTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.140 s -- in com.vyom.integration.DatabaseLeastPrivilegeTest
```

1. **`testAppUserCanReadLedgerEntries`:** Confirmed `vyom_app` can execute `SELECT count(*) FROM ledger_entries`. (**PASS**)
2. **`testAppUserCannotUpdateLedgerEntries`:** Attempted `UPDATE ledger_entries SET balance_after = 0`. Threw `PSQLException: permission denied for table ledger_entries`. (**PASS**)
3. **`testAppUserCannotDeleteLedgerEntries`:** Attempted `DELETE FROM ledger_entries`. Threw `PSQLException: permission denied for table ledger_entries`. (**PASS**)
4. **`testAppUserCannotModifyUserSecurityColumns`:** Attempted `UPDATE users SET role = 'ADMIN'`. Threw `PSQLException: permission denied for column role of table users`. Attempted `UPDATE users SET password_hash = 'malicious'`. Threw `PSQLException: permission denied for column password_hash of table users`. (**PASS**)
5. **`testAppUserCannotDropTable`:** Attempted `DROP TABLE ledger_entries`. Threw `PSQLException: must be owner of table ledger_entries`. (**PASS**)

---

## 4. Conclusion

The port resolution and least-privilege configurations have been proven secure and functional through reproducible automated tests. Process isolation between the gateway and backend is guaranteed, and database security controls strictly prevent privilege escalation and unauthorized ledger modifications.
