'use strict';

const $ = (id) => document.getElementById(id);
const form = $('run-form');
let lastState = null;
let lastRunsRefresh = 0;
let grafanaBase = null;
const PROTOCOLS = [['ldap', 'LDAP'], ['kerberos', 'Kerberos'], ['ntlm', 'NTLM'], ['computer', 'Computers']];

async function api(path, options) {
  const res = await fetch(path, options);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(body.error || `${res.status} ${res.statusText}`);
  }
  return body;
}

function fmt(n, digits = 0) {
  if (n === null || n === undefined || n === '' || Number.isNaN(Number(n))) return '–';
  return Number(n).toLocaleString('en-GB', { maximumFractionDigits: digits, minimumFractionDigits: digits });
}

function fmtDuration(sec) {
  sec = Math.max(0, Math.round(sec));
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return m ? `${m}m ${String(s).padStart(2, '0')}s` : `${s}s`;
}

function setText(id, value) {
  $(id).textContent = value;
}

async function loadConfig() {
  const cfg = await api('/api/config');
  const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
  const base = `${cfg.grafanaUrl}/d/${cfg.dashboardUid}`;
  grafanaBase = base;
  $('grafana-link').href = `${base}?from=now-15m&to=now`;
  $('grafana').src = `${base}?from=now-15m&to=now&refresh=5s&kiosk&theme=${dark ? 'dark' : 'light'}`;

  // Remote load generators (JMeter servers), when the controller has any.
  const generators = cfg.generators || [];
  if (generators.length) {
    $('generators').replaceChildren(...generators.map((host, i) => {
      const opt = document.createElement('option');
      opt.value = String(i + 1);
      opt.textContent = i === 0 ? `1 (${host})` : `${i + 1} of ${generators.length}`;
      return opt;
    }));
    $('generators').value = String(generators.length);
    $('generators-field').hidden = false;
  }
}

/** Plain-language check of the load settings: how long the attempts take at the target rate. */
function updateLoadSummary() {
  const f = new FormData(form);
  const duration = Number(f.get('duration')) || 0;
  const lines = [];
  for (const [key, name] of PROTOCOLS) {
    const threads = Number(f.get(`${key}Threads`)) || 0;
    const rate = Number(f.get(`${key}Rate`)) || 0;
    const attempts = Number(f.get(`${key}Attempts`)) || 0;
    if (!threads || (!rate && !attempts)) continue;
    if (rate && attempts) {
      const needed = attempts / rate;
      const late = needed > duration ? ` (longer than the ${fmtDuration(duration)} duration, which ends the run first)` : '';
      lines.push(`${name}: ${fmt(attempts)} attempts at ${fmt(rate, 1)}/s ≈ ${fmtDuration(needed)}${late}.`);
    } else if (rate) {
      lines.push(`${name}: ${fmt(rate, 1)}/s for ${fmtDuration(duration)} ≈ ${fmt(rate * duration)} attempts.`);
    } else {
      lines.push(`${name}: stops after ${fmt(attempts)} attempts, as fast as ${threads} threads go.`);
    }
  }
  setText('load-summary', lines.join(' '));
}

async function loadProfiles() {
  const profiles = await api('/api/profiles');
  const select = $('profile');
  select.replaceChildren(...profiles.map((p) => {
    const opt = document.createElement('option');
    opt.value = p;
    opt.textContent = p.replace(/\.properties$/, '');
    return opt;
  }));
  if (profiles.includes('lab.properties')) select.value = 'lab.properties';
}

function renderStatus(status) {
  const state = status.state;
  const run = status.run;
  const pill = $('state-pill');
  pill.className = `pill pill-${state}`;
  pill.textContent = { idle: 'Idle', running: 'Running', stopping: 'Stopping' }[state] || state;

  const busy = state !== 'idle';
  $('start-btn').disabled = busy;
  $('stop-btn').disabled = state !== 'running';
  for (const el of form.elements) {
    if (el.id !== 'stop-btn' && el.id !== 'start-btn') el.disabled = busy;
  }

  if (!run) return;

  setText('run-id', run.id);
  const pct = Math.min(100, (run.elapsedSec / run.duration) * 100);
  $('progress-bar').style.width = `${busy ? pct : 100}%`;
  if (busy) {
    const threads = PROTOCOLS.filter(([key]) => run[`${key}Threads`] > 0).map(([key, name]) => `${name} ${run[`${key}Threads`]}`).join(' / ');
    const label = run.label ? ` · ${run.label}` : '';
    const gens = run.generators > 1 ? ` on ${run.generators} generators` : '';
    setText('progress-text', `${fmtDuration(run.elapsedSec)} of up to ${fmtDuration(run.duration)} · ${run.profile.replace(/\.properties$/, '')}${label} · ${threads} threads${gens}`);
  } else {
    const outcome = run.stopRequested ? 'stopped early' : run.exitCode === 0 ? 'completed' : `failed (exit ${run.exitCode})`;
    setText('progress-text', `Last run ${outcome} after ${fmtDuration(run.elapsedSec)}.`);
  }

  // While running, show the most recent interval; once finished, the whole-run totals.
  const live = busy && run.interval ? run.interval : run.total;
  setText('t-throughput', live ? fmt(live.throughput, 1) : '–');
  setText('t-avg', live ? fmt(live.avgMs) : '–');
  setText('t-active', run.interval && run.interval.active !== undefined ? fmt(run.interval.active) : '–');
  const total = run.total;
  setText('t-samples', total ? fmt(total.samples) : '–');
  setText('t-errors', total ? fmt(total.errorPct, 2) : '–');
  $('t-errors').classList.toggle('bad', !!total && total.errors > 0);

  const consoleEl = $('console');
  const atBottom = consoleEl.scrollTop + consoleEl.clientHeight >= consoleEl.scrollHeight - 8;
  consoleEl.textContent = (status.log || []).join('\n');
  if (atBottom) consoleEl.scrollTop = consoleEl.scrollHeight;
}

async function pollStatus() {
  try {
    const status = await api('/api/status');
    renderStatus(status);
    const now = Date.now();
    // Refresh history when a run starts or finishes, and periodically while one is running.
    if (status.state !== lastState || (status.state !== 'idle' && now - lastRunsRefresh > 10000)) {
      lastState = status.state;
      loadRuns();
    }
  } catch (e) {
    $('state-pill').className = 'pill';
    $('state-pill').textContent = 'Controller unreachable';
  } finally {
    setTimeout(pollStatus, 2000);
  }
}

function outcomeCell(r) {
  const span = document.createElement('span');
  span.className = 'status';
  if (r.source === 'cli' && r.exitCode === undefined) {
    span.textContent = 'command line';
    span.classList.add('muted');
  } else if (!r.endedAt) {
    span.textContent = '● running';
    span.classList.add('status-ok');
  } else if (String(r.stopRequested) === 'true') {
    span.textContent = '■ stopped';
    span.classList.add('status-warn');
  } else if (String(r.exitCode) === '0') {
    span.textContent = '✓ completed';
    span.classList.add('status-ok');
  } else {
    span.textContent = `✕ exit ${r.exitCode}`;
    span.classList.add('status-bad');
  }
  return span;
}

async function loadRuns() {
  lastRunsRefresh = Date.now();
  let runs;
  try {
    runs = await api('/api/runs');
  } catch (e) {
    return;
  }
  const rows = runs.map((r) => {
    const tr = document.createElement('tr');
    const total = r.total || {
      samples: r['total.samples'], throughput: r['total.throughput'],
      errors: r['total.errors'], errorPct: r['total.errorPct'],
    };
    const cells = [
      [r.id, 'mono'],
      [r.label || '', ''],
      [r.source === 'cli' ? 'CLI' : 'Web', ''],
      [r.profile ? r.profile.replace(/\.properties$/, '') : '–', ''],
      [r.ldapThreads !== undefined ? `${r.ldapThreads} / ${r.kerberosThreads} / ${r.ntlmThreads} / ${r.computerThreads ?? 0}` : '–', 'num'],
      [r.duration ? fmtDuration(r.duration) : '–', 'num'],
      [fmt(total.samples), 'num'],
      [fmt(total.throughput, 1), 'num'],
      [total.errors !== undefined ? `${fmt(total.errors)} (${fmt(total.errorPct, 2)}%)` : '–', 'num'],
    ];
    for (const [text, cls] of cells) {
      const td = document.createElement('td');
      td.textContent = text;
      if (cls) td.className = cls;
      tr.appendChild(td);
    }
    const outcome = document.createElement('td');
    outcome.appendChild(outcomeCell(r));
    tr.appendChild(outcome);
    const report = document.createElement('td');
    if (String(r.hasReport) === 'true') {
      const a = document.createElement('a');
      a.href = `/results/${encodeURIComponent(r.id)}/report/index.html`;
      a.target = '_blank';
      a.rel = 'noopener';
      a.className = 'link';
      a.textContent = 'HTML report ↗';
      report.appendChild(a);
    } else {
      report.textContent = '–';
      report.className = 'muted';
    }
    tr.appendChild(report);
    return tr;
  });
  if (!rows.length) {
    const tr = document.createElement('tr');
    const td = document.createElement('td');
    td.colSpan = 11;
    td.className = 'muted';
    td.textContent = 'No runs yet.';
    tr.appendChild(td);
    rows.push(tr);
  }
  $('runs').replaceChildren(...rows);
  // Finished runs with a report; command-line runs have no end time recorded.
  fillCompareChoices(runs.filter((r) => String(r.hasReport) === 'true' && (r.source === 'cli' || r.endedAt)));
}

function fillCompareChoices(runs) {
  for (const [id, fallback] of [['compare-a', 1], ['compare-b', 0]]) {
    const select = $(id);
    const previous = select.value;
    select.replaceChildren(...runs.map((r) => {
      const opt = document.createElement('option');
      opt.value = r.id;
      opt.textContent = r.label ? `${r.id} · ${r.label}` : r.id;
      return opt;
    }));
    if (runs.some((r) => r.id === previous)) select.value = previous;
    else if (runs[fallback]) select.value = runs[fallback].id;
  }
}

function pct(a, b) {
  if (a === null || a === undefined || b === null || b === undefined || Number(a) === 0) return null;
  return ((Number(b) - Number(a)) / Number(a)) * 100;
}

function deltaCell(delta, colour) {
  const td = document.createElement('td');
  td.className = 'num';
  if (delta === null) {
    td.textContent = '–';
    return td;
  }
  td.textContent = `${delta > 0 ? '+' : ''}${fmt(delta, 1)}%`;
  // Response times: slower by more than 5% is worse, faster by more than 5% is better.
  if (colour && Math.abs(delta) >= 5) td.classList.add(delta > 0 ? 'delta-worse' : 'delta-better');
  return td;
}

function row(cells) {
  const tr = document.createElement('tr');
  for (const cell of cells) {
    if (cell instanceof HTMLElement) {
      tr.appendChild(cell);
      continue;
    }
    const [text, cls] = cell;
    const td = document.createElement('td');
    td.textContent = text;
    if (cls) td.className = cls;
    tr.appendChild(td);
  }
  return tr;
}

const pair = (a, b, digits = 0) => `${fmt(a, digits)} / ${fmt(b, digits)}`;

function fmtMetric(value, unit) {
  if (value === null || value === undefined) return '–';
  if (unit === 'bytes') return `${fmt(value / 1048576, 0)} MB`;
  if (unit === 'percent') return `${fmt(value, 1)}%`;
  const number = fmt(value, value < 10 ? 2 : 0);
  return unit ? `${number} ${unit}` : number;
}

function describeRun(r) {
  const threads = PROTOCOLS.filter(([key]) => Number(r[`${key}Threads`]) > 0).map(([key, name]) => `${name} ${r[`${key}Threads`]}`).join(', ');
  return `${r.id}${r.label ? ` (${r.label})` : ''}${threads ? `: ${threads} threads` : ''}`;
}

$('compare-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const errorEl = $('compare-error');
  errorEl.hidden = true;
  const a = $('compare-a').value;
  const b = $('compare-b').value;
  let data;
  try {
    data = await api(`/api/compare?a=${encodeURIComponent(a)}&b=${encodeURIComponent(b)}`);
  } catch (e) {
    errorEl.textContent = e.message;
    errorEl.hidden = false;
    $('compare-result').hidden = true;
    return;
  }
  setText('compare-meta', `A = ${describeRun(data.a)}. B = ${describeRun(data.b)}.`);
  $('compare-tx').replaceChildren(...data.transactions.map((t) => {
    const a = t.a || {};
    const b = t.b || {};
    return row([
      [t.transaction, t.transaction === 'Total' ? 'strong' : ''],
      [pair(a.samples, b.samples), 'num'],
      [pair(a.throughput, b.throughput, 1), 'num'],
      [pair(a.mean, b.mean, 1), 'num'],
      deltaCell(pct(a.mean, b.mean), true),
      [pair(a.p95, b.p95, 1), 'num'],
      deltaCell(pct(a.p95, b.p95), true),
      [pair(a.p99, b.p99, 1), 'num'],
      [pair(a.errorPct, b.errorPct, 2), 'num'],
    ]);
  }));
  const dcRows = data.dc.map((m) => row([
    [m.dc, 'mono'], [m.metric, ''],
    [fmtMetric(m.a, m.unit), 'num'], [fmtMetric(m.b, m.unit), 'num'],
    deltaCell(pct(m.a, m.b), false),
  ]));
  $('compare-dc').replaceChildren(...dcRows);
  setText('compare-dc-note', data.dcError
    || (dcRows.length ? '' : 'No DC metrics recorded for these runs. On AWS they come from Telegraf on each Windows DC; in the Docker lab from the Samba container.'));
  if (grafanaBase) {
    for (const [id, r] of [['compare-grafana-a', data.a], ['compare-grafana-b', data.b]]) {
      $(id).href = r.firstSampleMs ? `${grafanaBase}?from=${r.firstSampleMs}&to=${r.lastSampleMs}` : grafanaBase;
    }
  }
  $('compare-result').hidden = false;
});

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const errorEl = $('form-error');
  errorEl.hidden = true;
  $('start-btn').disabled = true;
  try {
    await api('/api/runs', { method: 'POST', body: new URLSearchParams(new FormData(form)) });
    lastState = null;
  } catch (e) {
    errorEl.textContent = e.message;
    errorEl.hidden = false;
    $('start-btn').disabled = false;
  }
});

$('stop-btn').addEventListener('click', async () => {
  $('stop-btn').disabled = true;
  try {
    await api('/api/runs/stop', { method: 'POST' });
  } catch (e) {
    $('form-error').textContent = e.message;
    $('form-error').hidden = false;
  }
});

$('refresh-runs').addEventListener('click', loadRuns);
form.addEventListener('input', updateLoadSummary);

(async () => {
  await Promise.all([loadConfig().catch(() => {}), loadProfiles().catch(() => {})]);
  updateLoadSummary();
  pollStatus();
})();
