/* Job Hunt Engine — bundled UI.
 *
 * Vanilla JS, no build step, no framework. Everything goes through the same
 * /api endpoints an external dashboard would use. See docs/DECISIONS.md #3.
 *
 * The design goal is the daily loop: paste a posting, decide, track it, and
 * later move it along. That path is one paste, one click to add, one click to
 * track, one click per status change -- no tab switching and no modal prompts.
 * Everything rarer lives behind "Details".
 */

'use strict';

const TERMINAL = new Set(['offer', 'rejected', 'ghosted']);

/** Verb for each move, so buttons read as actions rather than state names. */
const MOVE_LABEL = {
  applied: 'Mark applied',
  screening: 'Move to screening',
  interview: 'Move to interview',
  final: 'Move to final round',
  offer: 'Got an offer',
  rejected: 'Rejected',
  ghosted: 'No reply',
};

const state = { jobs: [], filter: 'all', search: '', sort: 'score', touched: new Set() };

// ── plumbing ──────────────────────────────────────────────────────────

class ProblemError extends Error {
  constructor(status, problem) {
    super(problem.detail || problem.title || `HTTP ${status}`);
    this.status = status;
    this.title = problem.title;
    this.errors = problem.errors || null;
  }
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: options.body ? { 'Content-Type': 'application/json' } : {},
    ...options,
  });
  if (response.status === 204) return null;
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (!response.ok) throw new ProblemError(response.status, payload || {});
  return payload;
}

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

const esc = (value) => String(value ?? '').replace(/[&<>"']/g,
  (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

let toastTimer;
function toast(message, isBad = false) {
  const el = $('#toast');
  el.textContent = message;
  el.className = 'toast show' + (isBad ? ' bad' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.className = 'toast'; }, 3000);
}

function fail(error) {
  console.error(error);
  toast(error instanceof ProblemError ? error.message : 'the engine is not reachable', true);
  if (!(error instanceof ProblemError)) $('#health-dot').classList.add('down');
}

function problemHtml(error) {
  if (!(error instanceof ProblemError)) {
    return `<div class="problem">Could not reach the engine. Is it still running?</div>`;
  }
  const fields = error.errors
    ? `<ul>${Object.entries(error.errors).map(([k, v]) => `<li>${esc(k)} — ${esc(v)}</li>`).join('')}</ul>`
    : '';
  return `<div class="problem">${esc(error.message)}${fields}</div>`;
}

const when = (iso) => (iso ? new Date(iso).toLocaleDateString(undefined,
  { month: 'short', day: 'numeric', year: '2-digit' }) : '');

const whenExact = (iso) => (iso ? new Date(iso).toLocaleString(undefined,
  { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '');

const linesOf = (text) => (text || '').split('\n').map((s) => s.trim()).filter(Boolean);

/**
 * Run something slow with visible feedback.
 *
 * A sweep takes twenty seconds and an auto-apply run can take minutes, and
 * until now both looked identical to a dead button. Anything that talks to
 * somebody else's server goes through here.
 */
async function busy(element, work) {
  const target = typeof element === 'string' ? $(element) : element;
  target?.classList.add('is-busy');
  const wasDisabled = target?.disabled;
  if (target) target.disabled = true;
  try {
    return await work();
  } finally {
    target?.classList.remove('is-busy');
    if (target) target.disabled = wasDisabled ?? false;
  }
}

/** Placeholder rows while a list loads, so the page does not jump. */
function skeleton(container, rows = 3) {
  const target = typeof container === 'string' ? $(container) : container;
  if (target) {
    target.innerHTML = Array.from({ length: rows },
      () => '<div class="skeleton-row"></div>').join('');
  }
}

// ── reading a pasted posting ──────────────────────────────────────────

/**
 * Best-effort company / title / location from pasted text, to save typing them.
 *
 * This is a UI convenience and nothing more. It runs in the browser, its output
 * lands in three editable boxes the human looks at before submitting, and
 * anything it gets wrong is corrected in place. The server-side extractor is
 * unchanged and still refuses to guess -- see CLAUDE.md rule 10. The difference
 * that makes it acceptable here is that a person confirms every value before it
 * is written.
 */
function guessFromPaste(text) {
  const lines = text.split('\n').map((l) => l.trim()).filter(Boolean);
  const guess = { company: '', title: '', location: '' };
  if (!lines.length) return guess;

  // Labelled lines are not guesses at all -- the server reads these too.
  const labelled = (names) => {
    const re = new RegExp(`^(?:${names})\\s*[:\\-]\\s*(.+)$`, 'i');
    for (const line of lines) {
      const match = line.match(re);
      if (match) return match[1].trim();
    }
    return '';
  };
  guess.company = labelled('company|employer|organisation|organization');
  guess.title = labelled('(?:job\\s+)?title|role|position');
  guess.location = labelled('location|based\\s+in|city');
  if (guess.company && guess.title) return guess;

  const ROLE = /(engineer|developer|architect|manager|analyst|designer|scientist|consultant|administrator|specialist|lead|director|intern|sre|devops|programmer)/i;
  const looksLikeTitle = (line) => line.length < 90 && ROLE.test(line);
  const looksLikeLocation = (line) =>
    line.length < 70 && !ROLE.test(line) &&
    (/,/.test(line) || /\b(remote|hybrid|on-?site|bengaluru|bangalore|pune|mumbai|delhi|hyderabad|chennai|noida|gurugram|gurgaon|kolkata)\b/i.test(line));

  // The shape most boards paste as: a title line, then "Company · Location · …".
  const middot = lines.slice(0, 3).find((l) => l.includes('·'));
  if (middot) {
    const parts = middot.split('·').map((p) => p.trim()).filter(Boolean);
    if (!guess.company && parts[0] && !looksLikeTitle(parts[0])) guess.company = parts[0];
    if (!guess.location && parts[1] && looksLikeLocation(parts[1])) guess.location = parts[1];
    if (!guess.title) {
      const before = lines[lines.indexOf(middot) - 1];
      if (before && looksLikeTitle(before)) guess.title = before;
      else if (parts[0] && looksLikeTitle(parts[0])) guess.title = parts[0];
    }
  }

  // Otherwise fall back to the first few lines: whichever reads as a role is
  // the title, and the line above it is usually the company.
  if (!guess.title) {
    const index = lines.slice(0, 4).findIndex(looksLikeTitle);
    if (index >= 0) {
      guess.title = lines[index].replace(/\s*[-–|]\s*.*$/, '').trim();
      if (!guess.company && index > 0) guess.company = lines[index - 1];
      if (!guess.company && lines[index + 1] && !looksLikeLocation(lines[index + 1])) {
        guess.company = lines[index + 1];
      }
      if (!guess.location) {
        guess.location = lines.slice(index + 1, index + 4).find(looksLikeLocation) || '';
      }
    }
  }

  // "Senior Backend Engineer at Acme"
  const at = (guess.title || lines[0]).match(/^(.*?)\s+at\s+(.+)$/i);
  if (at && looksLikeTitle(at[1])) {
    guess.title = at[1].trim();
    if (!guess.company) guess.company = at[2].trim();
  }

  const tidy = (v) => v.replace(/^[\s\-–|•]+|[\s\-–|•]+$/g, '').slice(0, 150);
  return { company: tidy(guess.company), title: tidy(guess.title), location: tidy(guess.location) };
}

function applyGuess(text) {
  const guess = guessFromPaste(text);
  for (const field of ['company', 'title', 'location']) {
    const input = $(`#d-${field}`);
    // Never overwrite something typed by hand.
    if (state.touched.has(field)) continue;
    input.value = guess[field];
    $(`#derived em[data-for="${field}"]`).textContent = guess[field] ? 'auto — check it' : '';
  }
}

const pasteBox = $('#paste-text');

function onPasteInput() {
  const text = pasteBox.value;
  $('#derived').hidden = text.trim().length < 40;
  if (text.trim().length >= 40) applyGuess(text);
  pasteBox.rows = Math.min(14, Math.max(3, text.split('\n').length + 1));
}

pasteBox.addEventListener('input', onPasteInput);
pasteBox.addEventListener('paste', () => setTimeout(onPasteInput, 0));

['company', 'title', 'location'].forEach((field) => {
  $(`#d-${field}`).addEventListener('input', () => {
    state.touched.add(field);
    $(`#derived em[data-for="${field}"]`).textContent = '';
  });
});

pasteBox.addEventListener('keydown', (event) => {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault();
    $('#paste-form').requestSubmit();
  }
});

function resetPaste() {
  $('#paste-form').reset();
  state.touched.clear();
  $('#derived').hidden = true;
  $('#paste-problem').innerHTML = '';
  pasteBox.rows = 3;
}

$('#paste-clear').addEventListener('click', resetPaste);

$('#paste-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const body = {
    rawText: pasteBox.value.trim() || null,
    company: $('#d-company').value.trim() || null,
    title: $('#d-title').value.trim() || null,
    location: $('#d-location').value.trim() || null,
    url: $('#d-url').value.trim() || null,
  };
  const button = $('#add-btn');
  button.disabled = true;

  try {
    const result = await api('/api/jobs/ingest', { method: 'POST', body: JSON.stringify(body) });
    $('#paste-problem').innerHTML = '';
    resetPaste();
    await refresh();

    // Three outcomes, said three different ways. Re-pasting a posting is
    // routine: it is neither an error nor a new find.
    if (result.outcome === 'created') toast(`Added ${result.job.title}`);
    else if (result.outcome === 'updated') toast(`${result.job.title} — updated since you last saw it`);
    else toast(`Already had ${result.job.title}`);
  } catch (error) {
    $('#paste-problem').innerHTML = problemHtml(error);
  } finally {
    button.disabled = false;
  }
});

// ── tabs ──────────────────────────────────────────────────────────────

$("#tabs").addEventListener("click", (event) => {
  const tab = event.target.closest(".tab");
  if (!tab) return;
  show(tab.dataset.view);
});

/** One place that knows what each tab needs loaded. */
function show(view) {
  // Remembered in the hash so a reload lands where you were, not on Home.
  if (location.hash !== "#" + view) history.replaceState({}, "", "#" + view);
  $$(".tab").forEach((t) => t.classList.toggle("is-active", t.dataset.view === view));
  $$(".view").forEach((v) => v.classList.toggle("is-active", v.id === 'view-' + view));
  const load = {
    home: loadHome,
    jobs: loadJobs,
    pipeline: loadPipeline,
    me: async () => { await Promise.all([loadApplicant(), loadProfileState(), loadPreferences()]); },
    automation: loadAutomation,
  }[view];
  if (load) load().catch(fail);
}

// ── the job list ──────────────────────────────────────────────────────

const statusOf = (job) => (job.application ? job.application.status : null);

function chipsFor(job) {
  const chips = [];
  if (job.location) chips.push(job.location);
  if (job.remoteType) chips.push(job.remoteType);
  if (job.salaryMin) {
    const unit = job.salaryCurrency === 'INR' ? 100000 : 1000;
    const suffix = job.salaryCurrency === 'INR' ? 'L' : 'k';
    const low = Math.round(job.salaryMin / unit);
    chips.push(job.salaryMax ? `${low}–${Math.round(job.salaryMax / unit)}${suffix}` : `${low}${suffix}+`);
  }
  if (job.expMin != null) chips.push(`${job.expMin}${job.expMax != null ? `–${job.expMax}` : '+'} yrs`);
  return chips.map((c) => `<span class="chip">${esc(c)}</span>`).join('');
}

function techFor(job, limit = 6) {
  const tech = job.technologies || [];
  const shown = tech.slice(0, limit).map((t) => `<span class="chip tech">${esc(t)}</span>`).join('');
  return shown + (tech.length > limit ? `<span class="chip more">+${tech.length - limit}</span>` : '');
}

function primaryAction(job) {
  if (!job.application) {
    // Opening the posting and starting to track it are one intent, so they are
    // one click. The apply form itself is on their site and always will be --
    // nothing here submits an application. See docs/DECISIONS.md #9.
    return job.url
      ? `<button class="primary sm" data-act="open">Open &amp; track</button>`
      : `<button class="primary sm" data-act="track">Track</button>`;
  }
  const next = job.application.allowedTransitions?.[0];
  if (!next) return '';
  return `<button class="primary sm" data-act="move" data-to="${esc(next)}">${esc(MOVE_LABEL[next] || next)}</button>`;
}

const scoreOf = (job) => (job.match ? (job.match.aiScore ?? job.match.heuristicScore) : -1);

/** The queue: worth applying to, and not already in the pipeline. */
const isReady = (job) => job.match?.verdict === 'apply' && !job.application;

function matchesFilter(job) {
  switch (state.filter) {
    case 'all': return true;
    // Score bands, so "show me only the strong ones" is one click. 90+ is a
    // near-complete match; 60+ is the widest band still worth writing for.
    case 'band90': return scoreOf(job) >= 90;
    case 'band75': return scoreOf(job) >= 75;
    case 'band60': return scoreOf(job) >= 60;
    case 'ready': return isReady(job);
    case 'review': return job.match?.verdict === 'review' && !job.application;
    case 'untracked': return !job.application;
    default: return statusOf(job) === state.filter;
  }
}

function visibleJobs() {
  const jobs = state.jobs.filter(matchesFilter);
  // Best-match-first is the default because that is what a queue is for.
  // Unscored jobs sort to the bottom rather than the top: -1 is "not judged",
  // not "judged badly", but it should not outrank something already ranked.
  return state.sort === 'score'
    ? [...jobs].sort((a, b) => scoreOf(b) - scoreOf(a)
        || new Date(b.discoveredAt) - new Date(a.discoveredAt))
    : jobs;
}

function renderPills() {
  const counts = {
    all: state.jobs.length,
    band90: state.jobs.filter((j) => scoreOf(j) >= 90).length,
    band75: state.jobs.filter((j) => scoreOf(j) >= 75).length,
    band60: state.jobs.filter((j) => scoreOf(j) >= 60).length,
    ready: state.jobs.filter(isReady).length,
    review: state.jobs.filter((j) => j.match?.verdict === 'review' && !j.application).length,
    untracked: state.jobs.filter((j) => !j.application).length,
  };
  for (const job of state.jobs) {
    const status = statusOf(job);
    if (status) counts[status] = (counts[status] || 0) + 1;
  }

  // Only stages with something in them get a pill. Eight dead filters is noise.
  const order = ['all', 'band90', 'band75', 'band60', 'ready', 'review', 'untracked',
    'saved', 'applied', 'screening', 'interview', 'final', 'offer', 'rejected', 'ghosted'];
  const label = {
    all: 'All', band90: '90+', band75: '75+', band60: '60+',
    ready: 'Worth applying', review: 'Worth a look', untracked: 'Not tracked',
  };

  $('#pills').innerHTML = order
    .filter((key) => key === 'all' || counts[key])
    .map((key) => `<button class="pill ${key === 'ready' ? 'accent' : ''} ${state.filter === key ? 'is-on' : ''}"
        data-filter="${key}">${esc(label[key] || key)} <b>${counts[key] || 0}</b></button>`)
    .join('');
}

$('#sort').addEventListener('change', (event) => {
  state.sort = event.target.value;
  renderJobs();
});

async function loadJobs() {
  if (!state.jobs.length) skeleton("#jobs-list", 4);
  const params = new URLSearchParams();
  if (state.search) params.set('company', state.search);
  // The status sub-filter is applied in the browser because /api/jobs filters
  // on verdict and score, not pipeline status. At this volume that is instant.
  state.jobs = await api(`/api/jobs?${params}`);
  renderPills();
  renderJobs();
}

/**
 * How many rows to draw. A sweep brings in a hundred-odd jobs and rendering all
 * of them produced a fifteen-thousand-pixel page that nobody scrolls to the end
 * of -- the tail is the low scores anyway, and the whole point of ranking is
 * that the top is where you look.
 */
const PAGE = 25;

function renderJobs() {
  const jobs = visibleJobs();
  const list = $('#jobs-list');

  if (!jobs.length) {
    list.innerHTML = state.jobs.length
      ? `<div class="empty">Nothing under this filter.</div>`
      : `<div class="empty"><strong>No jobs yet.</strong><br>
           Fetch some on the Job Applier tab, or add one by hand above.</div>`;
    return;
  }

  const shown = jobs.slice(0, state.shown || PAGE);
  const remaining = jobs.length - shown.length;

  list.innerHTML = shown.map((job) => {
    const status = statusOf(job);
    return `
    <article class="job" data-job="${job.id}">
      <div class="job-main">
        <div class="job-head">
          <span class="job-title">${esc(job.title)}</span>
          ${status ? `<span class="badge ${TERMINAL.has(status) ? 'terminal' : 'status'}">${esc(status)}</span>` : ''}
          ${job.match ? `<span class="badge ${esc(job.match.verdict || 'review')}">${esc(job.match.verdict || 'scored')} ${job.match.aiScore ?? job.match.heuristicScore}</span>` : ''}
        </div>
        <div class="job-sub">${esc(job.company)} <span class="sep">·</span> ${esc(when(job.discoveredAt))}</div>
        <div class="job-chips">${chipsFor(job)}${techFor(job)}</div>
      </div>
      <div class="job-actions">
        ${primaryAction(job)}
        <button class="sm ghost" data-act="detail">Details</button>
      </div>
    </article>`;
  }).join('') + (remaining > 0
    ? `<button class="show-more" id="show-more">Show ${remaining} more</button>`
    : '');
}

$('#jobs-list').addEventListener('click', (event) => {
  if (event.target.id !== 'show-more') return;
  state.shown = (state.shown || PAGE) + PAGE;
  renderJobs();
});

$('#pills').addEventListener('click', (event) => {
  const pill = event.target.closest('.pill');
  if (!pill) return;
  state.filter = pill.dataset.filter;
  state.shown = PAGE;  // a new filter starts at the top, not 100 rows down
  renderPills();
  renderJobs();
});

let searchTimer;
$('#search').addEventListener('input', (event) => {
  state.search = event.target.value.trim();
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => loadJobs().catch(fail), 220);
});

$('#jobs-list').addEventListener('click', async (event) => {
  const button = event.target.closest('button[data-act]');
  if (!button) return;
  const card = button.closest('[data-job]');
  const jobId = Number(card.dataset.job);
  const job = state.jobs.find((j) => j.id === jobId);
  button.disabled = true;

  try {
    if (button.dataset.act === 'open') {
      // Opened before the await so the browser still counts it as user-initiated
      // and does not treat it as a popup.
      window.open(job.url, '_blank', 'noopener');
      await api('/api/applications', { method: 'POST', body: JSON.stringify({ jobId }) });
      toast('Tracking — mark it applied once you have sent it');
      await refresh();
    } else if (button.dataset.act === 'track') {
      await api('/api/applications', { method: 'POST', body: JSON.stringify({ jobId }) });
      toast(`Tracking ${job.title}`);
      await refresh();
    } else if (button.dataset.act === 'move') {
      // One click, no note prompt. Notes are added from Details when wanted.
      await api(`/api/applications/${job.application.id}/transitions`, {
        method: 'POST',
        body: JSON.stringify({ status: button.dataset.to }),
      });
      toast(`${job.title} → ${button.dataset.to}`);
      await refresh();
    } else if (button.dataset.act === 'detail') {
      await openDetail(jobId);
    }
  } catch (error) {
    fail(error);
  } finally {
    button.disabled = false;
  }
});

/**
 * Why the job scored what it did.
 *
 * The bars matter less than the "not stated" marks. A factor the posting never
 * mentioned scores half by design, and that is a different thing from scoring
 * badly -- without saying so, a 70 built on four unknowns looks identical to a
 * 70 that was actually measured.
 */
function scoreSheet(match) {
  if (!match) {
    return `<div class="sheet-block"><div class="block-label">Match</div>
      <span class="hint">Not scored. Add a profile at <code>.work/profile.json</code>
      and save your preferences to score the backlog.</span></div>`;
  }

  const factors = match.breakdown || {};
  // Fixed order: jsonb does not preserve key order, and "stack first" reads far
  // better than whatever Postgres hands back.
  const ORDER = ['stack', 'experience', 'remote', 'location', 'salary'];
  const names = ORDER.filter((n) => factors[n]).concat(
    Object.keys(factors).filter((n) => !ORDER.includes(n)));

  const rows = names.map((name) => {
    const f = factors[name];
    const pct = f.max ? Math.round((f.earned / f.max) * 100) : 0;
    return `<div class="factor">
        <span class="factor-name">${esc(name)}</span>
        <span class="bar"><i style="width:${pct}%"></i></span>
        <span class="factor-num">${f.earned}/${f.max}</span>
        ${f.measured ? '' : '<span class="chip more">not stated</span>'}
      </div>`;
  }).join('');

  // A posting that omits salary and experience cannot reach the high bands,
  // however well the stack fits. Saying so stops a 79 reading as "79% match"
  // when it means "strong on everything the posting actually told us".
  const unknown = names.filter((n) => !factors[n].measured);
  const ceiling = unknown.length
    ? `<p class="hint">${unknown.length} factor${unknown.length > 1 ? 's were' : ' was'} not
       stated in the posting (${unknown.map(esc).join(', ')}), which caps the score.
       This is confidence, not a percentage fit.</p>`
    : '';

  return `<div class="sheet-block">
      <div class="block-label">Match
        <span class="badge ${esc(match.verdict || 'review')}">${esc(match.verdict || '')} ${match.aiScore ?? match.heuristicScore}</span>
      </div>
      ${rows || '<span class="hint">No breakdown recorded — re-score to populate it.</span>'}
      ${ceiling}
      ${match.reasoning ? `<p class="hint">${esc(match.reasoning)}</p>` : ''}
      ${match.missingSkills?.length
        ? `<div class="job-chips"><span class="hint">Gaps:</span>
             ${match.missingSkills.map((s) => `<span class="chip tech">${esc(s)}</span>`).join('')}</div>`
        : ''}
    </div>`;
}

// ── the detail sheet: everything that is not the daily path ───────────

async function openDetail(jobId) {
  const job = await api(`/api/jobs/${jobId}`);
  const application = job.application
    ? await api(`/api/applications/${job.application.id}`)
    : null;
  const [events, contacts] = await Promise.all([
    application ? api(`/api/applications/${application.id}/events`) : Promise.resolve([]),
    api(`/api/contacts?jobId=${jobId}`),
  ]);

  $('#detail-body').innerHTML = `
    <div class="sheet-head">
      <div>
        <h3>${esc(job.title)}</h3>
        <div class="job-sub">${esc(job.company)}${job.location ? ` <span class="sep">·</span> ${esc(job.location)}` : ''}</div>
      </div>
      <button class="sm ghost" data-act="close">Close</button>
    </div>

    <div class="job-chips">${chipsFor(job)}${techFor(job, 30)}</div>

    ${scoreSheet(job.match)}

    ${application ? `
      <div class="sheet-block">
        <div class="block-label">Pipeline <span class="badge ${TERMINAL.has(application.status) ? 'terminal' : 'status'}">${esc(application.status)}</span></div>
        <div class="actions">
          ${application.allowedTransitions.length
            ? application.allowedTransitions.map((s) =>
                `<button class="sm" data-act="move" data-to="${esc(s)}">${esc(MOVE_LABEL[s] || s)}</button>`).join('')
            : `<span class="hint">${esc(application.status)} is the end of the road.</span>`}
        </div>
        <div class="note-row">
          <input id="note-input" placeholder="Add a note…" autocomplete="off">
          <button class="sm" data-act="note">Save note</button>
        </div>
        <div class="events">
          ${events.map((e) => `<div class="event"><time>${esc(whenExact(e.occurredAt))}</time>
             <span class="etype">${esc(e.type.replace('_', ' '))}</span>
             <span>${esc(e.note || '')}</span></div>`).join('')}
        </div>
      </div>`
    : `<div class="sheet-block">
         <button class="primary sm" data-act="track">Track this job</button>
         <span class="hint">Adds it to your pipeline and starts recording history.</span>
       </div>`}

    <div class="sheet-block">
      <div class="block-label">People at ${esc(job.company)}</div>
      ${contacts.length
        ? contacts.map((c) => `<div class="mini">${esc(c.name)}${c.title ? ` — ${esc(c.title)}` : ''}
            <span class="badge ${c.outreachStatus === 'replied' ? 'apply' : 'status'}">${esc(c.outreachStatus)}</span></div>`).join('')
        : `<span class="hint">Nobody yet.</span>`}
      <div class="note-row">
        <input id="contact-quick" placeholder="Add a name…" autocomplete="off">
        <button class="sm" data-act="add-contact">Add</button>
      </div>
    </div>

    <details class="sheet-block">
      <summary class="block-label">The posting</summary>
      <pre>${esc(job.description || '(no description captured)')}</pre>
      <dl class="kv">
        <dt>dedupe key</dt><dd>${esc(job.dedupeKey)}</dd>
        <dt>source</dt><dd>${esc(job.source)}</dd>
      </dl>
      ${job.url ? `<a class="chip" href="${esc(job.url)}" target="_blank" rel="noopener">Open original ↗</a>` : ''}
    </details>

    <div class="sheet-foot">
      <button class="sm danger" data-act="delete" data-armed="0">Delete this job</button>
    </div>`;

  $('#detail').dataset.job = jobId;
  if (!$('#detail').open) $('#detail').showModal();
}

$('#detail').addEventListener('click', async (event) => {
  const button = event.target.closest('button[data-act]');
  if (!button) return;
  const dialog = $('#detail');
  const jobId = Number(dialog.dataset.job);
  const job = state.jobs.find((j) => j.id === jobId);
  const applicationId = job?.application?.id;

  try {
    switch (button.dataset.act) {
      case 'close':
        dialog.close();
        return;

      case 'track':
        await api('/api/applications', { method: 'POST', body: JSON.stringify({ jobId }) });
        toast('Tracking');
        await refresh();
        await openDetail(jobId);
        return;

      case 'move':
        await api(`/api/applications/${applicationId}/transitions`, {
          method: 'POST',
          body: JSON.stringify({ status: button.dataset.to }),
        });
        toast(`Moved to ${button.dataset.to}`);
        await refresh();
        await openDetail(jobId);
        return;

      case 'note': {
        const input = $('#note-input');
        if (!input.value.trim()) return;
        await api(`/api/applications/${applicationId}/notes`, {
          method: 'POST',
          body: JSON.stringify({ note: input.value.trim() }),
        });
        toast('Noted');
        await openDetail(jobId);
        return;
      }

      case 'add-contact': {
        const input = $('#contact-quick');
        if (!input.value.trim()) return;
        await api('/api/contacts', {
          method: 'POST',
          body: JSON.stringify({ name: input.value.trim(), company: job.company, jobId }),
        });
        toast('Contact added');
        await openDetail(jobId);
        return;
      }

      case 'delete':
        // Two-step in place rather than a browser confirm. Deleting a tracked
        // job takes its application and event history with it, so the second
        // press has to be deliberate.
        if (button.dataset.armed === '0') {
          button.dataset.armed = '1';
          button.textContent = job?.application
            ? 'Really delete? History goes too'
            : 'Really delete?';
          return;
        }
        await api(`/api/jobs/${jobId}`, { method: 'DELETE' });
        dialog.close();
        toast('Deleted');
        await refresh();
        return;
    }
  } catch (error) { fail(error); }
});

// ── contacts ──────────────────────────────────────────────────────────

$('#contact-more').addEventListener('click', () => {
  const extra = $('#contact-extra');
  extra.hidden = !extra.hidden;
});

async function loadContacts() {
  const contacts = await api('/api/contacts');
  $('#contacts-list').innerHTML = contacts.length
    ? contacts.map((c) => `
      <article class="job" data-contact="${c.id}">
        <div class="job-main">
          <div class="job-head">
            <span class="job-title">${esc(c.name)}</span>
            <span class="badge ${c.outreachStatus === 'replied' ? 'apply' : 'status'}">${esc(c.outreachStatus)}</span>
          </div>
          <div class="job-sub">${[c.title, c.company, c.email].filter(Boolean).map(esc).join(' <span class="sep">·</span> ')}</div>
        </div>
        <div class="job-actions">
          <button class="sm ghost" data-act="edit">Edit</button>
          <button class="sm danger" data-act="delete">Delete</button>
        </div>
      </article>`).join('')
    : `<div class="empty">No contacts yet.</div>`;
  window.__contacts = contacts;
}

$('#contact-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.target;
  const body = {};
  for (const [key, value] of new FormData(form).entries()) {
    if (String(value).trim()) body[key] = String(value).trim();
  }
  const id = body.id;
  delete body.id;

  try {
    await api(id ? `/api/contacts/${id}` : '/api/contacts', {
      method: id ? 'PUT' : 'POST',
      body: JSON.stringify(body),
    });
    form.reset();
    $('#contact-extra').hidden = true;
    toast(id ? 'Contact updated' : 'Contact added');
    await loadContacts();
  } catch (error) { fail(error); }
});

$('#contacts-list').addEventListener('click', async (event) => {
  const button = event.target.closest('button[data-act]');
  if (!button) return;
  const id = Number(button.closest('[data-contact]').dataset.contact);

  try {
    if (button.dataset.act === 'edit') {
      const contact = (window.__contacts || []).find((c) => c.id === id);
      const form = $('#contact-form');
      for (const [key, value] of Object.entries(contact)) {
        if (form.elements[key]) form.elements[key].value = value ?? '';
      }
      $('#contact-extra').hidden = false;
      form.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } else if (button.dataset.act === 'delete') {
      if (button.dataset.armed !== '1') {
        button.dataset.armed = '1';
        button.textContent = 'Sure?';
        return;
      }
      await api(`/api/contacts/${id}`, { method: 'DELETE' });
      toast('Deleted');
      await loadContacts();
    }
  } catch (error) { fail(error); }
});

// ── setup ─────────────────────────────────────────────────────────────

/** The "what I am looking for" form, on the Me tab. */
async function loadPreferences() {
  const prefs = await api('/api/preferences');
  const form = $('#prefs-form');
  form.elements.remotePref.value = prefs.remotePref || 'any';
  form.elements.minSalary.value = prefs.minSalary ?? '';
  form.elements.salaryCurrency.value = prefs.salaryCurrency || '';
  for (const field of ['targetRoles', 'locations', 'mustHave', 'dealBreakers', 'excludeCompanies']) {
    form.elements[field].value = (prefs[field] || []).join('\n');
  }
}

/**
 * The questions that have blocked applications, most-blocking first.
 *
 * The highest-leverage screen here. "Why do you want to work at X" is worth
 * answering per company; "notice period in months" blocks forty forms and is
 * worth answering once — which is why they are ranked by how many applications
 * each has cost rather than listed in the order they were met.
 */
async function loadQuestions() {
  const questions = await api('/api/apply/questions');
  $('#questions-block').hidden = questions.length === 0;
  if (!questions.length) return;

  $('#questions-list').innerHTML = questions.map((q, i) => `
    <div class="question" data-q="${i}">
      <div class="question-head">
        <strong>${esc(q.question)}</strong>
        <span class="chip">blocked ${q.blocked}</span>
        ${q.lastSeenAt ? `<span class="hint">last: ${esc(q.lastSeenAt)}</span>` : ''}
      </div>
      <div class="note-row">
        <input placeholder="Your answer — used verbatim on every form asking this" data-answer>
        <button class="sm" data-act="answer">Save</button>
      </div>
    </div>`).join('');
  window.__questions = questions;
}

$('#questions-list').addEventListener('click', async (event) => {
  if (!event.target.matches('[data-act="answer"]')) return;
  const row = event.target.closest('[data-q]');
  const answer = $('[data-answer]', row).value.trim();
  if (!answer) { toast('Type an answer first', true); return; }

  try {
    await api('/api/apply/questions', {
      method: 'POST',
      body: JSON.stringify({
        question: window.__questions[Number(row.dataset.q)].question,
        answer,
      }),
    });
    toast('Answered — forms asking this will now go through');
    await loadQuestions();
  } catch (error) { fail(error); }
});

/** Everything the engine does unattended: sources, variants, people, limits. */
async function loadAutomation() {
  await Promise.all([loadReadiness(), loadContacts(), loadQuestions()]);
  const [variants, stats, sources] = await Promise.all([
    api('/api/variants'), api('/api/stats'), api('/api/sources'),
  ]);

  $('#sources-list').innerHTML = sources.length
    ? sources.map((s) => `
      <div class="mini" data-source="${s.id}">
        <strong>${esc(s.name)}</strong>
        <span class="chip">${esc(s.type)}</span>
        <span class="hint">${s.lastRunAt ? `swept ${esc(when(s.lastRunAt))}` : 'never swept'}</span>
        <button class="sm ghost" data-act="toggle">${s.enabled ? 'Pause' : 'Resume'}</button>
        <button class="sm danger" data-act="delete">Remove</button>
      </div>`).join('')
    : `<div class="hint">No boards yet. Press "Find more boards" above.</div>`;

  $('#variants-list').innerHTML = variants.length
    ? variants.map((v) => `
      <div class="mini" data-variant="${v.id}">
        <strong>${esc(v.name)}</strong>${v.isDefault ? ' <span class="badge apply">default</span>' : ''}
        <span class="hint">${esc(v.texPath)}</span>
        <button class="sm danger" data-act="delete">Delete</button>
      </div>`).join('')
    : `<div class="hint">None yet.</div>`;

  $('#engine-info').innerHTML = `
    <dt>Jobs</dt><dd>${stats.totalJobs}</dd>
    <dt>In pipeline</dt><dd>${stats.trackedApplications}</dd>
    <dt>Awaiting a score</dt><dd>${stats.unscoredJobs}</dd>
    <dt>Not yet judged by AI</dt><dd>${stats.pendingAiJobs} <span class="hint">— the Phase 4 queue</span></dd>
    <dt>Funnel</dt><dd>${Object.entries(stats.byStatus).map(([k, v]) => `${k} ${v}`).join(' · ')}</dd>`;
}

$('#prefs-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.target;
  // A full replace: a partial update of a single row is ambiguous about whether
  // an omitted list means unchanged or now-empty.
  const body = {
    remotePref: form.elements.remotePref.value,
    minSalary: form.elements.minSalary.value ? Number(form.elements.minSalary.value) : null,
    salaryCurrency: form.elements.salaryCurrency.value.trim() || null,
    targetRoles: linesOf(form.elements.targetRoles.value),
    locations: linesOf(form.elements.locations.value),
    mustHave: linesOf(form.elements.mustHave.value),
    dealBreakers: linesOf(form.elements.dealBreakers.value),
    excludeCompanies: linesOf(form.elements.excludeCompanies.value),
  };

  try {
    await api('/api/preferences', { method: 'PUT', body: JSON.stringify(body) });
    // Preferences are half of what a score means, so changing them invalidates
    // every existing verdict. Re-scoring here means the queue is never ranked
    // against rules that no longer apply.
    const run = await api('/api/jobs/rescore', { method: 'POST' });
    $('#prefs-saved').textContent = run.hadProfile
      ? `Saved. Re-scored ${run.scored} of ${run.considered}.`
      : 'Saved. No profile at .work/profile.json, so nothing is scored yet.';
    setTimeout(() => { $('#prefs-saved').textContent = ''; }, 6000);
    await loadJobs();
  } catch (error) { fail(error); }
});

// ── home: what needs you right now ────────────────────────────────────

const isToday = (iso) => iso && new Date(iso).toDateString() === new Date().toDateString();

/**
 * The landing page answers one question — what should I do now — and answers it
 * differently before and after setup. Until the five prerequisites are met
 * nothing else is worth showing; after that the checklist is gone for good and
 * the page is counts and actions.
 */
async function loadHome() {
  const ready = await loadReadiness();
  $('#onboarding').hidden = ready.ready;
  $('#home-ready').hidden = !ready.ready;
  if (!ready.ready) return;

  const [stats, jobs, applications] = await Promise.all([
    api('/api/stats'), api('/api/jobs'), api('/api/applications'),
  ]);
  state.jobs = jobs;

  const worth = jobs.filter(isReady).length;
  const today = jobs.filter((j) => isToday(j.discoveredAt)).length;
  const waiting = applications.filter(
    (a) => a.status === 'applied' || a.status === 'screening').length;

  $('#tiles').innerHTML = [
    ['New today', today, 'all'],
    ['Worth applying', worth, 'ready'],
    ['In pipeline', stats.trackedApplications, 'saved'],
    ['Awaiting reply', waiting, 'applied'],
  ].map(([label, value, filter]) =>
    `<button class="tile" data-go="jobs" data-filter="${filter}">
       <b>${value}</b><span>${esc(label)}</span></button>`).join('');

  const actions = [];
  if (worth) {
    actions.push(`<div class="action"><span class="badge apply">${worth}</span>
      scored high enough to apply to.
      <button class="sm" data-go="jobs" data-filter="ready">Show them</button></div>`);
  }
  const stale = jobs.filter((j) => j.application?.status === 'saved').length;
  if (stale) {
    actions.push(`<div class="action"><span class="badge status">${stale}</span>
      tracked but not marked applied yet.
      <button class="sm" data-go="jobs" data-filter="saved">Review</button></div>`);
  }
  if (!ready.live) {
    actions.push(`<div class="action"><span class="badge review">dry run</span>
      Auto-apply fills forms but submits nothing.
      <button class="sm" data-go="automation">Turn on real submission</button></div>`);
  }
  $('#next-actions').innerHTML = actions.length ? actions.join('')
    : `<p class="hint">Nothing needs you. Fetch new jobs, or wait for the morning sweep.</p>`;

  await loadActivity(applications);
}

/** Recent events across the pipeline, newest first. */
async function loadActivity(applications) {
  const events = (await Promise.all(
    applications.slice(0, 6).map((a) =>
      api(`/api/applications/${a.id}/events`)
        .then((list) => list.map((e) => ({ ...e, company: a.company })))
        .catch(() => []))))
    .flat()
    .sort((a, b) => new Date(b.occurredAt) - new Date(a.occurredAt))
    .slice(0, 8);

  $('#activity').innerHTML = events.length
    ? events.map((e) => `<div class="event">
        <time>${esc(whenExact(e.occurredAt))}</time>
        <span class="etype">${esc(e.type.replace('_', ' '))}</span>
        <span>${esc(e.company)} — ${esc((e.note || '').slice(0, 64))}</span></div>`).join('')
    : `<p class="hint">Nothing yet. Activity appears here as you track and apply.</p>`;
}

$('#view-home').addEventListener('click', (event) => {
  const target = event.target.closest('[data-go]');
  if (!target) return;
  if (target.dataset.filter) state.filter = target.dataset.filter;
  show(target.dataset.go);
});

// ── pipeline board ────────────────────────────────────────────────────

const STAGES = ['saved', 'applied', 'screening', 'interview', 'final', 'offer'];

/** Terminal outcomes get one shared column; six dead ones would be all board. */
const CLOSED = ['rejected', 'ghosted'];

/**
 * A board rather than a list, because a pipeline is a thing you move items
 * through and a list only tells you what state each item is in. Every job
 * tracker worth using converged on this shape for the same reason.
 *
 * The drag is where the state machine finally becomes visible: pick a card up
 * and only the columns it is legally allowed to enter light up. The rules were
 * always there -- allowedTransitions has been in the API since the pipeline
 * existed -- but a list of buttons never showed you the shape of them.
 */
async function loadPipeline() {
  const applications = await api("/api/applications");
  const board = $('#board');

  if (!applications.length) {
    board.innerHTML = `<div class="empty">Nothing tracked yet.
      Track a job from the Jobs tab and it appears here.</div>`;
    return;
  }

  const columns = [...STAGES, 'closed'];
  const label = { closed: 'Closed' };

  board.innerHTML = columns.map((stage) => {
    const inStage = applications.filter((a) =>
      stage === 'closed' ? CLOSED.includes(a.status) : a.status === stage);

    return `<div class="column" data-stage="${stage}">
        <div class="column-head">
          <span>${esc(label[stage] || stage)}</span>
          <b>${inStage.length}</b>
        </div>
        <div class="column-body">
          ${inStage.map((a) => `
            <article class="board-card" draggable="true"
                     data-app="${a.id}" data-allowed="${esc((a.allowedTransitions || []).join(','))}">
              <div class="board-title">${esc(a.title)}</div>
              <div class="board-sub">${esc(a.company)}</div>
              ${a.appliedAt ? `<div class="board-meta">applied ${esc(when(a.appliedAt))}</div>` : ''}
              ${CLOSED.includes(a.status) ? `<span class="badge terminal">${esc(a.status)}</span>` : ''}
            </article>`).join('')
            || '<div class="column-empty">—</div>'}
        </div>
      </div>`;
  }).join('');
}

let dragging = null;

$('#board').addEventListener('dragstart', (event) => {
  const card = event.target.closest('.board-card');
  if (!card) return;
  dragging = card;
  card.classList.add('is-dragging');

  // Light up only the columns this card may legally enter. The rule already
  // exists server-side; this is the first time it has been visible.
  const allowed = (card.dataset.allowed || '').split(',').filter(Boolean);
  $$('.column').forEach((column) => {
    const stage = column.dataset.stage;
    const ok = stage === 'closed'
      ? allowed.some((s) => CLOSED.includes(s))
      : allowed.includes(stage);
    column.classList.toggle('can-drop', ok);
    column.classList.toggle('no-drop', !ok);
  });
});

$('#board').addEventListener('dragend', () => {
  dragging?.classList.remove('is-dragging');
  $$('.column').forEach((c) => c.classList.remove('can-drop', 'no-drop', 'is-over'));
  dragging = null;
});

$('#board').addEventListener('dragover', (event) => {
  const column = event.target.closest('.column');
  if (!column || !column.classList.contains('can-drop')) return;
  event.preventDefault();
  column.classList.add('is-over');
});

$('#board').addEventListener('dragleave', (event) => {
  event.target.closest('.column')?.classList.remove('is-over');
});

$('#board').addEventListener('drop', async (event) => {
  const column = event.target.closest('.column');
  if (!column || !dragging || !column.classList.contains('can-drop')) return;
  event.preventDefault();

  const id = dragging.dataset.app;
  const allowed = (dragging.dataset.allowed || '').split(',').filter(Boolean);
  // The closed column stands for two statuses, so ask which one was meant.
  const stage = column.dataset.stage === 'closed'
    ? (allowed.includes('rejected') ? 'rejected' : 'ghosted')
    : column.dataset.stage;

  // Move the card first and reconcile after. A drag that visibly snaps back
  // while a request completes feels broken even when it worked.
  column.querySelector('.column-body').appendChild(dragging);

  try {
    await api(`/api/applications/${id}/transitions`, {
      method: 'POST',
      body: JSON.stringify({ status: stage }),
    });
    toast(`Moved to ${stage}`);
    await loadPipeline();
  } catch (error) {
    fail(error);
    await loadPipeline();
  }
});

// ── readiness: the order the setup has to happen in ───────────────────

/**
 * The engine has five prerequisites and they only make sense in order — skills
 * before scoring means anything, roles before a sweep is allowed, details and a
 * resume before anything can be submitted. Shown as a checklist because shown
 * as a pile of settings panels it read as noise.
 */
async function loadReadiness() {
  const r = await api('/api/readiness');

  const done = r.steps.filter((s) => s.done).length;
  const chip = $('#ready-chip');
  chip.textContent = r.armed
    ? 'auto-applying'
    : r.ready ? 'ready — dry run' : `setup ${done}/${r.steps.length}`;
  chip.className = 'ready-chip ' + (r.armed ? 'armed' : r.ready ? 'ok' : 'todo');

  const list = $('#checklist');
  if (list) {
    list.innerHTML = r.steps.map((s, i) => `
      <div class="step ${s.done ? 'is-done' : ''}">
        <span class="step-num">${s.done ? '✓' : i + 1}</span>
        <span class="step-body">
          <strong>${esc(s.title)}</strong>
          <span class="hint">${esc(s.detail)}</span>
        </span>
        ${s.done ? '' : `<button class="sm" data-goto="${esc(s.goTo)}">Fix</button>`}
      </div>`).join('');
  }

  const state = $('#autoapply-state');
  if (state) {
    state.innerHTML = !r.ready
      ? `<div class="problem">Finish setup above first — auto-apply refuses to run
           until it has your details and a resume.</div>`
      : `<div class="result-ok">
           <strong>${r.live ? 'Live — applications are submitted.' : 'Dry run — fills forms, submits nothing.'}</strong>
           <div class="hint">Only jobs scoring ${r.minScore}+, at most ${r.dailyLimit} a day.</div>
         </div>`;
  }
  wireDials(r);
  return r;
}

$('#checklist')?.addEventListener('click', (event) => {
  const target = event.target.closest('[data-goto]')?.dataset.goto;
  if (!target) return;
  // "sources" and "preferences" live on this page; the rest are a tab away.
  if (target === 'profile') {
    $$('.tab').find((t) => t.dataset.view === 'profile')?.click();
  } else {
    $(`#${target === 'sources' ? 'sources-form' : 'prefs-form'}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
});

// ── your details: what the forms ask for ──────────────────────────────

async function loadApplicant() {
  const view = await api('/api/applicant');
  const form = $('#applicant-form');
  const d = view.details;

  if (d) {
    for (const [key, value] of Object.entries(d)) {
      const field = form.elements[key];
      if (!field) continue;
      field.value = value === null || value === undefined ? '' : String(value);
    }
  }

  $('#resume-state').innerHTML = view.hasResume
    ? `<span class="ok-text">✓ ${esc(view.details.resumePath)}</span>`
    : `<span class="warn-text">No resume uploaded — every application is blocked until there is one.</span>`;
}

$('#applicant-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.target;
  const body = {};
  for (const el of form.elements) {
    if (!el.name) continue;
    const raw = el.value.trim();
    if (raw === '') { body[el.name] = null; continue; }
    // Numbers must go over as numbers: a CTC sent as a string lands in the
    // form as a string and some ATS number fields silently drop it.
    body[el.name] = el.type === 'number' ? Number(raw)
      : el.name === 'requiresVisaSponsorship' ? raw === 'true'
      : raw;
  }

  try {
    await api('/api/applicant', { method: 'PUT', body: JSON.stringify(body) });
    $('#applicant-saved').textContent = 'Saved.';
    setTimeout(() => { $('#applicant-saved').textContent = ''; }, 3000);
    await Promise.all([loadApplicant(), loadReadiness()]);
  } catch (error) { fail(error); }
});

$('#resume-upload').addEventListener('click', async () => {
  const input = $('#resume-file');
  if (!input.files?.length) { toast('Choose a file first', true); return; }

  const body = new FormData();
  body.append('file', input.files[0]);
  try {
    // Not through api(): this is multipart, so the browser must set the
    // boundary itself and a JSON content-type would break it.
    const response = await fetch('/api/applicant/resume', { method: 'POST', body });
    if (!response.ok) throw new ProblemError(response.status, await response.json());
    toast('Resume uploaded');
    input.value = '';
    await Promise.all([loadApplicant(), loadReadiness()]);
  } catch (error) { fail(error); }
});

// ── auto-apply ────────────────────────────────────────────────────────

$('#apply-now').addEventListener('click', () => busy('#apply-now', async () => {
  // A real browser opening a real form per job. Minutes, not seconds — and a
  // silent button for that long is indistinguishable from a broken one.
  $('#apply-status').textContent = 'Opening each form in a real browser — a few minutes…';
  $('#apply-result').innerHTML = '';

  try {
    const run = await api('/api/apply/run', { method: 'POST' });
    $('#apply-status').textContent = '';
    const counts = Object.entries(run.byOutcome || {})
      .map(([k, v]) => `${v} ${k.replace('_', ' ').toLowerCase()}`).join(', ');

    const interesting = run.attempts.filter((a) => a.outcome !== 'SKIPPED');
    $('#apply-result').innerHTML = `
      <div class="result-ok">
        <strong>${run.live ? `${run.submitted} submitted` : 'Dry run — nothing submitted'}</strong>
        <div class="hint">${esc(counts)}</div>
        <div class="sweep-rows">
          ${interesting.map((a) => `
            <div class="mini">
              <span class="badge ${a.outcome === 'SUBMITTED' ? 'apply' : a.outcome === 'FAILED' ? 'skip' : 'status'}">${esc(a.outcome.toLowerCase().replace('_', ' '))}</span>
              <strong>${esc(a.company)}</strong>
              <span class="hint">${esc((a.title || '').slice(0, 44))}</span>
              ${a.blockedBy?.length ? `<span class="hint">— asks: ${esc(a.blockedBy.slice(0, 2).join(', '))}</span>` : ''}
            </div>`).join('')}
        </div>
        ${run.attempts.some((a) => a.outcome === 'NEEDS_HUMAN')
          ? `<p class="hint">The <strong>needs human</strong> ones are worth your evening — their
             forms ask something only you can answer, which is usually a sign the
             employer is actually reading.</p>` : ''}
      </div>`;
    $('#apply-status').textContent = '';
    await loadHome();
  } catch (error) {
    $('#apply-status').textContent = '';
    $('#apply-result').innerHTML = problemHtml(error);
  }
}));

// ── the profile: the single biggest lever on whether the queue is any good ──

let draft = null;

async function loadProfileState() {
  const view = await api('/api/profile');
  const state = $('#profile-state');
  const chips = $('#skill-chips');

  if (!view.profile) {
    state.innerHTML = `<span class="warn-text">No profile yet — nothing is being scored.</span>`;
    chips.innerHTML = '';
    // Nothing to confirm means nothing to fold away: open the one form that
    // gets this screen out of its empty state.
    $('#resume-fold').open = true;
    renderIdentity(null);
    return;
  }

  const skills = view.profile.skills || [];
  state.innerHTML = `<span class="hint">${esc(view.path)}</span>`;

  // Shown, not counted. "Scoring against 24 skills" asked you to take the
  // engine's word for the one input every score depends on.
  chips.innerHTML = skills.map((skill) => {
    const strong = (skill.proficiency || 0) >= 4;
    return `<span class="chip-skill${strong ? ' is-strong' : ''}"
      title="${strong ? 'mentioned often in your resume' : 'mentioned once or twice'}"
      >${esc(skill.name)}</span>`;
  }).join('') || `<span class="hint">None read yet.</span>`;

  renderIdentity(view.profile);
}

/** The glance-and-confirm header: who the engine thinks you are. */
async function renderIdentity(profile) {
  const name = profile?.name?.trim();
  $('#identity-name').textContent = name || 'Nobody yet';
  $('#identity-initials').textContent = name
    ? name.split(/\s+/).slice(0, 2).map((part) => part[0]).join('').toUpperCase()
    : '—';

  const locations = profile?.locations || [];
  $('#identity-sub').textContent = profile
    ? [profile.headline, locations.join(' · ')].filter(Boolean).join(' — ')
      || 'Read from your resume'
    : 'Upload a resume and this fills itself in.';

  $('#identity-years').textContent = profile?.yearsExperience ?? '—';
  $('#identity-skills').textContent = (profile?.skills || []).length || '—';

  // The number that answers "is any of this working". Counted from the job
  // list rather than added to /api/stats, whose shape is pinned by contract
  // tests. Failing to fetch it is not worth breaking the header over.
  try {
    const worthApplying = await api('/api/jobs?verdict=apply');
    $('#identity-matches').textContent = worthApplying.length;
  } catch { /* leave the dash */ }
}

$('#resume-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.target;
  const locations = (form.elements.locations.value || '')
    .split(',').map((s) => s.trim()).filter(Boolean);

  try {
    const response = await api('/api/profile/draft', {
      method: 'POST',
      body: JSON.stringify({
        text: form.elements.text.value,
        yearsExperience: form.elements.yearsExperience.value
          ? Number(form.elements.yearsExperience.value) : null,
        name: form.elements.name.value.trim() || null,
        locations,
      }),
    });

    draft = response.profile;
    // The resume often states its own years of experience; offer it rather
    // than making them find it, but never overwrite something typed.
    if (!form.elements.yearsExperience.value && response.hints?.yearsExperience) {
      form.elements.yearsExperience.value = response.hints.yearsExperience;
      draft.yearsExperience = response.hints.yearsExperience;
    }
    renderSkillDraft();
  } catch (error) { fail(error); }
});

function renderSkillDraft() {
  if (!draft) return;
  $('#skill-draft').hidden = false;
  $('#skill-count').textContent = `${draft.skills.length} recognised`;

  $('#skill-rows').innerHTML = draft.skills.length
    ? draft.skills.map((skill, index) => `
      <div class="skill-row" data-index="${index}">
        <span class="chip tech">${esc(skill.name)}</span>
        <input type="range" min="1" max="5" value="${skill.proficiency ?? 3}" data-prof>
        <span class="factor-num" data-shown>${skill.proficiency ?? 3}/5</span>
        <button type="button" class="sm ghost" data-drop>Remove</button>
      </div>`).join('')
    : `<div class="hint">Nothing recognised. The dictionary covers mainstream
        backend and web technologies — if your stack is unusual, add the skills
        by editing the profile file directly.</div>`;
}

$('#skill-rows').addEventListener('input', (event) => {
  const row = event.target.closest('.skill-row');
  if (!row || !event.target.matches('[data-prof]')) return;
  const value = Number(event.target.value);
  draft.skills[Number(row.dataset.index)].proficiency = value;
  $('[data-shown]', row).textContent = `${value}/5`;
});

$('#skill-rows').addEventListener('click', (event) => {
  if (!event.target.matches('[data-drop]')) return;
  const index = Number(event.target.closest('.skill-row').dataset.index);
  draft.skills.splice(index, 1);
  renderSkillDraft();
});

$('#save-profile').addEventListener('click', async () => {
  if (!draft) return;
  const button = $('#save-profile');
  button.disabled = true;
  try {
    const result = await api('/api/profile', { method: 'PUT', body: JSON.stringify(draft) });
    $('#profile-saved').textContent = `Saved. Re-scored ${result.rescored} jobs.`;
    await Promise.all([loadProfileState(), loadJobs()]);
    toast(`Profile saved — ${result.rescored} jobs re-scored`);
  } catch (error) { fail(error); }
  finally { button.disabled = false; }
});

// ── the watchlist and the sweep ───────────────────────────────────────

/** Re-probe the shipped candidate list. Companies switch ATS; new ones appear. */
$('#discover-boards').addEventListener('click', () => busy('#discover-boards', async () => {
  $('#discover-status').textContent = 'Probing a few hundred companies — about 40 seconds…';
  try {
    const result = await discover(null);
    $('#discover-status').textContent = result.added
      ? `Found ${result.added} new board(s) out of ${result.probed} probed.`
      : `Nothing new — all ${result.found.length} boards found are already on the list.`;
    await loadAutomation();
  } catch (error) {
    $('#discover-status').textContent = '';
    fail(error);
  }
}));

/**
 * Adding a company means naming it, not knowing its ATS.
 *
 * The old form wanted "greenhouse: groww" — which requires knowing that Groww
 * is on Greenhouse and that its token is spelled the obvious way. Both are
 * things the engine can find out in three requests.
 */
$('#sources-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.target;
  const names = form.elements.text.value.trim();
  if (!names) return;

  $('#discover-status').textContent = 'Looking these up…';
  try {
    const result = await discover(names);
    form.reset();
    if (result.added) {
      toast(`Added ${result.added}: ${result.found.map((f) => f.slug).join(', ')}`);
      $('#discover-status').textContent = '';
    } else {
      // Silence here would read as a bug. It usually means the company is not
      // on one of the three boards the engine can read, which is worth saying.
      $('#discover-status').textContent = result.skipped
        ? 'Already on the list.'
        : 'No board found. That company is probably not on Greenhouse, Lever or '
          + 'Ashby — use the capture button on its careers page instead.';
    }
    await loadAutomation();
  } catch (error) {
    $('#discover-status').textContent = '';
    fail(error);
  }
});

function discover(names) {
  return api('/api/sources/discover', {
    method: 'POST',
    body: JSON.stringify({ names }),
  });
}

$('#sources-list').addEventListener('click', async (event) => {
  const button = event.target.closest('button[data-act]');
  if (!button) return;
  const row = button.closest('[data-source]');
  const id = row.dataset.source;

  try {
    if (button.dataset.act === 'toggle') {
      const resuming = button.textContent.trim() === 'Resume';
      await api(`/api/sources/${id}/enabled?value=${resuming}`, { method: 'POST' });
      await loadAutomation();
    } else if (button.dataset.act === 'delete') {
      if (button.dataset.armed !== '1') {
        button.dataset.armed = '1';
        button.textContent = 'Sure?';
        return;
      }
      // Only the board goes; jobs already found through it stay.
      await api(`/api/sources/${id}`, { method: 'DELETE' });
      await loadAutomation();
    }
  } catch (error) { fail(error); }
});

$('#sweep-now').addEventListener('click', () => busy('#sweep-now', async () => {
  // Naming the duration matters: twenty silent seconds reads as a broken
  // button, and the same twenty seconds with a number on them reads as work.
  // Name the duration: twenty silent seconds reads as a broken button, and the
  // same seconds with a number on them read as work. Scale it with the
  // watchlist, which discovery grew from a dozen boards to over a hundred.
  const boards = $$('#sources-list [data-source]').length;
  const minutes = Math.max(1, Math.round((boards * 1.4) / 60));
  $('#sweep-status').textContent = boards
    ? `Fetching from ${boards} boards — about ${minutes} minute${minutes === 1 ? '' : 's'}…`
    : 'Fetching…';
  $('#sweep-result').innerHTML = '';

  try {
    const report = await api('/api/sources/sweep', { method: 'POST' });
    $('#sweep-status').textContent = 'Runs automatically each morning.';
    $('#sweep-result').innerHTML = `
      <div class="result-ok">
        <strong>${report.created} new</strong>, ${report.updated} updated,
        ${report.unchanged} already known —
        from ${report.considered} matching your target roles
        out of ${report.fetched} posted across ${report.companies} companies.
        ${report.companiesFailed ? `<span class="warn-text">${report.companiesFailed} board(s) could not be read.</span>` : ''}
        <div class="sweep-rows">
          ${report.sources.filter((s) => s.error || s.created || s.considered).map((s) =>
            `<div class="mini"><strong>${esc(s.company)}</strong>
               <span class="hint">${s.error
                 ? esc(s.error)
                 : `${s.considered} matched, ${s.created} new`}</span></div>`).join('')}
        </div>
      </div>`;
    await loadHome();
    if (report.created) toast(`${report.created} new jobs`);
  } catch (error) {
    $('#sweep-status').textContent = '';
    $('#sweep-result').innerHTML = problemHtml(error);
  }
}));

$('#variant-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.target;
  try {
    await api('/api/variants', {
      method: 'POST',
      body: JSON.stringify({
        name: form.elements.name.value.trim(),
        texPath: form.elements.texPath.value.trim(),
        isDefault: form.elements.isDefault.checked,
      }),
    });
    form.reset();
    toast('Variant added');
    await loadAutomation();
  } catch (error) { fail(error); }
});

$('#variants-list').addEventListener('click', async (event) => {
  const button = event.target.closest('button[data-act="delete"]');
  if (!button) return;
  if (button.dataset.armed !== '1') {
    button.dataset.armed = '1';
    button.textContent = 'Sure?';
    return;
  }
  try {
    await api(`/api/variants/${button.closest('[data-variant]').dataset.variant}`, { method: 'DELETE' });
    toast('Deleted');
    await loadAutomation();
  } catch (error) { fail(error); }
});

// ── boot ──────────────────────────────────────────────────────────────

async function refresh() {
  await loadJobs();
}

/**
 * Pick up a posting sent by the bookmarklet.
 *
 * It lands in the paste box rather than being ingested, so the company and
 * title still pass through fields a person looked at. Same rule as a manual
 * paste; the bookmarklet only saved the copying.
 */
async function collectCapture() {
  if (!new URLSearchParams(location.search).has('captured')) return;
  history.replaceState({}, '', location.pathname);

  try {
    const capture = await api('/api/capture');
    if (!capture || !capture.text) return;

    pasteBox.value = capture.text;
    if (capture.url) $('#d-url').value = capture.url;
    onPasteInput();
    pasteBox.scrollIntoView({ behavior: 'smooth', block: 'start' });
    toast('Captured — check the three fields and press Add');
  } catch (error) { fail(error); }
}

// Land where you left off. A reload during setup that throws you back to a
// tab you had finished with is a small thing that feels broken every time.
const START = location.hash.slice(1);
show(["home", "jobs", "pipeline", "me", "automation"].includes(START) ? START : "home");
collectCapture();

// Pasting is the rare path now that sweeps run nightly, so the box starts
// collapsed rather than dominating the screen it is least often used on.
$('#toggle-paste').addEventListener('click', () => {
  const form = $('#paste-form');
  form.hidden = !form.hidden;
  if (!form.hidden) pasteBox.focus();
});

// ── getting started ───────────────────────────────────────────────────

/**
 * Two screens: read the resume, confirm what it found, go.
 *
 * Everything the old five-step checklist asked for is either in the resume
 * (skills, roles, contact details) or has a defensible default (the watchlist,
 * work style, currency). What is left genuinely cannot be inferred -- notice
 * period, expected salary, where you will actually work.
 */
let obDraft = null;

$('#ob-file').addEventListener('change', async (event) => {
  const file = event.target.files?.[0];
  if (!file) return;
  const body = new FormData();
  body.append('file', file);
  await readResume(() => fetch('/api/onboarding/resume', { method: 'POST', body }));
});

$('#ob-paste-toggle').addEventListener('click', () => {
  $('#ob-paste').hidden = !$('#ob-paste').hidden;
});

$('#ob-paste-go').addEventListener('click', () => readResume(() =>
  fetch('/api/onboarding/paste', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: $('#ob-paste-text').value }),
  })));

async function readResume(send) {
  const zone = $('#dropzone');
  zone.classList.add('is-busy');
  $('#ob-problem').innerHTML = '';
  try {
    const response = await send();
    if (!response.ok) throw new ProblemError(response.status, await response.json());
    obDraft = await response.json();
    showDraft();
  } catch (error) {
    $('#ob-problem').innerHTML = problemHtml(error);
  } finally {
    zone.classList.remove('is-busy');
  }
}

function showDraft() {
  $('#ob-step1').hidden = true;
  $('#ob-step2').hidden = false;

  const form = $('#ob-step2');
  form.elements.email.value = obDraft.email || '';
  form.elements.phone.value = obDraft.phone || '';
  form.elements.roles.value = (obDraft.roles || []).join('\n');

  $('#ob-read').innerHTML = `Read <strong>${obDraft.skills.length} skills</strong>
    ${obDraft.yearsExperience ? `and <strong>${obDraft.yearsExperience} years</strong>` : ''}
    from your resume. Correct anything below.`;

  $('#ob-skill-count').textContent = `${obDraft.skills.length} found`;
  $('#ob-skills').innerHTML = obDraft.skills.map((s, i) => `
    <div class="skill-row" data-index="${i}">
      <span class="chip tech">${esc(s.name)}</span>
      <input type="range" min="1" max="5" value="${s.proficiency ?? 3}" data-prof>
      <span class="factor-num" data-shown>${s.proficiency ?? 3}/5</span>
      <button type="button" class="sm ghost" data-drop>Remove</button>
    </div>`).join('');
}

$('#ob-skills').addEventListener('input', (event) => {
  const row = event.target.closest('.skill-row');
  if (!row || !event.target.matches('[data-prof]')) return;
  const value = Number(event.target.value);
  obDraft.skills[Number(row.dataset.index)].proficiency = value;
  $('[data-shown]', row).textContent = `${value}/5`;
});

$('#ob-skills').addEventListener('click', (event) => {
  if (!event.target.matches('[data-drop]')) return;
  obDraft.skills.splice(Number(event.target.closest('.skill-row').dataset.index), 1);
  showDraft();
});

$('#ob-step2').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.target;
  const button = form.querySelector('button[type=submit]');

  await busy(button, async () => {
    button.textContent = 'Fetching jobs…';
    try {
      const result = await api('/api/onboarding/start', {
        method: 'POST',
        body: JSON.stringify({
          name: form.elements.name.value.trim() || null,
          email: form.elements.email.value.trim() || null,
          phone: form.elements.phone.value.trim() || null,
          yearsExperience: obDraft.yearsExperience,
          skills: obDraft.skills,
          roles: linesOf(form.elements.roles.value),
          locations: linesOf(form.elements.locations.value),
          noticePeriodDays: form.elements.noticePeriodDays.value
            ? Number(form.elements.noticePeriodDays.value) : null,
          expectedCtc: form.elements.expectedCtc.value
            ? Number(form.elements.expectedCtc.value) : null,
          linkedinUrl: obDraft.linkedinUrl,
          githubUrl: obDraft.githubUrl,
          resumePath: obDraft.resumePath,
        }),
      });

      $('#ob-step2').hidden = true;
      $('#ob-done').hidden = false;
      $('#ob-done').innerHTML = result.error
        ? `<div class="problem">Saved, but the first fetch failed: ${esc(result.error)}</div>`
        : `<div class="result-ok centred">
             <h2>${result.found} jobs found</h2>
             <p class="hint">${result.matched} matched your roles, ${result.scored} scored against your skills.</p>
             <button class="primary big" data-go="jobs">See them</button>
           </div>`;
    } finally {
      button.textContent = 'Find my jobs';
    }
  });
});

// ── the two dials that decide whether anything happens ────────────────

/**
 * The score threshold is the control that decides whether auto-apply does
 * anything at all, and its right value depends entirely on how the current
 * queue happens to score. It was an environment variable requiring a restart,
 * which is not a control anybody can use. It is a slider now, and it says how
 * many jobs currently clear it.
 */
function wireDials(readiness) {
  const score = $('#dial-score');
  const limit = $('#dial-limit');
  if (!score) return;

  score.value = readiness.minScore;
  limit.value = readiness.dailyLimit;
  paintLive(readiness.live);
  paintDials();
}

/**
 * The live switch.
 *
 * Turning it on is a real change and says so; turning it off needs no
 * ceremony. The confirm is not a nag — the next scheduled run submits real
 * applications under the user's name, and that is worth one deliberate click.
 */
function paintLive(live) {
  const box = $('#dial-live');
  if (!box) return;
  box.checked = !!live;
  $('#live-switch').classList.toggle('is-live', !!live);
  $('#live-title').textContent = live
    ? 'Submitting applications for real'
    : 'Submit applications for real';
  $('#live-sub').textContent = live
    ? 'Auto-apply presses submit. Applications cannot be recalled.'
    : 'Currently a dry run: forms are filled and abandoned.';
}

$('#dial-live')?.addEventListener('change', async (event) => {
  const box = event.target;
  const turningOn = box.checked;

  if (turningOn && !confirm(
    'Turn on real submission?\n\n'
    + 'Auto-apply will send real applications to employers under your name, '
    + 'up to the daily cap. They cannot be recalled.')) {
    box.checked = false;
    return;
  }

  try {
    const status = await api('/api/apply/settings', {
      method: 'POST',
      body: JSON.stringify({ live: turningOn }),
    });
    paintLive(status.live);
    toast(status.live ? 'Live. Applications will be submitted.' : 'Back to dry run.');
    await loadReadiness();
  } catch (error) {
    box.checked = !turningOn;
    fail(error);
  }
});

function paintDials() {
  const score = Number($('#dial-score').value);
  const limit = Number($('#dial-limit').value);
  $('#dial-score-val').textContent = score;
  $('#dial-limit-val').textContent = limit;

  const eligible = state.jobs.filter((j) =>
    !j.application && j.match && (j.match.aiScore ?? j.match.heuristicScore) >= score);
  const top = [...eligible].sort((a, b) =>
    (b.match.aiScore ?? b.match.heuristicScore) - (a.match.aiScore ?? a.match.heuristicScore));

  $('#dial-effect').innerHTML = eligible.length
    ? `<strong>${Math.min(eligible.length, limit)}</strong> would be applied to
       ${eligible.length > limit ? `(${eligible.length} qualify, capped at ${limit})` : ''}
       <div class="hint">${top.slice(0, 3).map((j) =>
         esc(j.company + ' — ' + j.title.slice(0, 34))).join(' · ')}</div>`
    : `<span class="warn-text">Nothing qualifies at ${score}+.</span>
       <span class="hint">Your best match is
       ${state.jobs.filter((j) => j.match).length
         ? Math.max(...state.jobs.filter((j) => j.match).map((j) => j.match.heuristicScore))
         : 0}. Lower the bar to let anything through.</span>`;
}

['#dial-score', '#dial-limit'].forEach((id) => {
  $(id)?.addEventListener('input', paintDials);
  $(id)?.addEventListener('change', async () => {
    try {
      await api('/api/apply/settings', {
        method: 'POST',
        body: JSON.stringify({
          minScore: Number($('#dial-score').value),
          dailyLimit: Number($('#dial-limit').value),
        }),
      });
      await loadReadiness();
    } catch (error) { fail(error); }
  });
});
