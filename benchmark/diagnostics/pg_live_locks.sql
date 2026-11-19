-- SQL Diagnostic: Live Locks, Blocked Queries & Wait Events
-- Phase 4.3.3: Bottleneck Isolation
SELECT 
    a.pid,
    a.usename,
    a.client_addr,
    a.client_port,
    a.state,
    a.wait_event_type,
    a.wait_event,
    pg_blocking_pids(a.pid) AS blocking_pids,
    clock_timestamp() - a.query_start AS query_duration,
    clock_timestamp() - a.xact_start AS xact_duration,
    l.locktype,
    l.mode,
    l.granted,
    l.relation::regclass AS locked_relation,
    LEFT(a.query, 120) AS query_snippet
FROM pg_stat_activity a
LEFT JOIN pg_locks l ON a.pid = l.pid
WHERE a.datname = current_database()
  AND a.pid != pg_backend_pid()
  AND a.state != 'idle'
ORDER BY query_duration DESC NULLS LAST;
