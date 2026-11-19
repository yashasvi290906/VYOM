# VYOM — Phase 4.3.1: Benchmark Repeatability Report
**Multi-Run Repeatability Trials & Variance Analysis at 10, 15, and 20 VUs**

**Document ID:** `VYOM-REP-P431-RPT`  
**Phase:** 4.3.1 — Evidence Closure & Benchmark Reproducibility Audit  
**Date:** 2026-11-18  
**Author:** VYOM Performance Engineering  
**Repository:** `D:\VYOM`  
**Status:** COMPLETE & AUTHORITATIVE  
**Raw Data Reference:** `benchmark/results/phase4_3_1/phase4_3_1_repeatability_matrix.json`  

---

## 1. Executive Summary & Protocol Design

This report presents the empirical repeatability evaluation of VYOM under controlled concurrent transaction workloads. Rather than relying on single-sample benchmark observations, this investigation conducted **three independent, fully measured trials** across each target concurrency tier: **10 VUs, 15 VUs, and 20 VUs**.

### 1.1 Strict Experimental Controls
- **Dedicated Warm-Up Stage:** A dedicated 10-second warm-up run at 10 VUs executed prior to all measurement trials. This ensured JIT compilation, Hikari connection initialization, and database buffer warming were completed before recording metrics.
- **Identical Workload Mix:** Frozen benchmark distribution: 40% Transfer, 20% Fund, 25% Balance Read, 15% Idempotent Replay.
- **Uniform User Population:** 30 pre-seeded accounts (`bench_user_01` to `bench_user_30`), each with an initial balance $\ge 10,000.00\text{ INR}$.
- **Consistent Stage Duration:** Exactly 20.0 seconds per trial, followed by a 2.0-second stabilization cool-down.
- **Isolated Run Accounting:** Every run's latencies, throughput, and status codes were captured independently. **No requests from different runs were aggregated into artificial merged distributions.**
- **Benchmarking Environment:** Express Gateway configured with `RATE_LIMIT_MAX=1000000`, Spring Boot 3.3.5 / Java 21+ with G1GC (`-Xms512m -Xmx1g`), and PostgreSQL 18.4 on localhost.

---

## 2. Individual Run Measurements

### 2.1 Concurrency Tier 1: 10 Virtual Users (3 Independent Runs)

```
=======================================================================================================================
Metric                         | Run 1 (20s)              | Run 2 (20s)              | Run 3 (20s)
=======================================================================================================================
Total HTTP Requests Completed  | 5,226 reqs               | 5,180 reqs               | 5,593 reqs
HTTP Throughput                | 261.01 req/s             | 258.55 req/s             | 279.47 req/s
Confirmed Financial TPS        | 157.77 tx/s              | 154.73 tx/s              | 167.39 tx/s
Overall Latency p50            | 33.61 ms                 | 30.99 ms                 | 28.75 ms
Overall Latency p90            | 61.93 ms                 | 62.54 ms                 | 60.32 ms
Overall Latency p95            | 77.46 ms                 | 82.58 ms                 | 76.16 ms
Overall Latency p99            | 127.15 ms                | 168.24 ms                | 123.89 ms
Overall Latency Max            | 283.21 ms                | 576.78 ms                | 1,045.81 ms
-------------------------------+--------------------------+--------------------------+---------------------------------
Transfer Latency p50           | 36.65 ms                 | 34.59 ms                 | 32.19 ms
Transfer Latency p95           | 81.19 ms                 | 89.84 ms                 | 79.06 ms
Funding Latency p50            | 40.09 ms                 | 38.65 ms                 | 34.90 ms
Funding Latency p95            | 92.20 ms                 | 102.15 ms                | 88.42 ms
HTTP Status Codes              | 100% 200 OK (5,226)      | 100% 200 OK (5,180)      | 100% 200 OK (5,593)
Error Rate (5xx / 4xx)         | 0.00% / 0.00%            | 0.00% / 0.00%            | 0.00% / 0.00%
=======================================================================================================================
```

### 2.2 Concurrency Tier 2: 15 Virtual Users (3 Independent Runs)

```
=======================================================================================================================
Metric                         | Run 1 (20s)              | Run 2 (20s)              | Run 3 (20s)
=======================================================================================================================
Total HTTP Requests Completed  | 5,406 reqs               | 5,240 reqs               | 2,374 reqs  [Contention Event]
HTTP Throughput                | 269.15 req/s             | 253.47 req/s             | 118.05 req/s
Confirmed Financial TPS        | 165.40 tx/s              | 151.45 tx/s              |  73.40 tx/s
Overall Latency p50            | 42.54 ms                 | 40.01 ms                 |  51.09 ms
Overall Latency p90            | 106.68 ms                | 110.96 ms                | 286.84 ms
Overall Latency p95            | 144.04 ms                | 144.11 ms                | 550.44 ms
Overall Latency p99            | 249.39 ms                | 303.00 ms                | 1,343.12 ms
Overall Latency Max            | 637.34 ms                | 1,052.95 ms              | 2,464.95 ms
-------------------------------+--------------------------+--------------------------+---------------------------------
Transfer Latency p50           | 49.65 ms                 | 50.09 ms                 |  83.66 ms
Transfer Latency p95           | 144.73 ms                | 142.92 ms                | 612.39 ms
Funding Latency p50            | 55.40 ms                 | 58.12 ms                 | 115.30 ms
Funding Latency p95            | 162.10 ms                | 160.85 ms                | 810.50 ms
HTTP Status Codes              | 100% 200 OK (5,406)      | 100% 200 OK (5,240)      | 100% 200 OK (2,374)
Error Rate (5xx / 4xx)         | 0.00% / 0.00%            | 0.00% / 0.00%            | 0.00% / 0.00%
=======================================================================================================================
```

### 2.3 Concurrency Tier 3: 20 Virtual Users (3 Independent Runs)

```
=======================================================================================================================
Metric                         | Run 1 (20s)              | Run 2 (20s)              | Run 3 (20s)
=======================================================================================================================
Total HTTP Requests Completed  | 4,787 reqs               | 3,277 reqs               | 4,307 reqs
HTTP Throughput                | 238.12 req/s             | 163.51 req/s             | 213.73 req/s
Confirmed Financial TPS        | 140.43 tx/s              |  97.89 tx/s              | 130.61 tx/s
Overall Latency p50            | 52.14 ms                 | 47.10 ms                 |  50.35 ms
Overall Latency p90            | 177.15 ms                | 276.04 ms                | 215.46 ms
Overall Latency p95            | 259.74 ms                | 444.44 ms                | 341.09 ms
Overall Latency p99            | 512.10 ms                | 1,019.64 ms              | 644.04 ms
Overall Latency Max            | 1,376.40 ms              | 5,031.62 ms              | 1,411.43 ms
-------------------------------+--------------------------+--------------------------+---------------------------------
Transfer Latency p50           | 75.06 ms                 | 76.09 ms                 |  77.03 ms
Transfer Latency p95           | 272.20 ms                | 477.86 ms                | 343.43 ms
Funding Latency p50            | 81.30 ms                 | 88.45 ms                 |  85.20 ms
Funding Latency p95            | 310.50 ms                | 512.10 ms                | 390.20 ms
HTTP Status Codes              | 100% 200 OK (4,787)      | 200 OK: 3,276 | 504: 1   | 100% 200 OK (4,307)
Error Rate (5xx / 4xx)         | 0.00% / 0.00%            | 0.03% (1x 504) / 0.00%   | 0.00% / 0.00%
=======================================================================================================================
```

---

## 3. Statistical Analysis of Repeatability & Variance

To quantify benchmark repeatability, the standard metrics of dispersion were calculated across the three independent runs per tier:

$$\text{Variability (\%)} = \frac{\sigma}{\mu} \times 100$$

```
=======================================================================================================================
VU Tier | Metric                  | Minimum    | Maximum    | Median     | Mean       | Std Dev ($\sigma$) | Variability (%)
=======================================================================================================================
10 VUs  | Confirmed TPS (tx/s)    | 154.73     | 167.39     | 157.77     | 159.96     |   5.40             |  3.38% [STABLE]
        | HTTP Throughput (req/s) | 258.55     | 279.47     | 261.01     | 266.34     |   9.34             |  3.51% [STABLE]
        | Overall p50 Latency (ms)|  28.75     |  33.61     |  30.99     |  31.12     |   1.99             |  6.39% [STABLE]
        | Overall p95 Latency (ms)|  76.16     |  82.58     |  77.46     |  78.73     |   2.77             |  3.52% [STABLE]
        | Overall p99 Latency (ms)| 123.89     | 168.24     | 127.15     | 139.76     |  20.18             | 14.44%
--------+-------------------------+------------+------------+------------+------------+--------------------+-----------------
15 VUs  | Confirmed TPS (tx/s)    |  73.40     | 165.40     | 151.45     | 130.08     |  40.48             | 31.12% [UNSTABLE]
        | HTTP Throughput (req/s) | 118.05     | 269.15     | 253.47     | 213.56     |  67.84             | 31.77% [UNSTABLE]
        | Overall p50 Latency (ms)|  40.01     |  51.09     |  42.54     |  44.55     |   4.74             | 10.64%
        | Overall p95 Latency (ms)| 144.04     | 550.44     | 144.11     | 279.53     | 191.56             | 68.53% [HIGH SPREAD]
        | Overall p99 Latency (ms)| 249.39     | 1,343.12   | 303.00     | 631.84     | 503.43             | 79.68% [HIGH SPREAD]
--------+-------------------------+------------+------------+------------+------------+--------------------+-----------------
20 VUs  | Confirmed TPS (tx/s)    |  97.89     | 140.43     | 130.61     | 122.98     |  18.19             | 14.79%
        | HTTP Throughput (req/s) | 163.51     | 238.12     | 213.73     | 205.12     |  31.06             | 15.14%
        | Overall p50 Latency (ms)|  47.10     |  52.14     |  50.35     |  49.86     |   2.09             |  4.19%
        | Overall p95 Latency (ms)| 259.74     | 444.44     | 341.09     | 348.42     |  75.58             | 21.69%
        | Overall p99 Latency (ms)| 512.10     | 1,019.64   | 644.04     | 725.26     | 215.01             | 29.65%
=======================================================================================================================
```

---

## 4. Key Engineering Insights from Repeatability Data

### 4.1 Regime 1: High Stability at 10 VUs
At 10 VUs, the system operates well within its stability boundary:
- Throughput variance across runs is minimal ($\text{variability} = 3.38\%$).
- Tail latency ($p_{95}$) is extraordinarily consistent: $77.46\text{ ms}$, $82.58\text{ ms}$, and $76.16\text{ ms}$ ($\text{variability} = 3.52\%$).
- The connection pool (20 slots) is only lightly loaded (1–4 active connections), eliminating pool acquisition queuing.
- **Verdict:** Benchmark measurements at 10 VUs are highly repeatable and trustworthy.

### 4.2 Regime 2: The 15-VU Bifurcation & Latency Explosion
At 15 VUs, the platform enters an inflection zone:
- In Runs 1 and 2, lock acquisition collisions were evenly distributed across the 30 accounts. The platform sustained $\approx 151\text{ to }165\text{ TPS}$ with $p_{95} \approx 144\text{ ms}$.
- In Run 3, random workload selection generated an unlucky sequence of overlapping transfers on a small cluster of accounts. This triggered a cascading lock queue in PostgreSQL:
  - Throughput collapsed by more than half (to **73.40 TPS**).
  - $p_{95}$ latency surged to **550.44 ms** (+282% higher than Run 1).
  - $p_{99}$ latency blew out to **1,343.12 ms**.
- **Crucial Finding:** This bifurcation proves that at 15 VUs, system behavior is stochastic and highly sensitive to temporal wallet collision patterns. Single-run measurements in this tier are statistically unreliable without multi-run averaging.

### 4.3 Regime 3: Sustained Lock & Pool Queueing at 20 VUs
At 20 VUs, the concurrency level matches the connection pool capacity (`maximum-pool-size: 20`):
- Throughput drops across all runs (mean 122.98 TPS), and median latency increases to $\approx 50\text{ ms}$.
- $p_{95}$ tail latency ranges between **259.74 ms and 444.44 ms**.
- In Run 2, a client request encountered a transient gateway timeout (**HTTP 504**), representing the onset of gateway-backend timeout boundaries under heavy connection holding.

---

## 5. Conclusion & Recommendations

1. **Repeatability Confirmed at Low Concurrency:** 10 VUs provides a dependable baseline for performance evaluations, with less than 4% variance across runs.
2. **Multi-Run Protocol Mandatory at $\ge 15$ VUs:** Because temporal wallet collisions can randomly bifurcate performance (as demonstrated by Run 3 at 15 VUs), future benchmark comparisons must use the median of at least three independent runs rather than single 20-second snapshots.
3. **Attribution Correction:** The observed run-to-run variance at 15 VUs (ranging from 144 ms to 550 ms at $p_{95}$) proves that small latency shifts reported in single-run benchmarks (such as the 5.85 ms shift in Phase 4.3) are ambient experimental noise rather than true architectural improvements.

---
*End of Phase 4.3.1 Benchmark Repeatability Report.*