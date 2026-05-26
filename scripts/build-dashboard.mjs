// BTS 개발 진척도 + 용어 사전을 단일 HTML(docs/progress.html)로 생성하는 빌드 스크립트
// 입력. docs/plan/product/*.md, docs/plan/fr-index.md, Maxi_wiki/BTS/glossary.md, Maxi_wiki/BTS/용어 정리.md
// 실행. node scripts/build-dashboard.mjs

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '..');
const HOME = process.env.HOME || process.env.USERPROFILE || '';

const PLAN_DIR = path.join(REPO_ROOT, 'docs/plan/product');
const FR_INDEX_PATH = path.join(REPO_ROOT, 'docs/plan/fr-index.md');
const GLOSSARY_PATH = path.join(HOME, 'Maxi_wiki/BTS/glossary.md');
const YONGEO_PATH = path.join(HOME, 'Maxi_wiki/BTS/용어 정리.md');
const OUTPUT_PATH = path.join(REPO_ROOT, 'docs/progress.html');

const BC_MAPPING = {
  'issue-tracking':         { label: '이슈 관리',      emoji: '📌', desc: '이슈를 만들고, 상태를 바꾸고, 댓글/첨부/링크를 관리하는 영역.' },
  'project-workflow':       { label: '워크플로우',     emoji: '🔄', desc: '이슈가 어떤 상태를 거쳐 가는지 규칙을 정하는 영역.' },
  'automation':             { label: '자동화 규칙',    emoji: '🤖', desc: '특정 조건이 충족되면 자동으로 동작을 실행하는 영역.' },
  'agile-planning':         { label: '스프린트/보드',  emoji: '📅', desc: '칸반 보드, 백로그, 스프린트, 타임라인 등 일정 관리 영역.' },
  'notification-dashboard': { label: '알림/대시보드',  emoji: '🔔', desc: '이메일/Slack/인앱 알림 + 개인 대시보드 영역.' },
  'personalization':        { label: '개인화',         emoji: '🎨', desc: '필터/뷰/단축키 등 사용자별 환경 설정 영역.' },
  'search-export-import':   { label: '검색/입출력',    emoji: '🔍', desc: 'AQL 검색, CSV/Excel 내보내기/가져오기 영역.' },
  'slack-integration':      { label: 'Slack 연동',     emoji: '💬', desc: 'Slack 메시지를 이슈로 변환, 봇 알림 영역.' },
  'identity-access':        { label: '계정/권한',      emoji: '🔐', desc: '로그인, 2단계 인증, SSO, 권한 검사 영역.' },
};

const STATUS_ORDER = ['진행중', '차단', '미진행', '완료'];
const STATUS_META = {
  '완료':   { icon: '✅', color: '#22c55e', marker: 'x' },
  '진행중': { icon: '🔄', color: '#3b82f6', marker: '~' },
  '차단':   { icon: '⛔', color: '#ef4444', marker: '!' },
  '미진행': { icon: '⬜', color: '#9ca3af', marker: ' ' },
};

function readFileSafe(p) {
  if (!fs.existsSync(p)) throw new Error(`입력 파일 없음: ${p}`);
  return fs.readFileSync(p, 'utf-8');
}

function aggregateFrStatus(steps) {
  if (steps.length === 0) return '미진행';
  if (steps.some(s => s.status === '차단')) return '차단';
  if (steps.every(s => s.status === '완료')) return '완료';
  if (steps.some(s => s.status === '완료' || s.status === '진행중')) return '진행중';
  return '미진행';
}

function markerToStatus(m) {
  switch (m) {
    case 'x': return '완료';
    case '~': return '진행중';
    case '!': return '차단';
    default: return '미진행';
  }
}

export function parsePlanFile(content, bcSlug) {
  const lines = content.split('\n');
  const frs = [];
  let currentFr = null;
  const frHeaderRe = /^#{3,4}\s+§[\d.]+\s+(FR-[A-Z]+-\d+)\s*[—\-]\s*(.+?)\s*$/;
  const stepRe = /^- \[([ x~!])\]\s+(D[1-7])\.\s+(.+?)(?:\s*\(책임\..*\))?\s*$/;

  for (const line of lines) {
    const fr = line.match(frHeaderRe);
    if (fr) {
      if (currentFr) frs.push(currentFr);
      currentFr = { id: fr[1], description: fr[2].trim(), steps: [] };
      continue;
    }
    if (currentFr) {
      const step = line.match(stepRe);
      if (step) {
        currentFr.steps.push({
          phase: step[2],
          status: markerToStatus(step[1]),
          description: step[3].trim(),
        });
      }
    }
  }
  if (currentFr) frs.push(currentFr);

  for (const fr of frs) fr.status = aggregateFrStatus(fr.steps);
  return { bcSlug, frs };
}

export function parseFrIndex(content) {
  const map = new Map();
  const rowRe = /^\|\s*(FR-[A-Z]+-\d+)\s*\|\s*(.+?)\s*\|\s*(.+?)\s*\|\s*([\w-]+)\s*\|\s*§[\d.]+\s*\|\s*$/;
  for (const line of content.split('\n')) {
    const m = line.match(rowRe);
    if (m) {
      map.set(m[1], { id: m[1], oneLiner: m[2].trim(), priority: m[3].trim(), bcSlug: m[4].trim() });
    }
  }
  return map;
}

function stripBold(s) {
  return s.replace(/\*\*(.+?)\*\*/g, '$1').trim();
}

function stripUnderline(s) {
  return s.replace(/_(.+?)_/g, '$1').trim();
}

function cleanDef(s) {
  return stripUnderline(stripBold(s)).replace(/\s+/g, ' ').trim();
}

export function parseGlossary(content) {
  const terms = [];
  const lines = content.split('\n');
  let currentSection = null;
  let inTable = false;
  let tableColumns = 0;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const sectionMatch = line.match(/^##\s+(.+?)\s*$/);
    if (sectionMatch) {
      currentSection = sectionMatch[1].trim();
      inTable = false;
      continue;
    }
    if (line.match(/^\|[\s\-:|]+\|\s*$/)) {
      inTable = true;
      tableColumns = line.split('|').filter(c => c.trim() !== '').length;
      continue;
    }
    if (inTable && line.startsWith('|')) {
      const cells = line.split('|').map(c => c.trim()).filter((_, idx, arr) => idx > 0 && idx < arr.length - 1);
      if (cells.length === 0) continue;
      if (cells[0] === '용어' || cells[0].includes('---')) continue;

      if (tableColumns === 3) {
        const [term, eng, def] = cells;
        const cleanedTerm = cleanDef(term);
        const cleanedEng = cleanDef(eng);
        const cleanedDef = cleanDef(def);
        if (cleanedTerm) terms.push({ term: cleanedTerm, category: `도메인 — ${currentSection}`, definition: cleanedDef, source: 'glossary' });
        if (cleanedEng && cleanedEng !== cleanedTerm) terms.push({ term: cleanedEng, category: `도메인 — ${currentSection}`, definition: cleanedDef, source: 'glossary' });
      } else if (tableColumns === 2) {
        const [term, def] = cells;
        const cleanedTerm = cleanDef(term);
        const cleanedDef = cleanDef(def);
        if (cleanedTerm) terms.push({ term: cleanedTerm, category: `도메인 — ${currentSection}`, definition: cleanedDef, source: 'glossary' });
      }
    } else if (line.trim() === '' || line.startsWith('#') || line.startsWith('>')) {
      inTable = false;
    }
  }
  return terms;
}

export function parseYongeo(content) {
  const terms = [];
  const lines = content.split('\n');
  let currentSection = null;
  let inTable = false;

  for (const line of lines) {
    const sectionMatch = line.match(/^#\s+(.+?)\s*$/);
    if (sectionMatch) {
      currentSection = sectionMatch[1].trim();
      inTable = false;
      continue;
    }
    if (line.match(/^\|[\s\-:|]+\|\s*$/)) {
      inTable = true;
      continue;
    }
    if (inTable && line.startsWith('|')) {
      const cells = line.split('|').map(c => c.trim()).filter((_, idx, arr) => idx > 0 && idx < arr.length - 1);
      if (cells.length < 2) continue;
      if (cells[0].includes('개발 용어')) continue;

      const rawTerm = cells[0];
      const rawDef = cells[1];

      const fullTerm = stripUnderline(stripBold(rawTerm));
      const definition = cleanDef(rawDef);
      if (!fullTerm || !definition) continue;

      const splitTerms = fullTerm.split(/\s*\/\s*/).map(t => t.trim()).filter(Boolean);
      const expanded = [];
      for (const t of splitTerms) {
        expanded.push(t);
        const acronymMatch = t.match(/^([A-Z][A-Za-z0-9]+)\s*\(([^)]+)\)\s*$/);
        if (acronymMatch) {
          expanded.push(acronymMatch[1].trim());
          expanded.push(acronymMatch[2].trim());
        }
      }
      const uniqueTerms = Array.from(new Set(expanded));
      for (const t of uniqueTerms) {
        terms.push({ term: t, category: currentSection || '개발 일반', definition, source: 'yongeo' });
      }
    } else if (line.trim() === '' || line.startsWith('#') || line.startsWith('>')) {
      inTable = false;
    }
  }
  return terms;
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function escapeAttr(s) {
  return escapeHtml(s);
}

function buildTermLookup(terms) {
  const map = new Map();
  for (const t of terms) {
    const key = t.term.toLowerCase();
    if (!map.has(key)) map.set(key, t);
  }
  const sorted = Array.from(map.values()).sort((a, b) => b.term.length - a.term.length);
  return sorted;
}

function escapeRegex(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function annotateTerms(text, sortedTerms) {
  let html = escapeHtml(text);
  const used = new Set();
  for (const t of sortedTerms) {
    if (used.has(t.term.toLowerCase())) continue;
    if (t.term.length < 2) continue;
    const re = new RegExp(`(${escapeRegex(t.term)})`, 'i');
    const m = html.match(re);
    if (m && !isInsideTag(html, m.index)) {
      const before = html.slice(0, m.index);
      const after = html.slice(m.index + m[0].length);
      const def = escapeAttr(t.definition).slice(0, 200);
      html = `${before}<span class="term" data-def="${def}">${m[0]}</span>${after}`;
      used.add(t.term.toLowerCase());
    }
  }
  return html;
}

function isInsideTag(html, idx) {
  const before = html.slice(0, idx);
  const lastOpen = before.lastIndexOf('<');
  const lastClose = before.lastIndexOf('>');
  return lastOpen > lastClose;
}

function computeBcStats(frs) {
  const stats = { 완료: 0, 진행중: 0, 차단: 0, 미진행: 0 };
  for (const fr of frs) stats[fr.status]++;
  const total = frs.length;
  const pct = total === 0 ? 0 : Math.round((stats['완료'] / total) * 100);
  return { ...stats, total, pct };
}

function computeOverallStats(allFrs) {
  return computeBcStats(allFrs);
}

function donutSvg(pct, size = 260, strokeWidth = 28) {
  const r = (size - strokeWidth) / 2;
  const cx = size / 2;
  const cy = size / 2;
  const circumference = 2 * Math.PI * r;
  const offset = circumference * (1 - pct / 100);
  return `<svg viewBox="0 0 ${size} ${size}" class="donut">
    <circle cx="${cx}" cy="${cy}" r="${r}" fill="none" stroke="#e5e7eb" stroke-width="${strokeWidth}" />
    <circle cx="${cx}" cy="${cy}" r="${r}" fill="none" stroke="#22c55e" stroke-width="${strokeWidth}"
      stroke-dasharray="${circumference}" stroke-dashoffset="${offset}"
      transform="rotate(-90 ${cx} ${cy})" stroke-linecap="round" />
    <text x="${cx}" y="${cy}" text-anchor="middle" dominant-baseline="central" class="donut-text">${pct}%</text>
  </svg>`;
}

function progressBar(pct) {
  return `<div class="bar"><div class="bar-fill" style="width:${pct}%"></div></div>`;
}

function renderOverview({ overall, bcSummaries }) {
  const cards = Object.entries(BC_MAPPING).map(([slug, meta]) => {
    const s = bcSummaries[slug] || { total: 0, 완료: 0, pct: 0 };
    return `<div class="bc-card" data-nav="bc-${slug}">
      <div class="bc-card-head">${meta.emoji} ${escapeHtml(meta.label)}</div>
      <div class="bc-card-stats">${s['완료']} / ${s.total} 완료</div>
      ${progressBar(s.pct)}
      <div class="bc-card-pct">${s.pct}%</div>
    </div>`;
  }).join('');

  return `<section id="overview" class="page active">
    <h1>BTS 개발 진척도</h1>
    <div class="overview-hero">
      ${donutSvg(overall.pct)}
      <div class="overview-numbers">
        <div class="big-num">${overall['완료']} / ${overall.total}</div>
        <div class="big-label">세부 기능 완료</div>
        <div class="status-grid">
          <div><span class="dot dot-done"></span> 완료 <b>${overall['완료']}</b></div>
          <div><span class="dot dot-prog"></span> 진행중 <b>${overall['진행중']}</b></div>
          <div><span class="dot dot-block"></span> 차단 <b>${overall['차단']}</b></div>
          <div><span class="dot dot-wait"></span> 대기 <b>${overall['미진행']}</b></div>
        </div>
      </div>
    </div>
    <h2>업무 영역별 진척</h2>
    <div class="bc-grid">${cards}</div>
  </section>`;
}

function renderBcDetail(slug, meta, frs, sortedTerms) {
  const stats = computeBcStats(frs);
  const frRows = frs.map(fr => {
    const sm = STATUS_META[fr.status];
    const annotated = annotateTerms(fr.description, sortedTerms);
    const steps = fr.steps.map(st => {
      const stMeta = STATUS_META[st.status];
      return `<li><span class="step-icon">${stMeta.icon}</span> ${escapeHtml(st.phase)} — ${annotateTerms(st.description, sortedTerms)}</li>`;
    }).join('');
    return `<details class="fr-item fr-${fr.status}">
      <summary>
        <span class="fr-status">${sm.icon}</span>
        <span class="fr-id">${escapeHtml(fr.id)}</span>
        <span class="fr-desc">${annotated}</span>
      </summary>
      <ol class="step-list">${steps}</ol>
    </details>`;
  }).join('');

  return `<section id="bc-${slug}" class="page">
    <h1>${meta.emoji} ${escapeHtml(meta.label)}</h1>
    <p class="bc-desc">${escapeHtml(meta.desc)}</p>
    <div class="bc-detail-stats">
      <div class="big-pct">${stats.pct}%</div>
      <div>${stats['완료']} / ${stats.total} 완료</div>
      ${progressBar(stats.pct)}
      <div class="status-grid">
        <div>✅ 완료 ${stats['완료']}</div>
        <div>🔄 진행중 ${stats['진행중']}</div>
        <div>⛔ 차단 ${stats['차단']}</div>
        <div>⬜ 대기 ${stats['미진행']}</div>
      </div>
    </div>
    <h2>세부 기능 (${stats.total}개)</h2>
    <div class="fr-list">${frRows}</div>
  </section>`;
}

function renderGlossary(terms) {
  const byCategory = new Map();
  for (const t of terms) {
    if (!byCategory.has(t.category)) byCategory.set(t.category, []);
    byCategory.get(t.category).push(t);
  }
  const categories = Array.from(byCategory.keys()).sort();
  const tabs = ['전체', ...categories].map(c =>
    `<button class="tab ${c === '전체' ? 'active' : ''}" data-cat="${escapeAttr(c)}">${escapeHtml(c)}</button>`
  ).join('');
  const items = terms.map(t =>
    `<div class="term-item" data-cat="${escapeAttr(t.category)}" data-search="${escapeAttr(t.term.toLowerCase() + ' ' + t.definition.toLowerCase())}">
      <div class="term-name">${escapeHtml(t.term)}</div>
      <div class="term-cat">${escapeHtml(t.category)}</div>
      <div class="term-def">${escapeHtml(t.definition)}</div>
    </div>`
  ).join('');

  return `<section id="glossary" class="page">
    <h1>📖 용어 사전</h1>
    <input type="search" id="term-search" placeholder="용어 검색 (한글/영문)..." />
    <div class="tab-bar">${tabs}</div>
    <div class="term-list">${items}</div>
  </section>`;
}

function renderSidebar() {
  const items = Object.entries(BC_MAPPING).map(([slug, meta]) =>
    `<li><a data-nav="bc-${slug}">${meta.emoji} ${escapeHtml(meta.label)}</a></li>`
  ).join('');
  return `<aside class="sidebar">
    <div class="sidebar-head">
      <div class="logo">🏢 BTS 진척도</div>
      <div class="updated">${new Date().toISOString().slice(0, 10)} 갱신</div>
    </div>
    <nav>
      <ul class="nav-top">
        <li><a data-nav="overview" class="active">🏠 전체 현황</a></li>
      </ul>
      <div class="nav-group">📋 업무 영역</div>
      <ul class="nav-bc">${items}</ul>
      <ul class="nav-top">
        <li><a data-nav="glossary">📖 용어 사전</a></li>
      </ul>
    </nav>
  </aside>`;
}

const CSS = `
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans KR", sans-serif; color: #1f2937; background: #f9fafb; line-height: 1.6; }
.layout { display: flex; min-height: 100vh; }
.sidebar { width: 260px; background: #fff; border-right: 1px solid #e5e7eb; padding: 20px 0; position: sticky; top: 0; height: 100vh; overflow-y: auto; }
.sidebar-head { padding: 0 20px 16px; border-bottom: 1px solid #f3f4f6; margin-bottom: 12px; }
.logo { font-size: 18px; font-weight: 700; }
.updated { font-size: 12px; color: #6b7280; margin-top: 4px; }
.sidebar nav ul { list-style: none; }
.sidebar nav a { display: block; padding: 8px 20px; cursor: pointer; color: #374151; font-size: 14px; }
.sidebar nav a:hover { background: #f3f4f6; }
.sidebar nav a.active { background: #eff6ff; color: #1d4ed8; font-weight: 600; border-right: 3px solid #1d4ed8; }
.nav-group { padding: 16px 20px 4px; font-size: 12px; color: #6b7280; font-weight: 600; text-transform: uppercase; }
.nav-bc a { padding-left: 32px; }
main { flex: 1; padding: 40px 48px; max-width: 1200px; }
.page { display: none; }
.page.active { display: block; }
h1 { font-size: 28px; margin-bottom: 16px; }
h2 { font-size: 20px; margin: 32px 0 16px; color: #374151; }
.overview-hero { display: flex; gap: 48px; align-items: center; background: #fff; padding: 32px; border-radius: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.05); margin-bottom: 24px; }
.donut { width: 220px; height: 220px; }
.donut-text { font-size: 56px; font-weight: 700; fill: #111827; }
.overview-numbers { flex: 1; }
.big-num { font-size: 36px; font-weight: 700; }
.big-label { color: #6b7280; margin-bottom: 16px; }
.status-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 16px; margin-top: 12px; }
.status-grid > div { font-size: 14px; }
.dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 4px; vertical-align: middle; }
.dot-done { background: #22c55e; }
.dot-prog { background: #3b82f6; }
.dot-block { background: #ef4444; }
.dot-wait { background: #9ca3af; }
.bc-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px; }
.bc-card { background: #fff; padding: 20px; border-radius: 10px; box-shadow: 0 1px 3px rgba(0,0,0,0.05); cursor: pointer; transition: transform 0.15s; }
.bc-card:hover { transform: translateY(-2px); box-shadow: 0 4px 12px rgba(0,0,0,0.1); }
.bc-card-head { font-size: 16px; font-weight: 600; margin-bottom: 8px; }
.bc-card-stats { font-size: 13px; color: #6b7280; margin-bottom: 8px; }
.bc-card-pct { font-size: 12px; color: #6b7280; text-align: right; margin-top: 4px; }
.bar { background: #e5e7eb; height: 8px; border-radius: 4px; overflow: hidden; }
.bar-fill { background: #22c55e; height: 100%; transition: width 0.3s; }
.bc-desc { color: #6b7280; margin-bottom: 16px; }
.bc-detail-stats { background: #fff; padding: 20px; border-radius: 10px; margin-bottom: 24px; }
.big-pct { font-size: 32px; font-weight: 700; color: #22c55e; }
.fr-list { display: flex; flex-direction: column; gap: 8px; }
.fr-item { background: #fff; border-radius: 8px; padding: 12px 16px; box-shadow: 0 1px 2px rgba(0,0,0,0.04); }
.fr-item summary { cursor: pointer; display: flex; gap: 12px; align-items: baseline; list-style: none; }
.fr-item summary::-webkit-details-marker { display: none; }
.fr-status { font-size: 16px; }
.fr-id { font-family: monospace; font-weight: 600; color: #6366f1; min-width: 100px; }
.fr-desc { flex: 1; }
.fr-완료 .fr-id { color: #22c55e; }
.fr-진행중 .fr-id { color: #3b82f6; }
.fr-차단 .fr-id { color: #ef4444; }
.step-list { list-style: none; margin: 12px 0 0 32px; display: flex; flex-direction: column; gap: 6px; font-size: 14px; color: #4b5563; }
.step-icon { margin-right: 4px; }
#term-search { width: 100%; padding: 10px 14px; font-size: 14px; border: 1px solid #d1d5db; border-radius: 8px; margin-bottom: 16px; }
.tab-bar { display: flex; gap: 4px; margin-bottom: 16px; flex-wrap: wrap; }
.tab { padding: 6px 12px; font-size: 13px; border: 1px solid #d1d5db; background: #fff; border-radius: 6px; cursor: pointer; }
.tab.active { background: #1d4ed8; color: #fff; border-color: #1d4ed8; }
.term-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 12px; }
.term-item { background: #fff; padding: 16px; border-radius: 8px; box-shadow: 0 1px 2px rgba(0,0,0,0.04); }
.term-item.hidden { display: none; }
.term-name { font-size: 16px; font-weight: 700; color: #1d4ed8; }
.term-cat { font-size: 11px; color: #9ca3af; text-transform: uppercase; margin: 4px 0; }
.term-def { font-size: 14px; color: #374151; }
.term { border-bottom: 1px dashed #94a3b8; cursor: help; position: relative; }
.term:hover::after { content: attr(data-def); position: absolute; left: 0; top: 100%; margin-top: 4px; background: #1f2937; color: #fff; padding: 8px 12px; border-radius: 6px; font-size: 12px; font-weight: 400; max-width: 360px; white-space: normal; z-index: 100; box-shadow: 0 4px 12px rgba(0,0,0,0.15); }
`;

const JS_RUNTIME = `
function navTo(id) {
  document.querySelectorAll('.page').forEach(p => p.classList.toggle('active', p.id === id));
  document.querySelectorAll('[data-nav]').forEach(a => a.classList.toggle('active', a.dataset.nav === id));
  window.scrollTo(0, 0);
}
document.addEventListener('click', (e) => {
  const el = e.target.closest('[data-nav]');
  if (el) { navTo(el.dataset.nav); }
});
const search = document.getElementById('term-search');
const tabBar = document.querySelector('.tab-bar');
let activeCat = '전체';
function filterTerms() {
  const q = (search.value || '').trim().toLowerCase();
  document.querySelectorAll('.term-item').forEach(item => {
    const matchCat = activeCat === '전체' || item.dataset.cat === activeCat;
    const matchSearch = !q || item.dataset.search.includes(q);
    item.classList.toggle('hidden', !(matchCat && matchSearch));
  });
}
if (search) search.addEventListener('input', filterTerms);
if (tabBar) tabBar.addEventListener('click', (e) => {
  const btn = e.target.closest('.tab');
  if (!btn) return;
  document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t === btn));
  activeCat = btn.dataset.cat;
  filterTerms();
});
`;

function renderHtml({ overall, bcSummaries, bcDetails, terms }) {
  const sortedTerms = buildTermLookup(terms);
  const detailSections = Object.entries(BC_MAPPING).map(([slug, meta]) => {
    const frs = bcDetails[slug] || [];
    return renderBcDetail(slug, meta, frs, sortedTerms);
  }).join('');

  return `<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>BTS 개발 진척도</title>
<style>${CSS}</style>
</head>
<body>
<div class="layout">
${renderSidebar()}
<main>
${renderOverview({ overall, bcSummaries })}
${detailSections}
${renderGlossary(terms)}
</main>
</div>
<script>${JS_RUNTIME}</script>
</body>
</html>`;
}

function main() {
  const frIndexContent = readFileSafe(FR_INDEX_PATH);
  const frIndex = parseFrIndex(frIndexContent);

  const planFiles = fs.readdirSync(PLAN_DIR).filter(f => f.endsWith('.md'));
  const detailedFrs = new Map();
  for (const f of planFiles) {
    const slug = f.replace(/\.md$/, '');
    if (!BC_MAPPING[slug]) continue;
    const content = readFileSafe(path.join(PLAN_DIR, f));
    const { frs } = parsePlanFile(content, slug);
    for (const fr of frs) detailedFrs.set(fr.id, fr);
  }

  const bcDetails = {};
  for (const slug of Object.keys(BC_MAPPING)) bcDetails[slug] = [];

  for (const [frId, meta] of frIndex.entries()) {
    const slug = meta.bcSlug;
    if (!BC_MAPPING[slug]) continue;
    const detail = detailedFrs.get(frId);
    const enriched = detail
      ? { ...detail, oneLiner: meta.oneLiner, priority: meta.priority }
      : { id: frId, description: meta.oneLiner, oneLiner: meta.oneLiner, priority: meta.priority, steps: [], status: '미진행' };
    bcDetails[slug].push(enriched);
  }

  const bcSummaries = {};
  const allFrs = [];
  for (const slug of Object.keys(BC_MAPPING)) {
    bcSummaries[slug] = computeBcStats(bcDetails[slug]);
    allFrs.push(...bcDetails[slug]);
  }

  const overall = computeOverallStats(allFrs);

  const glossaryContent = fs.existsSync(GLOSSARY_PATH) ? readFileSafe(GLOSSARY_PATH) : '';
  const yongeoContent = fs.existsSync(YONGEO_PATH) ? readFileSafe(YONGEO_PATH) : '';
  const terms = [...parseGlossary(glossaryContent), ...parseYongeo(yongeoContent)];

  const html = renderHtml({ overall, bcSummaries, bcDetails, terms });

  fs.mkdirSync(path.dirname(OUTPUT_PATH), { recursive: true });
  fs.writeFileSync(OUTPUT_PATH, html, 'utf-8');

  console.log(`✅ ${path.relative(REPO_ROOT, OUTPUT_PATH)} 생성 완료`);
  console.log(`   전체. ${overall['완료']} / ${overall.total} 완료 (${overall.pct}%)`);
  console.log(`   용어. ${terms.length}개`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main();
}
