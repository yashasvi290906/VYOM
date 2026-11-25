# VYOM — Phase 0: Build Infrastructure & Environment Configuration Report

## Executive Summary

This report documents the verification, remediation, and stabilization of VYOM's build infrastructure and environment configuration across the Java/Spring Boot backend, Node.js/Express API Gateway, and PostgreSQL 18 database tier. 

Key achievements include restoring a fully pinned and reproducible Apache Maven Wrapper (`3.9.9`), eliminating conflicting network port conventions between backend and gateway, formalizing least-privilege database role configuration, and creating an unambiguous environment configuration contract.

---

## 1. Java Build Infrastructure & Reproducibility

### 1.1 Maven Wrapper Verification
The Maven Wrapper configuration in `.mvn/wrapper/maven-wrapper.properties` was verified and pinned to official Apache Maven binaries:

```properties
wrapperVersion=3.3.4
distributionType=bin
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip
wrapperUrl=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.4/maven-wrapper-3.3.4.jar
```

Both platform execution scripts are present and functional:
- **Windows:** `.\mvnw.cmd`
- **Unix / Linux / macOS:** `./mvnw` (with executable permissions)

### 1.2 Verified Build Environment
Execution of `.\mvnw.cmd -version` verified the pinned runtime:
```
Apache Maven 3.9.9 (8e8579a9e76f7d015ee5ec7bfcdc97d260186937)
Maven home: C:\Users\yashasvi\.m2\wrapper\dists\apache-maven-3.9.9-bin\33b4b2b4\apache-maven-3.9.9
Java version: 22.0.2, vendor: Oracle Corporation, runtime: C:\Program Files\Java\jdk-22
Default locale: en_IN, platform encoding: UTF-8
OS name: "windows 11", version: "10.0", arch: "amd64", family: "windows"
```

- **Target Compiler Compliance:** Java 21 (`<java.version>21</java.version>` in `pom.xml`, javac target release 21).
- **Zero External Dependencies Required:** Builds execute out-of-the-box using the self-bootstrapping wrapper without requiring a pre-installed system Maven.

---

## 2. Environment Configuration Contract

To eliminate prior ambiguities where `PORT` could unintentionally cause port collision between the backend and gateway, strict precedence hierarchies were implemented.

### 2.1 Backend Environment Configuration (`application.yml`)
The backend reads configuration directly from environment variables with safe defaults:

| Variable | Description | Default Value | Process / Purpose |
|---|---|---|---|
| `BACKEND_PORT` | HTTP port for Spring Boot backend | `8080` | Spring Boot (takes priority over `PORT`) |
| `SERVER_ADDRESS` | Network interface binding | `127.0.0.1` | Spring Boot (localhost loopback defense) |
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `prod` (production default) | Spring Boot profile loader |
| `DB_HOST` | PostgreSQL host | `127.0.0.1` | HikariCP & Flyway |
| `DB_PORT` | PostgreSQL port | `5432` | HikariCP & Flyway |
| `DB_NAME` | PostgreSQL database name | `payledger` | HikariCP & Flyway |
| `DB_USER` | Runtime DB user (`vyom_app`) | `postgres` (fallback) | HikariCP connection pool (DML only) |
| `DB_PASSWORD` | Runtime DB password | `""` (empty) | HikariCP connection pool |
| `DB_MIGRATION_USER` | Migration DB user (`vyom_flyway`)| `postgres` (fallback) | Flyway migration runner (DDL privileges) |
| `DB_MIGRATION_PASSWORD`| Migration DB password | `""` (empty) | Flyway migration runner |
| `JWT_SECRET` | HMAC-SHA256 signing secret | *None (Mandatory in prod)* | `JwtTokenService` ($\ge 256$ bits) |
| `JWT_EXPIRATION_MINUTES`| Token expiration window | `60` | `JwtTokenService` |
| `VYOM_ADMIN_BOOTSTRAP_ENABLED`| Flag to seed initial admin | `false` | `AdminBootstrapService` |
| `IDEMPOTENCY_TIMEOUT_SECONDS` | In-flight request lock lease | `120` | `IdempotencyService` |
| `IDEMPOTENCY_JANITOR_ENABLED` | Scheduled cleanup daemon flag | `true` | `IdempotencyJanitorService` |

### 2.2 Gateway Environment Configuration (`gateway/src/config/index.js`)

| Variable | Description | Default Value | Process / Purpose |
|---|---|---|---|
| `NODE_ENV` | Gateway runtime mode | `production` | Express error handling and caching |
| `GATEWAY_PORT` | HTTP port for Express gateway | `3000` | Express listener (takes priority over `PORT`) |
| `BACKEND_BASE_URL` | Base URL for Spring Boot backend | `http://127.0.0.1:8080` | HTTP proxy forwarder |
| `BACKEND_TIMEOUT_MS` | Timeout for backend API requests | `5000` | Gateway upstream timeout handler |
| `JWT_SECRET` | Secret to decode client tokens | *Mandatory in prod* | Rate-limiter user identity extraction |
| `TRUST_PROXY` | Reverse proxy trust configuration | `false` / `loopback` | `parseTrustProxy` (prevents spoofing) |
| `RATE_LIMIT_WINDOW_MS`| Sliding rate limit window | `60000` (1 min) | Sliding-window token limiter |
| `RATE_LIMIT_MAX` | Max unauthenticated requests/min | `100` | Gateway rate limiter |
| `RATE_LIMIT_USER_MAX` | Max authenticated requests/min | `300` | Gateway rate limiter |
| `RATE_LIMIT_MAX_BUCKETS`| Memory bounding for limiter | `10000` | Bounded cache eviction guard |

### 2.3 Collision Prevention Guarantees
- If both services are started in an environment with generic `PORT=3000`, setting `BACKEND_PORT=8080` guarantees Spring Boot binds to 8080, while Gateway binds to `GATEWAY_PORT=3000`.
- Boolean values (`TRUST_PROXY`, `VYOM_ADMIN_BOOTSTRAP_ENABLED`) are explicitly parsed (`"true"`, `"1"`, `true` vs `"false"`, `"0"`, `false`), preventing JavaScript truthy-string evaluation bugs.

---

## 3. Reproducible Local Startup Guide

Follow this guide to run the complete VYOM platform locally.

### Step 1: Start PostgreSQL
Ensure PostgreSQL is active on port 5432:
```powershell
# If running as a standalone service/daemon:
& "D:\Postgres\bin\postgres.exe" -D "D:\Postgres inst"
```

### Step 2: Provision Database Roles & Least-Privilege Permissions
Execute the provisioning script against your target database (e.g. `payledger`):
```powershell
& "D:\Postgres\bin\psql.exe" -U postgres -d payledger `
  -v APP_PASSWORD="secure_app_password" `
  -v FLYWAY_PASSWORD="secure_flyway_password" `
  -f scripts/setup_least_privilege_user.sql
```

### Step 3: Configure Environment Variables
Copy `.env.example` to `.env` or set environment variables in your terminal:
```powershell
$env:SPRING_PROFILES_ACTIVE="prod"
$env:BACKEND_PORT="8080"
$env:DB_HOST="127.0.0.1"
$env:DB_PORT="5432"
$env:DB_NAME="payledger"
$env:DB_USER="vyom_app"
$env:DB_PASSWORD="secure_app_password"
$env:DB_MIGRATION_USER="vyom_flyway"
$env:DB_MIGRATION_PASSWORD="secure_flyway_password"
$env:JWT_SECRET="dGhpc19pc19hX3Zlcnlfc2VjdXJlX2tleV9mb3JfdnlvbV9kZXYxMjM0NTY3OA=="
```

### Step 4: Build and Start Backend
```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-22"
.\mvnw.cmd spring-boot:run
```
*Health verification:* `http://127.0.0.1:8080/actuator/health`

### Step 5: Start Express API Gateway
In a separate terminal:
```powershell
$env:GATEWAY_PORT="3000"
$env:BACKEND_BASE_URL="http://127.0.0.1:8080"
$env:JWT_SECRET="dGhpc19pc19hX3Zlcnlfc2VjdXJlX2tleV9mb3JfdnlvbV9kZXYxMjM0NTY3OA=="
npm start --prefix gateway
```
*Web Application URL:* `http://localhost:3000/`

---

## 4. Continuous Integration Path & Automation

The repository contains a complete CI workflow configuration in `.github/workflows/ci.yml` providing automated verification:
1. **Java Build & Test:** Compiles with JDK 21 and runs the full Maven test suite.
2. **Gateway Test Suite:** Runs all 34 Node.js integration tests via `npm test`.
3. **Syntax Validation:** Executes syntax checks across all 44 JavaScript repository files.
4. **PostgreSQL Migration Test:** Validates migrations and least-privilege database roles against PostgreSQL.
