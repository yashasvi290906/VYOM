const { test, describe } = require('node:test');
const assert = require('node:assert');
const { buildPsqlArgs, runQuery } = require('./reconcile');

describe('VYOM Financial Reconciliation CLI', () => {

  test('uses portable psql executable by default', () => {
    const args = buildPsqlArgs({}, 'vyom_test');

    assert.deepStrictEqual(args, [
      '-X',
      '-q',
      '-t',
      '-A',
      '-v',
      'ON_ERROR_STOP=1',
      '-U',
      'postgres',
      '-d',
      'vyom_test'
    ]);
  });

  test('supports configurable PostgreSQL connection settings', () => {
    const args = buildPsqlArgs({
      BENCHMARK_DB_USER: 'vyom_app',
      BENCHMARK_DB_HOST: 'localhost',
      BENCHMARK_DB_PORT: '5433'
    }, 'vyom_test');

    assert.deepStrictEqual(args.slice(-8), [
      '-U',
      'vyom_app',
      '-d',
      'vyom_test',
      '-h',
      'localhost',
      '-p',
      '5433'
    ]);
  });

  test('supports standard PostgreSQL environment fallbacks', () => {
    const args = buildPsqlArgs({
      PGUSER: 'postgres_user',
      PGHOST: '127.0.0.1',
      PGPORT: '5432'
    }, 'vyom_test');

    assert.ok(args.includes('postgres_user'));
    assert.ok(args.includes('127.0.0.1'));
    assert.ok(args.includes('5432'));
  });

  test('keeps shell metacharacters inside a single database argument', () => {
    const dbName = 'vyom; echo injected && whoami';
    const args = buildPsqlArgs({}, dbName);

    assert.strictEqual(args[args.indexOf('-d') + 1], dbName);
  });

  test('does not place database passwords in command arguments', () => {
    const password = 'test-only-database-password';
    const env = {
      PGPASSWORD: password,
      PSQL_PATH: '/usr/bin/psql'
    };

    const args = buildPsqlArgs(env, 'vyom_test');

    assert.ok(!args.includes(password));
  });

  test('executes psql with an argument array and returns trimmed output', () => {
    let capturedExecutable;
    let capturedArgs;
    let capturedOptions;

    const result = runQuery('SELECT 1;', {
      dbName: 'vyom_test',
      env: { PSQL_PATH: 'custom-psql' },
      execFile: (executable, args, options) => {
        capturedExecutable = executable;
        capturedArgs = args;
        capturedOptions = options;
        return '  1\n';
      }
    });

    assert.strictEqual(capturedExecutable, 'custom-psql');
    assert.strictEqual(capturedArgs[capturedArgs.indexOf('-d') + 1], 'vyom_test');
    assert.strictEqual(capturedOptions.input, 'SELECT 1;');
    assert.strictEqual(capturedOptions.encoding, 'utf8');
    assert.strictEqual(capturedOptions.env.PSQL_PATH, 'custom-psql');
    assert.strictEqual(result, '1');
  });

  test('fails with a clear error when PostgreSQL execution fails', () => {
    assert.throws(
      () => runQuery('SELECT 1;', {
        dbName: 'vyom_test',
        env: {},
        execFile: () => {
          throw new Error('psql exited with code 1');
        }
      }),
      /RECONCILIATION QUERY EXECUTION ERROR on database 'vyom_test'/
    );
  });

});
