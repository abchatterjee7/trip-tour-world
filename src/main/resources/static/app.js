(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const LABELS = {
    input_guardrail: '🛡️ Input guardrail checked the request',
    guardrail_blocked: '⛔ Request blocked',
    supervisor: '🧭 Supervisor planned the workflow',
    flight_agent: '✈️ Flight agent', hotel_agent: '🏨 Hotel agent', weather_agent: '🌦️ Weather agent',
    budget_agent: '💰 Budget agent', itinerary_agent: '🗓️ Itinerary agent drafted the plan',
    human_approval: '🙋 Human review recorded', final_agent: '📝 Final agent wrote the plan'
  };
  const EXAMPLES = [
    ['Goa from Hyderabad', 'Plan a 5-day Goa trip from Hyderabad with flights, stays and sightseeing under ₹40,000'],
    ['Dubai flights', 'Cheapest flights from Delhi to Dubai next month and where to stay'],
    ['Switzerland', 'Plan a 7-day Switzerland trip from India with affordable stays and sightseeing'],
    ['Bali honeymoon', 'Plan a 6-day Bali honeymoon from Bengaluru within ₹1.5 lakh']
  ];
  let tripId = sessionStorage.getItem('ttw_trip') || null;
  let timer = null;
  let current = null;

  // ---------------------------------------------------------------- helpers
  function headers() {
    const h = { 'Content-Type': 'application/json' };
    const k = sessionStorage.getItem('ttw_key');
    if (k) h['X-API-Key'] = k;
    return h;
  }
  async function api(path, opts = {}) {
    const res = await fetch('/api' + path, { ...opts, headers: { ...headers(), ...(opts.headers || {}) } });
    let body = null;
    try { body = await res.json(); } catch (_) { /* empty */ }
    if (!res.ok) throw new Error((body && body.error) || ('Request failed (' + res.status + ')'));
    return body;
  }
  function showError(msg) { const e = $('error'); e.textContent = msg; e.classList.remove('hidden'); }
  function clearError() { $('error').classList.add('hidden'); }
  const show = (id, on) => $(id).classList.toggle('hidden', !on);

  // Minimal, XSS-safe markdown (everything is HTML-escaped first).
  function esc(s) { return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
  function inline(s) {
    return esc(s)
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
      .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
  }
  function md(src) {
    const lines = (src || '').replace(/\r/g, '').split('\n');
    const out = []; let list = null; let para = [];
    const flushPara = () => { if (para.length) { out.push('<p>' + inline(para.join(' ')) + '</p>'); para = []; } };
    const closeList = () => { if (list) { out.push('</' + list + '>'); list = null; } };
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i]; const t = line.trim();
      let m;
      if (!t) { flushPara(); closeList(); continue; }
      if ((m = t.match(/^(#{1,4})\s+(.*)$/))) { flushPara(); closeList(); const n = Math.min(4, m[1].length + 1); out.push('<h' + n + '>' + inline(m[2]) + '</h' + n + '>'); continue; }
      if (/^(-{3,}|\*{3,})$/.test(t)) { flushPara(); closeList(); out.push('<hr>'); continue; }
      if (t.startsWith('|') && i + 1 < lines.length && /^\s*\|?[\s:|-]+\|[\s:|-]*$/.test(lines[i + 1])) {
        flushPara(); closeList();
        const cells = (r) => r.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
        let html = '<table><thead><tr>' + cells(t).map((c) => '<th>' + inline(c) + '</th>').join('') + '</tr></thead><tbody>';
        i += 2;
        while (i < lines.length && lines[i].trim().startsWith('|')) { html += '<tr>' + cells(lines[i]).map((c) => '<td>' + inline(c) + '</td>').join('') + '</tr>'; i++; }
        i--; out.push(html + '</tbody></table>'); continue;
      }
      if ((m = t.match(/^[-*•]\s+(.*)$/))) { flushPara(); if (list !== 'ul') { closeList(); out.push('<ul>'); list = 'ul'; } out.push('<li>' + inline(m[1]) + '</li>'); continue; }
      if ((m = t.match(/^\d+[.)]\s+(.*)$/))) { flushPara(); if (list !== 'ol') { closeList(); out.push('<ol>'); list = 'ol'; } out.push('<li>' + inline(m[1]) + '</li>'); continue; }
      closeList();
      const em = t.match(/^_(.+)_$/);
      if (em) { flushPara(); out.push('<p><em>' + esc(em[1]) + '</em></p>'); continue; }
      para.push(t);
    }
    flushPara(); closeList();
    return out.join('\n');
  }

  // ---------------------------------------------------------------- rendering
  function render(t) {
    current = t;
    show('run', true);
    const pill = $('statusPill'); pill.textContent = t.status.replace('_', ' '); pill.className = 'pill ' + t.status;
    const steps = $('steps'); steps.textContent = '';
    t.progress.forEach((n) => {
      const li = document.createElement('li');
      const d = document.createElement('span'); d.className = 'dot';
      li.append(d, document.createTextNode(LABELS[n] || n));
      steps.append(li);
    });
    if (t.status === 'RUNNING') {
      const li = document.createElement('li');
      const d = document.createElement('span'); d.className = 'dot pulse';
      li.append(d, document.createTextNode('Working…'));
      steps.append(li);
    }
    $('reasoning').textContent = t.supervisorReasoning ? 'Supervisor: ' + t.supervisorReasoning : '';
    const chips = $('agentChips'); chips.textContent = '';
    t.selectedAgents.forEach((a) => { const c = document.createElement('span'); c.className = 'chip static'; c.textContent = a.replace('_', ' '); chips.append(c); });

    show('draft', t.status === 'AWAITING_APPROVAL');
    if (t.status === 'AWAITING_APPROVAL') {
      $('draftBody').innerHTML = md(t.draftItinerary);
      $('approveBtn').disabled = false; $('reviseBtn').disabled = false;
    }
    const done = t.status === 'COMPLETED' || t.status === 'BLOCKED';
    show('final', done);
    if (done) {
      $('finalBody').innerHTML = md(t.finalResponse);
      buildRaw(t);
    }
    if (t.status === 'FAILED') showError(t.error || 'Trip planning failed.');
  }
  function buildRaw(t) {
    const parts = { Flights: t.flightResults, Hotels: t.hotelResults, Weather: t.weatherResults, Budget: t.budgetResults,
      Meta: JSON.stringify({ id: t.id, constraints: t.constraints, llmCalls: t.llmCalls, revisions: t.revisionCount, events: t.events }, null, 2) };
    const tabs = $('rawTabs'); tabs.textContent = '';
    const pick = (k) => { $('rawBody').textContent = parts[k] || '(not used for this request)'; };
    Object.keys(parts).forEach((k, i) => {
      const b = document.createElement('button'); b.className = 'ghost small'; b.textContent = k;
      b.addEventListener('click', () => pick(k)); tabs.append(b); if (i === 0) pick(k);
    });
  }

  // ---------------------------------------------------------------- polling & actions
  function stopPolling() { if (timer) { clearInterval(timer); timer = null; } }
  async function poll() {
    if (!tripId) return;
    try {
      const t = await api('/trips/' + encodeURIComponent(tripId));
      render(t);
      if (t.status !== 'RUNNING') { stopPolling(); setBusy(false); loadHistory(); }
    } catch (e) { stopPolling(); setBusy(false); showError(e.message); }
  }
  function startPolling() { stopPolling(); poll(); timer = setInterval(poll, 2000); }
  function setBusy(b) { $('planBtn').disabled = b; $('approveBtn').disabled = b; $('reviseBtn').disabled = b; }

  async function plan() {
    clearError();
    const message = $('prompt').value.trim();
    if (!message) { showError('Tell me where you would like to go.'); return; }
    show('final', false); show('draft', false);
    setBusy(true);
    try {
      const t = await api('/trips', { method: 'POST', body: JSON.stringify({ message }) });
      tripId = t.id; sessionStorage.setItem('ttw_trip', tripId);
      render(t); startPolling();
    } catch (e) { setBusy(false); showError(e.message); }
  }
  async function review(approved) {
    clearError();
    const feedback = $('feedback').value.trim();
    if (!approved && !feedback) { showError('Please describe the changes you want.'); return; }
    setBusy(true);
    try {
      const t = await api('/trips/' + encodeURIComponent(tripId) + '/review', { method: 'POST', body: JSON.stringify({ approved, feedback }) });
      $('feedback').value = ''; show('draft', false); render(t); startPolling();
    } catch (e) { setBusy(false); showError(e.message); }
  }
  async function loadHistory() {
    try {
      const list = await api('/trips?limit=8');
      const ul = $('history'); ul.textContent = '';
      if (!list.length) { const li = document.createElement('li'); li.textContent = 'No trips yet.'; ul.append(li); return; }
      list.forEach((t) => {
        const li = document.createElement('li');
        const q = document.createElement('span'); q.className = 'q'; q.textContent = t.query;
        const p = document.createElement('span'); p.className = 'pill ' + t.status; p.textContent = t.status.replace('_', ' ');
        li.append(q, p);
        li.addEventListener('click', () => { tripId = t.id; sessionStorage.setItem('ttw_trip', tripId); clearError(); startPolling(); window.scrollTo({ top: 0, behavior: 'smooth' }); });
        ul.append(li);
      });
    } catch (_) { /* history is optional */ }
  }
  async function loadHealth() {
    const el = $('health');
    try {
      const res = await fetch('/api/health'); const h = await res.json();
      el.textContent = (h.llm === 'not configured' ? '⚠ LLM not configured' : '● ' + h.llm) + ' · db: ' + h.database + (h.tracing ? ' · traced' : '');
      el.className = 'health ' + (h.llm === 'not configured' ? 'bad' : 'ok');
      show('keyRow', !!h.apiKeyRequired);
    } catch (_) { el.textContent = 'backend unreachable'; el.className = 'health bad'; }
  }

  // ---------------------------------------------------------------- init
  EXAMPLES.forEach(([label, text]) => {
    const b = document.createElement('button'); b.className = 'chip'; b.textContent = label;
    b.addEventListener('click', () => { $('prompt').value = text; $('prompt').focus(); });
    $('examples').append(b);
  });
  $('apiKey').value = sessionStorage.getItem('ttw_key') || '';
  $('apiKey').addEventListener('input', (e) => sessionStorage.setItem('ttw_key', e.target.value.trim()));
  $('planBtn').addEventListener('click', plan);
  $('approveBtn').addEventListener('click', () => review(true));
  $('reviseBtn').addEventListener('click', () => review(false));
  $('refreshBtn').addEventListener('click', loadHistory);
  loadHealth().then(() => { loadHistory(); if (tripId) startPolling(); });
})();
