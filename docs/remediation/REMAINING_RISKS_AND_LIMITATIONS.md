# VYOM — Remaining Risks, Technical Limitations & Operational Runbooks

**Document Identifier:** `VYOM-REM-RISKS-AND-LIMITATIONS-FINAL`  
**Execution Date:** 2026-11-19  
**Repository:** `D:\VYOM`  
**Git HEAD:** `176994b Initial Foundation and Transaction workign`  
**Branch:** `main`  
**Author:** Senior Financial Systems, Concurrency & Security Specialist  

---

## 1. Executive Summary

The **VYOM Comprehensive Remediation Phase** has resolved all 24 confirmed audit defects (**F01 through F24**), establishing strict double-entry ledger invariants, fail-closed authentication, least-privilege database permissions, user-scoped idempotency, and exact decimal arithmetic.

However, in accordance with the project's strict non-negotiable architectural boundaries:
- **Architecture Preserved:** Browser $\rightarrow$ Express Gateway (`:3000`) $\rightarrow$ Spring Boot (`:8080`) $\rightarrow$ PostgreSQL 18 (`:5432`).
- **Zero Foreign Infrastructure:** Zero external message queues (Kafka, RabbitMQ), zero distributed caches (Redis), zero container orchestration (Docker, K8s).

Certain physical, concurrency, and operational boundaries remain inherent to single-node relational transaction processing. This document delivers a transparent, technically rigorous assessment of these **inherent architectural limitations**, details the **residual operational risks**, and provides comprehensive **production operational runbooks** for enterprise deployment and governance.

---

## 2. Inherent Architectural Limitations

### 2.1 Hot-Spot Contention on Shared System Settlement Wallets
- **Description:** Under the canonical double-entry ledger architecture, every customer funding transaction credits the customer's wallet and debits the designated system settlement wallet for that currency (`SYSTEM_SETTLEMENT`).
- **Mechanism:** To guarantee ledger solvency and balance integrity, the system acquires a pessimistic row write lock (`SELECT ... FOR UPDATE`) on the system settlement wallet row within the transaction boundary.
- **Limitation:** Because all funding operations for a given currency serialize on that single row, transaction throughput for funding operations is physically capped by the duration of the row lock hold window. At 15–20+ concurrent virtual users executing funding operations simultaneously, thread queueing on PostgreSQL row locks increases transaction latency (p95/p99 spikes).
- **Why It Remains Unchanged in Single-Node Scope:** Architectural mitigations such as *settlement wallet sharding* (allocating multiple settlement sub-wallets per currency) or *asynchronous delayed settlement balancing* were explicitly excluded to preserve canonical double-entry simplicity and prevent complex distributed reconciliation jobs.

### 2.2 Single-Node Gateway In-Memory Rate Limiting
- **Description:** The Express API Gateway employs an in-memory sliding-window rate limiter (`gateway/src/middleware/rateLimiter.js`).
- **Limitation:**
  1. Rate limit state is stored entirely in Node.js process heap memory. If the gateway is scaled horizontally across multiple processes or instances, rate limit buckets are not shared across nodes, allowing clients to make $N \times \text{limit}$ requests across $N$ instances.
  2. A process restart flushes all active rate limit buckets, temporarily resetting burst protection.
- **Why It Remains Unchanged in Single-Node Scope:** Distributed caching stores (such as Redis or Memcached) are barred by architecture boundaries. Within single-node constraints, the implemented memory-bounded LRU bucket eviction and idle cleanup provide optimal DoS protection.

### 2.3 Single-Node PostgreSQL Failover and High Availability (HA)
- **Description:** The application connects directly to a single PostgreSQL 18 instance via HikariCP.
- **Limitation:**
  1. There is no automated active-passive failover (e.g. Patroni, PgBouncer pool, or AWS RDS Multi-AZ). If the PostgreSQL daemon or host machine experiences a kernel panic, hardware fault, or disk failure, the transaction engine becomes unavailable (Recovery Time Objective $> 0$).
  2. Point-in-time recovery depends entirely on WAL archiving and filesystem snapshots.
- **Production Recommendation:** Production deployment requires managed database clustering or streaming replication with an external proxy.

### 2.4 Synchronous Transaction Lifecycle vs. Asynchronous Messaging
- **Description:** Transfer and funding workflows are executed synchronously from the REST controller through Spring services into the database engine within a single thread.
- **Limitation:** In the absence of an event broker (e.g. Kafka or RabbitMQ) and transactional outbox table:
  1. Third-party notification hooks, audit streaming, or external webhooks cannot be dispatched asynchronously.
  2. Any future integration with real external payment gateways (e.g. UPI, Stripe, Razorpay) would introduce external network latency directly into the critical lock-holding path unless converted to an asynchronous worker pattern.

### 2.5 Scheduled Idempotency Janitor Polling
- **Description:** Stale or abandoned idempotency records (`status = PROCESSING`) are swept periodically by `IdempotencyJanitorService` running on a Spring `@Scheduled` timer.
- **Limitation:** The sweep interval introduces a time window (default 15 minutes lease duration) during which an abandoned request key cannot be re-reserved by the user. If the background janitor thread encounters an unhandled exception or Spring scheduler starvation, stale reservations remain blocked until the next cycle.

---

## 3. Residual Risk Assessment Matrix

| Risk ID | Risk Description | Severity | Likelihood | Impact | Current Defense / Mitigation |
| :---: | :--- | :---: | :---: | :---: | :--- |
| **R-01** | Funding lock contention at concurrency $> 30$ VUs | Medium | High | Degraded TPS, higher p99 latency | Short lock-hold window (scalar pre-check, deferred commit trigger). |
| **R-02** | Multi-instance gateway deployment bypasses rate limits | Low | Low (Single node) | Individual node burst exceeded | Enforce IP rate limiting at edge reverse proxy (Nginx / Cloudflare). |
| **R-03** | PostgreSQL host failure causes downtime | High | Low | Service outage until restart | PostgreSQL WAL archiving, volume snapshots, service monitors. |
| **R-04** | Client connection pool starvation under extreme read loads | Medium | Medium | HTTP 500 / ConnectionTimeout | Read-only transactions separated; Hikari connection timeout set to 5000ms. |
| **R-05** | Stale idempotency lease blocks retry during network split | Low | Medium | Client receives 409 Conflict | 15-minute lease expiration; Janitor bounded sweep with dynamic recovery. |
| **R-06** | Browser refresh during transfer without re-query | Low | Low | User re-enters transfer form | Handled by `sessionStorage` in-flight key recovery (`GET /api/v1/idempotency/status/{key}`). |

---

## 4. Production Operational Runbooks

### Runbook 1: Production JWT Secret Rotation

In financial platforms, signing secrets must be rotated periodically or immediately upon suspected compromise. Because the VYOM backend enforces fail-closed validation, rotation must follow a structured procedure.

#### Phase 1: Key Generation
1. Generate a high-entropy 256-bit cryptographically secure key:
   ```bash
   openssl rand -base64 48
   ```
2. Store the new key securely in your secret manager (e.g. AWS Secrets Manager, HashiCorp Vault, or encrypted environment configuration).

#### Phase 2: Staged Deployment (Zero-Downtime Migration)
*Note: Under single-key symmetric HMAC-SHA256, changing `JWT_SECRET` immediately invalidates all active sessions. For non-disruptive rotation, follow this protocol:*
1. **Maintenance Window Notification:** Schedule a short operational maintenance window during low-traffic periods.
2. **Update API Gateway First:**
   - Update `JWT_SECRET` in Gateway `.env` or systemd environment.
   - Restart the Express Gateway service:
     ```bash
     systemctl restart vyom-gateway
     ```
3. **Update Spring Boot Backend:**
   - Update `JWT_SECRET` in Spring Boot environment (`JWT_SECRET` environment variable).
   - Restart Spring Boot application:
     ```bash
     systemctl restart vyom-backend
     ```
4. **Post-Rotation Verification:**
   - Verify health endpoints:
     ```bash
     curl -i http://localhost:8080/actuator/health
     curl -i http://localhost:3000/api/v1/health
     ```

---

### Runbook 2: Database Migration Governance & Emergency Rollback

#### Flyway Governance Rules
1. **Immutability of Applied Migrations:** Migrations `V1` through `V8` are immutable once applied to any environment. Never modify existing migration SQL files.
2. **Forward-Fix Only Policy:** If a schema defect is detected in production, author a new sequential migration (`V9__description.sql`).
3. **Dual Trigger Protection:** Migration `V8` established deferred constraint triggers on both `transactions` and `ledger_entries`. Any future table modifications must preserve these trigger associations:
   ```sql
   CONSTRAINT trg_defer_verify_transaction_ledger
     AFTER INSERT OR UPDATE ON ledger_entries
     DEFERRABLE INITIALLY DEFERRED
     FOR EACH ROW EXECUTE FUNCTION fn_verify_transaction_ledger_invariants();
   ```

#### Emergency Rollback Procedure
If a deployment fails during migration application:
1. Stop the Spring Boot application immediately to prevent partial transaction processing:
   ```bash
   systemctl stop vyom-backend
   ```
2. Inspect `flyway_schema_history` using the administrative role `vyom_flyway`:
   ```sql
   SELECT installed_rank, version, description, type, script, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
   ```
3. If a migration failed (`success = false`), resolve the underlying schema error, repair the history table (`flyway.repair()` or `DELETE FROM flyway_schema_history WHERE success = false;`), and re-run.

---

### Runbook 3: Least-Privilege Database Role Maintenance

VYOM enforces strict segregation between runtime application access (`vyom_app`) and migration access (`vyom_flyway`).

#### Periodic Password Rotation for `vyom_app`:
1. Generate new strong password:
   ```bash
   openssl rand -base64 32
   ```
2. Connect to PostgreSQL as superuser `postgres`:
   ```sql
   ALTER ROLE vyom_app WITH PASSWORD '<NEW_STRONG_PASSWORD>';
   ```
3. Update `DB_PASSWORD` in the Spring Boot environment and restart the backend.
4. Verify table update restrictions:
   ```sql
   -- Connect as vyom_app
   -- This must SUCCEED:
   UPDATE users SET full_name = 'Test Name' WHERE email = 'test@vyom.internal';
   -- This must FAIL with 42501 (permission denied):
   UPDATE users SET role = 'ADMIN' WHERE email = 'test@vyom.internal';
   ```

---

### Runbook 4: Disaster Recovery & WAL Point-in-Time Recovery

#### Backup Strategy
1. **Continuous WAL Archiving:** Enable PostgreSQL WAL archiving to redundant durable storage (e.g. AWS S3 or dedicated storage volume):
   ```ini
   wal_level = replica
   archive_mode = on
   archive_command = 'test ! -f /mnt/wal_archive/%f && cp %p /mnt/wal_archive/%f'
   ```
2. **Daily Base Backups:** Execute daily physical base backups using `pg_basebackup`:
   ```bash
   pg_basebackup -h 127.0.0.1 -U postgres -D /mnt/backups/base_$(date +%Y%m%d) -Ft -z -P
   ```

#### Point-in-Time Recovery (PITR) Execution
If database corruption or accidental administrative deletion occurs:
1. Stop PostgreSQL service.
2. Restore the latest clean physical base backup to the data directory.
3. Configure `recovery.signal` and specify recovery target time:
   ```ini
   restore_command = 'cp /mnt/wal_archive/%f %p'
   recovery_target_time = '2026-11-19 14:00:00 EST'
   recovery_target_action = 'promote'
   ```
4. Start PostgreSQL and verify database integrity by running:
   ```bash
   node benchmark/reconcile.js
   ```

---

### Runbook 5: Future Horizontal Scaling Roadmap

When transitioning from the single-node architecture to a horizontally scaled distributed system, the following roadmap should be followed:

1. **Centralized Rate Limiting (Redis):** Replace the in-memory Map in `rateLimiter.js` with Redis-backed sliding-window rate limiting (`ioredis` with Lua scripts) to share client quotas across all Gateway instances.
2. **Settlement Wallet Sharding:** To eliminate hot-spot row contention on `SYSTEM_SETTLEMENT`, shard settlement accounts into $N$ partitioned wallets (e.g. `SYSTEM_SETTLEMENT_INR_0` through `SYSTEM_SETTLEMENT_INR_15`), selecting a shard deterministically or via round-robin during funding.
3. **Transactional Outbox Pattern:** Implement an `outbox_events` table within PostgreSQL. Write domain events (e.g. `TransferCompletedEvent`, `FundingCompletedEvent`) in the same database transaction as the financial ledger, and stream events via Debezium CDC to Kafka for asynchronous notification dispatch.
4. **PostgreSQL High Availability:** Deploy Patroni with etcd for automated PostgreSQL failover and PgBouncer for client connection pooling.
