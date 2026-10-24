import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { filterAlerts, lossTotals, validateSnapshot } from './dashboard.js';
import './style.css';

const time = value => new Date(value).toLocaleTimeString([], { hour12: false });
const number = value => value.toLocaleString();

function App() {
  const [source, setSource] = useState('replay');
  const [data, setData] = useState(null);
  const [uploaded, setUploaded] = useState(null);
  const [error, setError] = useState('');
  const [rule, setRule] = useState('');
  const [node, setNode] = useState('');
  const [selected, setSelected] = useState(null);
  const [playing, setPlaying] = useState(false);
  const [position, setPosition] = useState(0);
  const fileInput = useRef(null);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    async function load() {
      try {
        const response = await fetch(source === 'live' ? `${import.meta.env.BASE_URL}api/dashboard` : `${import.meta.env.BASE_URL}replay.json`, { signal: controller.signal });
        if (!response.ok) throw new Error(`Data request failed (${response.status})`);
        const snapshot = validateSnapshot(await response.json());
        if (snapshot.mode !== source) throw new Error('Data source mode does not match selection');
        if (active) { setData(snapshot); setError(''); }
      } catch (err) { if (active && err.name !== 'AbortError') { setError(err.message); setData(null); } }
    }
    setData(null); setSelected(null); setRule(''); setNode(''); setPosition(0); setPlaying(false);
    if (source === 'upload') { setData(uploaded); setPosition(uploaded?.alerts.length || 0); }
    else load();
    const interval = source === 'live' ? setInterval(load, 5000) : null;
    return () => { active = false; controller.abort(); clearInterval(interval); };
  }, [source, uploaded]);

  useEffect(() => {
    if (!playing || !data) return;
    const interval = setInterval(() => setPosition(value => {
      if (value >= data.alerts.length) { setPlaying(false); return value; }
      return value + 1;
    }), 1200);
    return () => clearInterval(interval);
  }, [playing, data]);

  async function upload(event) {
    const file = event.target.files?.[0];
    if (!file) return;
    try {
      if (file.size > 5 * 1024 * 1024) throw new Error('Replay files must be smaller than 5 MB');
      const snapshot = validateSnapshot(JSON.parse(await file.text()));
      if (snapshot.mode !== 'replay') throw new Error('Uploaded snapshots must be marked replay');
      setUploaded(snapshot); setSource('upload'); setError('');
    } catch (err) { setError(err.message); }
    event.target.value = '';
  }

  const chronological = [...(data?.alerts || [])].sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp));
  const available = source === 'live' ? chronological : chronological.slice(0, position);
  const alerts = filterAlerts(available, rule, node);
  const focus = alerts.find(alert => alert.id === selected) || alerts[0];
  const totals = lossTotals(data?.loss || []);
  const nodes = [...new Set([...(data?.alerts || []).flatMap(alert => alert.nodes), ...(data?.loss || []).map(row => row.node)])].sort();

  return <div className="shell">
    <header><div className="brand"><span className="brandmark">⌁</span><div>CROSSNODE<span className="subbrand">DETECTION OPERATIONS</span></div></div>
      <div className="toolbar"><label>Source <select value={source} onChange={event => setSource(event.target.value)}><option value="replay">Synthetic replay</option><option value="live">Live API</option>{source === 'upload' && <option value="upload">Uploaded replay</option>}</select></label><button onClick={() => fileInput.current.click()}>Import replay</button><input ref={fileInput} type="file" accept=".json,application/json" onChange={upload} hidden/></div></header>
    <main><div className="page-heading"><div><p className="eyebrow">TELEMETRY / CORRELATION</p><h1>Follow the movement.</h1><p className="muted">Cross-node connections and rule alerts with telemetry loss in view.</p></div><span className={`badge ${source === 'live' ? 'live' : ''}`}>{source === 'live' ? '● LIVE DATA' : '◈ SYNTHETIC REPLAY'}</span></div>
      {source !== 'live' && <div className="notice">Illustrative fixture data. These values are not benchmark or detection results. Loss counters show the fixture’s final snapshot.</div>}
      {source === 'live' && data && !data.detection_enabled && <div className="notice">Detection topology is disabled. This view displays alerts and loss snapshots supplied to Kafka.</div>}
      {error && <div role="alert" className="error">{error}</div>}
      <section className="stats" aria-label="Telemetry summary">{[['Visible alerts', alerts.length], ['Observed nodes', nodes.length], ['Received events', data?.loss.length ? number(totals.received) : '—'], ['Sequence gaps', data?.loss.length ? number(totals.sequence_gaps) : '—']].map(([label, value]) => <div className="stat" key={label}><span>{label}</span><strong>{value}</strong></div>)}</section>
      <div className="workspace"><section className="panel timeline"><div className="panel-heading"><h2>Alert timeline</h2><span className="muted">{alerts.length} events</span></div>
        <div className="filters"><label>Rule<select value={rule} onChange={event => setRule(event.target.value)}><option value="">All rules</option>{[...new Set((data?.alerts || []).map(alert => alert.rule_id))].map(value => <option key={value}>{value}</option>)}</select></label><label>Node<select value={node} onChange={event => setNode(event.target.value)}><option value="">All nodes</option>{nodes.map(value => <option key={value}>{value}</option>)}</select></label></div>
        {source !== 'live' && data && <div className="playback"><button onClick={() => { if (position >= data.alerts.length) setPosition(0); setPlaying(!playing); }}>{playing ? 'Pause' : 'Play'}</button><input aria-label="Replay position" type="range" min="0" max={data.alerts.length} value={position} onChange={event => { setPosition(Number(event.target.value)); setPlaying(false); }}/><span>{position}/{data.alerts.length}</span><button onClick={() => { setPosition(data.alerts.length); setPlaying(false); }}>All</button></div>}
        <div className="alert-list">{alerts.map(alert => <button className={`alert-row ${focus?.id === alert.id ? 'selected' : ''}`} key={alert.id} onClick={() => setSelected(alert.id)}><span className={`severity ${alert.severity}`}>{alert.severity}</span><strong>{alert.title}</strong><span className="muted">{time(alert.timestamp)} · {alert.nodes.join(' → ')}</span><code>{alert.rule_id}</code></button>)}{!alerts.length && <p className="empty">{data ? source === 'live' ? 'No alerts received.' : 'Press Play or All to explore this replay.' : error ? 'Data unavailable.' : 'Loading telemetry…'}</p>}</div>
      </section><div className="right-column"><section className="panel"><div className="panel-heading"><h2>Connection / event path</h2><span className="muted">{focus?.nodes.length || 0} nodes</span></div>{focus ? <><div className="graph">{focus.nodes.map((name, index) => <React.Fragment key={`${name}-${index}`}>{index > 0 && <span className="arrow" aria-hidden="true">→</span>}<div className="graph-node"><span className="node-icon">▣</span><strong>{name}</strong><small>{index === 0 ? 'Origin' : 'Destination'}</small></div></React.Fragment>)}</div><div className="detail"><span className={`severity ${focus.severity}`}>{focus.severity}</span><h3>{focus.title}</h3><p className="muted">{focus.description || 'No additional description supplied.'}</p><p>Telemetry: <strong>{focus.known_loss ? 'Known loss reported' : 'No loss annotation supplied'}</strong></p><code>{focus.id}</code></div></> : <p className="empty">Select an alert to inspect its path.</p>}</section>
      <section className="panel"><div className="panel-heading"><h2>Telemetry loss</h2><span className="muted">Per agent run</span></div><div className="table-scroll"><table><thead><tr><th>Node / boot</th><th>Received</th><th>Gaps</th><th>Kernel drops</th><th>Capture errors</th><th>Run status</th></tr></thead><tbody>{(data?.loss || []).map(row => <tr key={`${row.node}:${row.boot_id}:${row.agent_run_id}`}><td>{row.node}<small>{row.boot_id.slice(0, 12)} · {time(row.timestamp)}</small></td><td>{number(row.received)}</td><td className={row.sequence_gaps ? 'warning' : ''}>{number(row.sequence_gaps)}</td><td>{number(row.kernel_drops)}</td><td>{row.capture_errors === undefined ? "—" : number(row.capture_errors)}</td><td>{row.closed === undefined ? "—" : row.closed ? "Closed" : "Open"}{row.late_unreconciled > 0 && <small>{number(row.late_unreconciled)} too late to reconcile</small>}</td></tr>)}</tbody></table>{!data?.loss.length && <p className="empty">No loss snapshots received.</p>}</div><p className="footnote">Gaps include provisional and finalized missing sequences; late arrivals can reconcile provisional gaps. Kernel drops and capture errors are separate counters. Zero gaps alone does not establish zero loss.</p></section></div></div>
      <footer><span>CROSSNODE / RESEARCH BUILD</span><span>{data?.generated_at ? `Snapshot ${new Date(data.generated_at).toLocaleString()}` : 'Awaiting snapshot'}</span></footer>
    </main></div>;
}

createRoot(document.getElementById('root')).render(<App/>);
