-- SQL Diagnostic: Table Sizes, Row Counts & Scan Types
-- Phase 4.3.3: Bottleneck Isolation
SELECT 
    c.relname AS relation_name,
    c.relkind AS relation_kind,
    pg_size_pretty(pg_relation_size(c.oid)) AS table_size,
    pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size,
    t.n_live_tup AS live_tuples,
    t.n_dead_tup AS dead_tuples,
    t.seq_scan,
    t.seq_tup_read,
    t.idx_scan,
    t.idx_tup_fetch,
    t.n_tup_ins AS tuples_inserted,
    t.n_tup_upd AS tuples_updated
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_stat_user_tables t ON t.relid = c.oid
WHERE n.nspname = 'public' 
  AND c.relkind = 'r'
ORDER BY pg_total_relation_size(c.oid) DESC;
