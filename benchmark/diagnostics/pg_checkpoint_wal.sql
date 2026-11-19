-- SQL Diagnostic: Checkpoint, WAL & Buffer Statistics
-- Phase 4.3.3: Bottleneck Isolation
SELECT 
    'CHECKPOINTER' AS metric_type,
    num_timed,
    num_requested,
    num_done,
    write_time AS write_time_ms,
    sync_time AS sync_time_ms,
    buffers_written,
    slru_written
FROM pg_stat_checkpointer;

SELECT 
    'WAL' AS metric_type,
    wal_records,
    wal_fpi,
    wal_bytes,
    wal_buffers_full
FROM pg_stat_wal;

SELECT 
    'DATABASE' AS metric_type,
    datname,
    xact_commit,
    xact_rollback,
    blks_read,
    blks_hit,
    round((blks_hit * 100.0 / NULLIF(blks_hit + blks_read, 0)), 2) AS hit_ratio_pct,
    tup_inserted,
    tup_updated,
    tup_deleted,
    conflicts,
    deadlocks
FROM pg_stat_database
WHERE datname = current_database();
