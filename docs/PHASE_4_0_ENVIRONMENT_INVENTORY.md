# VYOM — Phase 4.0: Environment Inventory & Pre-Benchmark Audit Report

**Date:** 2026-11-18  
**Scope:** Forensic system inspection, runtime inventory, and benchmarking prerequisites for VYOM  
**Project Root:** `D:\VYOM`  
**Target Environment:** Windows 11 (AMD64), PostgreSQL 18.4, Java 22 / Spring Boot 3.3.5, Node.js v24.11.0 Gateway  

---

## 1. Executive Summary

In accordance with Phase 4.0 requirements, a comprehensive, live-system environment audit was performed to resolve actual runtime versions, configuration parameters, hardware capacities, and benchmarking tool availability. Previous audit reports noted slight discrepancies in reported versions; this inventory documents the authoritative, empirically confirmed specifications of the actual host machine and software stack.

---

## 2. Hardware & Operating System Specifications

| Attribute | Resolved Environment Value | Source / Verification Command |
|---|---|---|
| **Operating System** | Microsoft Windows 11 Home Single Language (Build 10.0.22631) | `Get-CimInstance Win32_OperatingSystem` |
| **Architecture** | 64-bit OS (AMD64 / x86_64) | `[System.Environment]::Is64BitOperatingSystem` |
| **CPU Model** | 11th Gen Intel(R) Core(TM) i5-1155G7 @ 2.50GHz | `Get-CimInstance Win32_Processor` |
| **Physical Cores** | 4 Physical Cores | `Win32_Processor.NumberOfCores` |
| **Logical Processors** | 8 Threads / Virtual Cores | `Win32_Processor.NumberOfLogicalProcessors` |
| **Base Clock Speed** | 2.50 GHz (Max turbo ~4.50 GHz) | `Win32_Processor.MaxClockSpeed` |
| **Total Physical RAM** | 8,022,520 KB (~7.65 GB RAM) | `Win32_OperatingSystem.TotalVisibleMemorySize` |
| **Free Physical RAM** | ~650 MB – 1,100 MB available (High memory pressure) | `Win32_OperatingSystem.FreePhysicalMemory` |
| **Storage Subsystem** | Local NVMe SSD | Host storage device |

> [!WARNING]
> Available physical memory is constrained (~700 MB free under idle operating state). Workload concurrency must be ramped conservatively to prevent OS paging or memory exhaustion.

---

## 3. Runtime & Tooling Versions

| Subsystem | Resolved Version | Verification Command | Notes |
|---|---|---|---|
| **Java Runtime** | Java 22.0.2 (build 22.0.2+9-70) | `java -version` | Oracle JDK 22.0.2 64-Bit Server VM |
| **Compilation Target** | Java 21 (release target 21) | `pom.xml` (`<java.version>21</java.version>`) | Bytecode compiled with release 21 flags |
| **Maven Build Tool** | Apache Maven 3.9.9 | `.\mvnw.cmd -version` | Wrapped local Maven 3.9.9 distribution |
| **Spring Boot Framework** | 3.3.5 (Spring Framework 6.1.14) | `pom.xml` parent artifact | Embedded Tomcat 10.1.31 web container |
| **Node.js Gateway Runtime** | v24.11.0 | `node -v` | V8 engine 13.x with native `fetch` support |
| **NPM Package Manager** | 11.6.1 | `npm -v` | Installed with Node.js v24 |
| **PostgreSQL Database** | PostgreSQL 18.4 (x86_64-windows) | `psql -c "SELECT version();"` | Compiled with MSVC 19.44.35227 |
| **Flyway Schema Migration** | 10.10.0 (embedded in Spring Boot) | `pom.xml` / `flyway_schema_history` | Migrations `V1` through `V6` active |

---

## 4. PostgreSQL Configuration & Connection Topology

Database configuration parameters queried directly from the running PostgreSQL 18.4 instance:

| PostgreSQL Parameter | Value | Impact on Benchmarking |
|---|---|---|
| `port` | `5432` | Standard localhost port |
| `max_connections` | `100` | Hard cap on total database connections |
| `shared_buffers` | `128 MB` | Memory allocated to shared PostgreSQL buffers |
| `work_mem` | `4 MB` | Sort / hash memory per query operation |
| `effective_cache_size` | `4 GB` | Query planner memory assumption |
| `maintenance_work_mem` | `64 MB` | Vacuum and index maintenance memory |
| `synchronous_commit` | `on` | Strict durability: commits write WAL to disk synchronously |
| `wal_level` | `replica` | Standard WAL logging level |

### Database Isolation Strategy
- **Production/Dev Database:** `payledger`
- **Unit/Integration Test Database:** `payledger_test`
- **Dedicated Benchmark Database:** `payledger_benchmark`
  - Created isolated database on port 5432 to eliminate interference with integration test state and development records.
  - Schema initialized via automated Flyway migrations (`V1` to `V6`).
  - System settlement accounts seeded per migration `V4`.

---

## 5. Application Connection Pool & Threading Architecture

### Spring Boot HikariCP Configuration
Queried from `src/main/resources/application.yml` and `application-test.yml`:

| Configuration Key | Benchmark Profile Value | Default Value | Notes |
|---|---|---|---|
| `maximum-pool-size` | `25` | `20` | Maximum JDBC connections held in pool |
| `minimum-idle` | `5` | `5` | Kept open during idle periods |
| `idle-timeout` | `30,000 ms` | `30,000 ms` | Max idle time before release |
| `connection-timeout` | `20,000 ms` | `20,000 ms` | Timeout before throwing SQL exception |
| `pool-name` | `VyomBenchmarkHikariPool` | `VyomHikariPool` | Thread/MDC tag for pool monitoring |

### Embedded Tomcat Threading
- Default worker threads: 200 max threads, 10 min spare threads.
- Accept queue size: 100 connections.

---

## 6. Gateway Configuration & Rate Limiting

Queried from `gateway/src/config/index.js` and `gateway/src/middleware/rateLimiter.js`:

| Setting | Default Value | Benchmark Profile Value | Rationale |
|---|---|---|---|
| `port` | `3000` | `3000` | Gateway HTTP listen port |
| `backendBaseUrl` | `http://localhost:8080` | `http://localhost:8080` | Target Spring Boot application |
| `timeoutMs` | `5000 ms` | `10000 ms` | Upstream backend proxy timeout |
| `rateLimitWindowMs` | `60,000 ms` | `60,000 ms` | Sliding window duration |
| `rateLimitMax` | `100 req/min` | `10,000 req/min` | Expanded for high-throughput benchmarking via `RATE_LIMIT_MAX` |
| `rateLimitMaxBuckets` | `10,000` | `10,000` | Maximum tracking buckets in memory |
| `trustProxy` | `false` | `false` | Reverse-proxy trust policy |

---

## 7. Load Generation Tooling Investigation

Per requirements, available benchmark tooling was inspected:
- **`k6`**: `Get-Command k6` $\to$ **NOT INSTALLED**.
- **`wrk`, `autocannon`, `ab`, `locust`, `jmeter`**: **NOT INSTALLED**.

### Approved Benchmarking Approach
To satisfy the strict boundary:
> *"Investigate whether k6 is installed. If not, identify an appropriate benchmarking approach without silently downloading dependencies or introducing unapproved infrastructure. Do not download benchmark tools without explicit approval."*

We utilize a **Native Node.js Benchmarking Engine** (`benchmark/engine.js`):
1. **Zero External Dependencies**: Operates exclusively using built-in Node.js v24 standard modules:
   - `node:http` and native global `fetch` (with connection reuse and HTTP keep-alive).
   - `node:perf_hooks` (`performance.now()`) providing microsecond-level timing accuracy.
   - `node:crypto` (`crypto.randomUUID()`) for valid, unique idempotency keys.
2. **True End-to-End Execution**: Issues asynchronous HTTP requests to `http://localhost:3000` (Gateway), traversing Gateway $\to$ Spring Boot Backend $\to$ HikariCP $\to$ PostgreSQL 18.4 $\to$ Ledger & Wallets.
3. **Rigorous Metric Separation**:
   - Explicitly records **Successful Financial TPS** (committed HTTP 200/201 transfers/fundings) separately from read/auth throughput.
   - Tracks response status codes, business rejections (e.g. 400 Insufficient Funds), and infrastructure errors.
   - Computes statistical percentiles (p50, p90, p95, p99, p99.9) using precise latency arrays.

---

## 8. Application Observability & Metrics Infrastructure

| Telemetry Source | Access Path | Metrics Collected |
|---|---|---|
| **Spring Boot Actuator** | `http://localhost:8080/actuator/metrics` | HikariCP pool (active, idle, pending), JVM heap/non-heap memory, CPU usage, Tomcat threads |
| **Correlation Tracking** | HTTP Header `X-Correlation-ID` | Propagated across gateway and backend SLF4J MDC |
| **Database Locks** | `pg_locks`, `pg_stat_activity` | Lock waits, deadlocks, and transaction blockages |
| **Database Health** | `http://localhost:3000/health` | Comprehensive gateway + backend + database component health |

---

## 9. Inventory Sign-Off

The environment is fully inventoried and verified. All runtime parameters and prerequisites for Phase 4.0 benchmark design and execution are confirmed.
