import test from 'node:test';
import assert from 'node:assert/strict';
import { filterAlerts, lossTotals, validateSnapshot } from './dashboard.js';

test('empty live data stays empty', () => {
  assert.deepEqual(validateSnapshot({ mode: 'live', generated_at: '2026-01-01T00:00:00Z', alerts: [], loss: [] }).alerts, []);
});
test('invalid snapshots cannot display fabricated counters', () => {
  assert.throws(() => validateSnapshot({ mode: 'live', generated_at: '2026-01-01T00:00:00Z', alerts: [],
    loss: [{ node: 'a', boot_id: 'b', agent_run_id: 'r', timestamp: '2026-01-01T00:00:00Z', received: -1, sequence_gaps: 0, kernel_drops: 0 }] }));
});
test('rule and node filters intersect', () => {
  const rows = [{ rule_id: 'ssh', nodes: ['a', 'b'], timestamp: '2026-01-01' }, { rule_id: 'tmp', nodes: ['b'], timestamp: '2026-01-02' }];
  assert.equal(filterAlerts(rows, 'ssh', 'b').length, 1);
  assert.equal(filterAlerts(rows, 'ssh', 'c').length, 0);
});
test('kernel drops and sequence gaps remain separate', () => {
  assert.deepEqual(lossTotals([{ received: 90, sequence_gaps: 10, kernel_drops: 6 }]),
    { received: 90, sequence_gaps: 10, kernel_drops: 6 });
});
