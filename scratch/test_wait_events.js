const { execSync } = require('child_process');
const PSQL_PATH = 'D:\\Postgres\\bin\\psql.exe';
const DB_NAME = 'payledger_p433';

function sampleLiveWaitEvents() {
  const sql = `
    SELECT COALESCE(wait_event_type, 'CPU/Active') || ':' || COALESCE(wait_event, 'running') AS event,
           count(*),
           count(*) FILTER (WHERE array_length(pg_blocking_pids(pid), 1) > 0) AS blocked_count
    FROM pg_stat_activity 
    WHERE datname = '${DB_NAME}' AND pid != pg_backend_pid() AND state != 'idle'
    GROUP BY 1;
  `;
  try {
    const raw = execSync(`"${PSQL_PATH}" -U postgres -d ${DB_NAME} -t -A -c "${sql.replace(/"/g, '\\"')}"`, {
      env: { ...process.env, PGPASSWORD: '' }
    }).toString().trim();
    console.log('RAW OUTPUT:', JSON.stringify(raw));
  } catch (err) {
    console.log('ERROR:', err.message);
  }
}
sampleLiveWaitEvents();
