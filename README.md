# VYOM (व्योम) — Digital Payments and Transaction Processing Platform

**Virtual Yatra of Money**  
*Every transaction. One trusted space.*

VYOM is an ACID-compliant simulated digital payments ledger, wallet engine, and API gateway built with **Java 21**, **Spring Boot 3.4.3**, **PostgreSQL 18**, **Flyway**, **Node.js/Express**, and a native **HTML5/CSS3/JavaScript frontend**.

The platform demonstrates production-oriented financial engineering concepts, including **concurrency safety**, **persistent idempotency with ownership fencing**, **deadlock prevention**, **double-entry bookkeeping**, and **database-enforced append-only ledger protection**.

> [!NOTE]
> **Simulation Notice:** VYOM is a simulated payment-processing platform designed to demonstrate financial system architecture, transaction integrity, and high-concurrency processing. It does not connect to real UPI switches, banking networks, external payment gateways, or real money.

---

## 1. System Architecture

```text
┌─────────────────────────────────────────────────────────────┐
│                    Browser Client                           │
│         HTML5 / Responsive CSS3 / JavaScript                 │
└──────────────────────────────┬──────────────────────────────┘
                               │ HTTP / JSON :3000
┌──────────────────────────────▼──────────────────────────────┐
│                 Node.js / Express Gateway                   │
│                                                             │
│  • Request and correlation ID generation                    │
│  • Reverse proxying and header pass-through                 │
│  • User-aware sliding-window rate limiting                  │
│  • Controlled upstream error translation (503 / 504)       │
│  • Static frontend hosting                                  │
└──────────────────────────────┬──────────────────────────────┘
                               │ HTTP / JSON :8080
┌──────────────────────────────▼──────────────────────────────┐
│             Java 21 / Spring Boot 3.4.3 Backend             │
│                                                             │
│  • Spring Security and stateless JWT authentication         │
│  • REST APIs and Bean Validation                            │
│  • Wallet ownership verification and IDOR protection       │
│  • Transaction management (@Transactional)                  │
│  • Pessimistic row locking (SELECT ... FOR UPDATE)          │
│  • User-scoped idempotency with SHA-256 and fencing         │
│  • Double-entry ledger processing                           │
│  • System settlement liquidity management                   │
└──────────────────────────────┬──────────────────────────────┘
                               │ JDBC :5432
┌──────────────────────────────▼──────────────────────────────┐
│                    PostgreSQL Database                      │
│                                                             │
│  • Database role separation (vyom_flyway / vyom_app)        │
│  • Flyway migrations (V1 through V11)                       │
│  • System settlement wallets                                │
│  • Deferred transaction balance invariant triggers         │
│  • Append-only ledger protection                            │
│  • Database constraints and unique indexes                  │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Key Architectural Invariants

1. **Non-Negative Balances:** Service-level validation and database constraints prevent customer wallets from having negative balances. System settlement accounts are handled separately.

2. **Double-Entry Conservation:** Finalized transactions maintain balanced debit and credit ledger entries. Deferred database triggers validate transaction balance invariants at commit.

3. **Atomic Financial Operations:** Wallet balance updates, transaction records, and corresponding ledger entries participate in atomic database transactions. Failed operations roll back rather than leaving partial financial state.

4. **Authentication and IDOR Protection:** Customer passwords are hashed using BCrypt. Stateless JWT bearer tokens use HS256 authentication. Ownership checks protect wallet, transaction, dispute, refund, and idempotency-related operations.

5. **Database-Enforced Append-Only Ledger:** PostgreSQL trigger `trg_protect_ledger_entries` prevents unauthorized updates and deletions of existing ledger entries.

6. **User-Scoped Persistent Idempotency:** Mutation requests use customer-scoped `(user_id, idempotency_key)` records. Identical retries can return cached responses without duplicate execution, while altered payloads using an existing key are rejected.

7. **Crash-Resilient Idempotency Recovery:** Abandoned `PROCESSING` records can be reclaimed through atomic ownership-token and lease-version updates. Stale workers are prevented from committing after losing ownership.

8. **Deterministic Wallet Lock Ordering:** Dual-wallet operations acquire locks in a consistent UUID order, reducing circular-wait conditions and preventing bidirectional wallet-lock deadlocks.

9. **Database Privilege Separation:** DDL operations are assigned to the `vyom_flyway` migration role, while application runtime operations use the restricted `vyom_app` role.

10. **Monetary Precision:** Java monetary calculations use `BigDecimal`, JavaScript uses integer-based `BigInt` arithmetic, and PostgreSQL stores monetary values using fixed-precision numeric types.

---

## 3. Technology Stack and Prerequisites

| Component | Technology |
|---|---|
| Backend Language | Java 21 |
| Backend Framework | Spring Boot 3.4.3 |
| Security | Spring Security 6.4.3 |
| Database | PostgreSQL 15+ |
| Tested Database Version | PostgreSQL 18.4 |
| Database Migrations | Flyway 10+ |
| Frontend Gateway | Node.js 22+ |
| Gateway Framework | Express.js |
| Frontend | HTML5, CSS3, JavaScript |
| Build Tool | Apache Maven 3.9.9 |
| Backend Testing | JUnit 5, Mockito, Spring Boot Test |
| Gateway Testing | Native Node.js `node:test` |

**Prerequisites**

- JDK 21 or later
- Node.js 22 or later
- npm
- PostgreSQL
- Git
- OpenSSL for generating secure JWT signing secrets

The repository includes a Maven Wrapper, so a separate Maven installation is not required.

---

## 4. Setup and Running Instructions

### Step 1: Clone the Repository

```bash
git clone https://github.com/yashasvi290906/VYOM.git
cd VYOM
```

### Step 2: Create PostgreSQL Databases

Ensure PostgreSQL is running on port `5432`.

Create the application and test databases:

```sql
CREATE DATABASE payledger;
CREATE DATABASE payledger_test;
```

### Step 3: Provision Least-Privilege Database Roles

Execute the database provisioning script to configure:

- `vyom_flyway` — database migration role with required DDL privileges.
- `vyom_app` — restricted application runtime role.

Example using PowerShell:

```powershell
psql -U postgres -d payledger `
  -v APP_PASSWORD="your_secure_runtime_password" `
  -v FLYWAY_PASSWORD="your_secure_migration_password" `
  -f scripts/setup_least_privilege_user.sql
```

Replace the example passwords with securely generated values.

### Step 4: Configure Environment Variables

Copy `.env.example` to `.env` and replace all placeholder values.

Alternatively, configure the variables directly in your terminal.

Example:

```powershell
$env:SPRING_PROFILES_ACTIVE="prod"
$env:BACKEND_PORT="8080"

$env:DB_HOST="127.0.0.1"
$env:DB_PORT="5432"
$env:DB_NAME="payledger"

$env:DB_USER="vyom_app"
$env:DB_PASSWORD="your_secure_runtime_password"

$env:DB_MIGRATION_USER="vyom_flyway"
$env:DB_MIGRATION_PASSWORD="your_secure_migration_password"
```

**Generate a secure JWT signing secret:**

```powershell
openssl rand -base64 48
```

Configure the generated value as `JWT_SECRET` in both the backend and gateway environments.

```powershell
$env:JWT_SECRET="your_generated_secret"
```

> [!IMPORTANT]
> Both services must use the same JWT signing secret. Never commit generated secrets, database passwords, or production credentials to Git.

### Step 5: Build and Start the Spring Boot Backend

Ensure JDK 21 or later is available through `JAVA_HOME` or your system `PATH`.

From the repository root:

```powershell
.\mvnw.cmd spring-boot:run
```

On Linux/macOS:

```bash
./mvnw spring-boot:run
```

Backend health endpoint:

```text
http://127.0.0.1:8080/actuator/health
```

### Step 6: Start the Node.js API Gateway

Open a separate terminal and configure the gateway:

```powershell
$env:GATEWAY_PORT="3000"
$env:BACKEND_BASE_URL="http://127.0.0.1:8080"
$env:JWT_SECRET="your_generated_secret"
```

Install dependencies and start the gateway:

```powershell
npm ci --prefix gateway
npm start --prefix gateway
```

### Step 7: Access the Web Application

Open:

```text
http://localhost:3000/
```

The frontend communicates with the backend through the Express gateway.

---

## 5. Administrative Bootstrap Procedure

Administrative account bootstrapping is disabled by default:

```text
VYOM_ADMIN_BOOTSTRAP_ENABLED=false
```

To provision an initial administrator account:

1. Configure the bootstrap environment variables using secure credentials.

```powershell
$env:VYOM_ADMIN_BOOTSTRAP_ENABLED="true"
$env:VYOM_ADMIN_BOOTSTRAP_EMAIL="admin@vyom.internal"
$env:VYOM_ADMIN_BOOTSTRAP_PASSWORD="your_secure_admin_password"
$env:VYOM_ADMIN_BOOTSTRAP_FULL_NAME="System Administrator"
```

2. Start the backend. During initial startup, `AdminBootstrapService` provisions the administrator account with the `ADMIN` role.

3. Stop the backend after successful provisioning.

4. Remove the bootstrap credentials from the environment and restore:

```powershell
$env:VYOM_ADMIN_BOOTSTRAP_ENABLED="false"
```

> [!WARNING]
> Do not leave administrative bootstrap enabled during normal operation. Use strong credentials and protect all bootstrap environment variables.

---

## 6. Testing and Verification

### Backend Test Suite

Run the complete Maven test suite:

```powershell
.\mvnw.cmd test
```

The suite includes unit, integration, security, database, financial integrity, concurrency, and idempotency tests.

### API Gateway Test Suite

Install dependencies:

```powershell
npm ci --prefix gateway
```

Run the gateway tests:

```powershell
npm test --prefix gateway
```

Coverage includes:

- Reverse proxying and upstream error handling
- Request and correlation ID generation
- Security headers
- Sliding-window rate limiting
- Rate-limit memory bounding and cache churn resilience
- JWT algorithm and expiration validation
- Proxy trust policy

### Financial Reconciliation

Run the reconciliation utility:

```powershell
node benchmark/reconcile.js
```

This utility performs financial consistency checks across supported ledger, wallet, settlement, and idempotency invariants.

---

## 7. Security Limitations and Operational Boundaries

1. **Simulated Financial Platform:** VYOM is an engineering simulation. It does not process real money or integrate with actual banking infrastructure.

2. **JWT Security:** Use cryptographically strong signing secrets, keep them outside source control, and rotate them according to operational security requirements.

3. **Database Privileges:** Use separate migration and runtime database credentials. Do not run the application using a PostgreSQL superuser account.

4. **Reverse Proxy Trust:** Configure `TRUST_PROXY` according to the actual deployment topology. Trust only known proxy addresses or networks to prevent client IP spoofing.

5. **Administrative Bootstrap:** Keep bootstrap disabled after initial administrator provisioning.

6. **Production Deployment:** Use TLS, protected environment variables or a secrets manager, restricted database networking, and appropriate operational monitoring.

---

## 8. Repository

**GitHub:** https://github.com/yashasvi290906/VYOM

VYOM is an evolving engineering project focused on transaction correctness, financial data integrity, application security, and reliable backend architecture.

**Every transaction. One trusted space.**
