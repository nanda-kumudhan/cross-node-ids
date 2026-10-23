export function validateSnapshot(data) {
  if (!data || !['live', 'replay'].includes(data.mode) || !Array.isArray(data.alerts) || !Array.isArray(data.loss)
      || !Number.isFinite(Date.parse(data.generated_at))) {
    throw new Error('Invalid dashboard snapshot');
  }
  for (const alert of data.alerts) {
    if (typeof alert.id !== 'string' || !alert.id || typeof alert.title !== 'string' || !alert.title || typeof alert.rule_id !== 'string'
        || !Number.isFinite(Date.parse(alert.timestamp)) || !Array.isArray(alert.nodes)
        || !alert.nodes.length || !alert.nodes.every(node => typeof node === 'string')
        || !['low', 'medium', 'high', 'critical'].includes(alert.severity)) {
      throw new Error('Invalid alert in snapshot');
    }
  }
  if (new Set(data.alerts.map(alert => alert.id)).size !== data.alerts.length) throw new Error('Duplicate alert IDs');
  for (const loss of data.loss) {
    if (typeof loss.node !== 'string' || typeof loss.boot_id !== 'string' || typeof loss.agent_run_id !== 'string'
        || !Number.isFinite(Date.parse(loss.timestamp))
        || !['received', 'sequence_gaps', 'kernel_drops'].every(key => Number.isSafeInteger(loss[key]) && loss[key] >= 0)) {
      throw new Error('Invalid loss counters in snapshot');
    }
  }
  if (new Set(data.loss.map(row => JSON.stringify([row.node, row.boot_id, row.agent_run_id]))).size !== data.loss.length) {
    throw new Error('Duplicate loss scopes');
  }
  return data;
}

export function filterAlerts(alerts, rule, node) {
  return alerts.filter(alert => (!rule || alert.rule_id === rule) && (!node || alert.nodes.includes(node)))
    .sort((a, b) => Date.parse(b.timestamp) - Date.parse(a.timestamp));
}

export function lossTotals(rows) {
  return rows.reduce((sum, row) => ({ received: sum.received + row.received,
    sequence_gaps: sum.sequence_gaps + row.sequence_gaps, kernel_drops: sum.kernel_drops + row.kernel_drops }),
  { received: 0, sequence_gaps: 0, kernel_drops: 0 });
}
