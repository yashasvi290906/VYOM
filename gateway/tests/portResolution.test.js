const { test, describe } = require('node:test');
const assert = require('node:assert');
const { resolvePort } = require('../src/config');

describe('Gateway & Backend Port Resolution and Validation', () => {

  test('Scenario 1: Default configuration returns port 3000', () => {
    const port = resolvePort(undefined, undefined, 3000);
    assert.strictEqual(port, 3000);
  });

  test('Scenario 2: Explicit GATEWAY_PORT takes highest precedence', () => {
    // GATEWAY_PORT=4000, PORT=5000 -> 4000
    const port = resolvePort('4000', '5000', 3000);
    assert.strictEqual(port, 4000);
  });

  test('Scenario 3: Generic PORT used when GATEWAY_PORT is unset', () => {
    // GATEWAY_PORT=undefined, PORT=5000 -> 5000
    const port = resolvePort(undefined, '5000', 3000);
    assert.strictEqual(port, 5000);
  });

  test('Scenario 3b: Empty or whitespace GATEWAY_PORT falls back to PORT', () => {
    const port = resolvePort('  ', '5000', 3000);
    assert.strictEqual(port, 5000);
  });

  test('Scenario 4: Boundary values 1 and 65535 are accepted', () => {
    assert.strictEqual(resolvePort('1', undefined, 3000), 1);
    assert.strictEqual(resolvePort('65535', undefined, 3000), 65535);
  });

  test('Scenario 5: Out of range ports throw fatal configuration error', () => {
    // 0 is invalid
    assert.throws(() => resolvePort('0', undefined, 3000), /Port configuration value "0" is invalid/);
    // Negative is invalid
    assert.throws(() => resolvePort('-1', undefined, 3000), /Port configuration value "-1" is invalid/);
    // > 65535 is invalid
    assert.throws(() => resolvePort('65536', undefined, 3000), /Port configuration value "65536" is invalid/);
    assert.throws(() => resolvePort('99999', undefined, 3000), /Port configuration value "99999" is invalid/);
  });

  test('Scenario 6: Non-numeric and decimal port values throw fatal error', () => {
    assert.throws(() => resolvePort('abc', undefined, 3000), /Port configuration value "abc" is invalid/);
    assert.throws(() => resolvePort('3000.5', undefined, 3000), /Port configuration value "3000.5" is invalid/);
  });
});
