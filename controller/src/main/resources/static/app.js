'use strict';

const $ = (id) => document.getElementById(id);
const form = $('run-form');
let lastState = null;
let lastRunsRefresh = 0;

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
  $('grafana-link').href = `${base}?from=now-15m&to=now`;
  $('grafana').src = `${base}?from=now-15m&to=now&refresh=5s&kiosk&theme=${dark ? 'dark' : 'light'}`;
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
    setText('progress-text', `${fmtDuration(run.elapsedSec)} of ${fmtDuration(run.duration)} · ${run.profile.replace(/\.properties$/, '')} · LDAP ${run.ldapThreads} / Kerberos ${run.kerberosThreads} / NTLM ${run.ntlmThreads} threads`);
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
      [r.source === 'cli' ? 'CLI' : 'Web', ''],
      [r.profile ? r.profile.replace(/\.properties$/, '') : '–', ''],
      [r.ldapThreads !== undefined ? `${r.ldapThreads} / ${r.kerberosThreads} / ${r.ntlmThreads}` : '–', 'num'],
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
    td.colSpan = 10;
    td.className = 'muted';
    td.textContent = 'No runs yet.';
    tr.appendChild(td);
    rows.push(tr);
  }
  $('runs').replaceChildren(...rows);
}

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

(async () => {
  await Promise.all([loadConfig().catch(() => {}), loadProfiles().catch(() => {})]);
  pollStatus();
})();
