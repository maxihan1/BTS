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
const TODOS_PATH = path.join(REPO_ROOT, 'TODOS.md');
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

/**
 * TODOS.md 섹션 헤딩의 상태 마커 — **단일 출처**.
 *
 * 파서(`parseTodos`) · 렌더(`renderTodos`) · 순수성 판별식
 * (`scripts/workflow/todos-resolved-section-purity.test.ts`)이 전부 여기서 파생한다.
 *
 * ★왜 상수 하나로 모았나. 2026-08-10 이전에는 판별식이 ✅·📌·⬜ **셋**을 허용하는데
 * 파서 정규식은 ✅·⬜ **둘**만 인식했다. `📌 보류` 섹션을 하나라도 만들면 그 헤딩과
 * 본문이 **앞 섹션의 본문으로 흡수**돼 집계에서 통째로 사라진다. 게다가 앞 섹션이 ✅ 인데
 * 흡수된 본문에 ⬜ 가 있으면 순수성 판별식이 **엉뚱한 섹션을 지목**한다.
 * 📌 섹션이 0건이라 잠복해 있었을 뿐이다 — 이 저장소의 `two-lists-never-check-each-other` 양식.
 *
 * 배열 **순서가 곧 화면 표시 순서**다. 안 한 것을 먼저, 끝난 것을 나중에 둔다.
 */
export const TODO_STATUSES = Object.freeze([
  { marker: '⬜', status: '미착수', heading: '아직 안 한 것' },
  { marker: '📌', status: '보류', heading: '보류 — 지금은 안 하기로 한 것' },
  { marker: '✅', status: '해소', heading: '해소된 것' },
]);

/** 마커 → 상태 이름. [TODO_STATUSES] 에서 파생한다 — 손으로 유지하는 두 번째 목록을 만들지 않는다. */
export const TODO_STATUS_BY_MARKER = Object.freeze(
  Object.fromEntries(TODO_STATUSES.map(s => [s.marker, s.status])),
);

/**
 * 카테고리 분류 + 두 줄 계약의 **대상 상태**.
 *
 * 「지금 남은 빚」이 이 화면의 목적이라 해소분은 제외한다. 해소 76건에 두 줄을 소급
 * 작성하는 비용 대비 가치가 없다. 판별식(`todos-plain-language-contract.test.ts`)이
 * 이 상수를 **import 해서** 쓴다 — 상태 목록을 두 번 적으면 그것도 갈라지는 두 목록이다.
 */
export const CONTRACTED_STATUSES = Object.freeze(['미착수', '보류']);

/**
 * 화면 카테고리 5종 — 개발자 말(영역)을 **사람 말**로 옮긴 이름과 한 줄 설명.
 *
 * 선언 **순서가 곧 화면 표시 순서**다. 사용자가 체감하는 것을 먼저, 안 보이는 것을 나중에 둔다.
 * 설명 줄이 없으면 비개발자는 그 묶음이 무엇인지 알 수 없다 — 분류만 있고 뜻이 없는 상태가 된다.
 */
export const CATEGORIES = Object.freeze({
  screen: {
    name: '화면에서 보이는 것',
    desc: '사용자가 눈으로 마주치는 부분. 고치면 바로 티가 난다.',
  },
  feature: {
    name: '기능 동작',
    desc: '이슈·가져오기 같은 기능이 정해진 규칙대로 도는가의 문제.',
  },
  guard: {
    name: '개발 안전장치',
    desc: '사용자에게는 안 보인다. 여기가 고장 나면 다른 고장을 못 잡는다.',
  },
  infra: {
    name: '빌드·배포 환경',
    desc: '코드를 검사하고 내보내는 기계 쪽 문제.',
  },
  docs: {
    name: '문서·규칙',
    desc: '적혀 있는 것과 실제가 다른 곳.',
  },
});

/**
 * 분류에 못 걸린 항목이 담기는 통.
 *
 * 실데이터에서는 **항상 비어 있어야** 하고 그것을 판별식이 강제한다. 그럼에도 두는 이유 —
 * 렌더가 분류 실패를 조용히 버리면 「분류가 틀렸다」와 「화면에서 사라졌다」가 같은 결과가 된다.
 * 실제로 이 통이 없던 초안이 제목에 영역 접두가 없는 항목을 통째로 증발시켰고,
 * `build-dashboard.test.mjs` 의 기존 단언이 그것을 잡았다(2026-08-18).
 */
export const UNCLASSIFIED = Object.freeze({
  name: '분류 없음',
  desc: '제목에 영역 접두(`<영역> — `)가 없어 자동 분류에 실패한 항목. 비어 있는 것이 정상이다.',
});

/**
 * `TODOS.md` 제목의 영역 접두 → 화면 카테고리.
 *
 * ★값을 `CATEGORIES` 에서 참조한다. 이름·설명을 여기 다시 적으면 같은 문자열이 두 벌이 되고,
 * 한쪽만 고쳐도 아무도 모른다 — 이 저장소가 이름 붙인 `two-lists-never-check-each-other` 다.
 * 키 집합과 실제 영역 집합의 **양방향 차집합 0** 은
 * `scripts/workflow/todos-plain-language-contract.test.ts` 가 강제한다.
 */
export const AREA_CATEGORIES = Object.freeze({
  'apps/web': CATEGORIES.screen,
  'issue-tracking': CATEGORIES.feature,
  'search-export-import': CATEGORIES.feature,
  'identity-access': CATEGORIES.feature,
  '도구': CATEGORIES.guard,
  '워크플로우': CATEGORIES.guard,
  '인프라': CATEGORIES.infra,
  '문서': CATEGORIES.docs,
});

/** 헤딩 마커 인식 정규식. 마커 목록을 문자열로 다시 적지 않고 상수에서 만든다. */
const TODO_HEADING_RE = new RegExp(
  `^##\\s+(${Object.keys(TODO_STATUS_BY_MARKER).join('|')})\\s+(.+?)\\s*$`,
);

/**
 * 코드펜스 여닫이 줄인가.
 *
 * ★이 파일은 fence 를 **두 곳**에서 본다 — `parseTodos` 의 H1 판정과 `mdToHtml` 의
 * 코드블록 렌더. 각자 적으면 한 파일 안에 두 목록이 생기고, 실제로 `parseTodos` 만
 * fence 를 몰라서 **코드 주석을 문서 제목으로 읽는** 결함이 있었다
 * (2026-08-18 실측 · `TODOS.md:455` bash 주석 · `:753` Kotlin 주석 — 두 항목의 본문이
 * 그 지점에서 잘려 화면에서 사라지고 있었다). 그래서 판정을 여기 한 벌만 둔다.
 *
 * 백틱만 본다. 물결 펜스(`~~~`)는 `TODOS.md` 에 0건이고 그 전제를
 * `build-dashboard.test.mjs` 가 단언으로 고정한다 — 쓰려면 여기를 먼저 고쳐야 red 가 풀린다.
 */
function isFenceLine(line) {
  return line.startsWith('```');
}

/**
 * `TODOS.md` 를 항목 목록으로 읽는다.
 *
 * 반환 항목의 `section` 은 **그 항목 바로 앞의 H1 제목**이다(없으면 `null`).
 * 파일이 카테고리 절로 재배열된 뒤로 H1 은 단순한 경계가 아니라 **분류 경계**이고,
 * 「이 항목이 어느 절에 있는가」를 알아야 파일 배치와 화면 배치의 일치를 강제할 수 있다
 * (`scripts/workflow/todos-structure-contract.test.ts`).
 *
 * ★절 추적을 별도 스캐너로 만들지 않고 여기 둔 이유. `# `·`## ` 를 읽는 코드가 늘 때마다
 * 코드펜스 판정을 잊는 것이 이 저장소의 반복 사고다 — #389 한 세션에서 다섯 번 밟았다.
 * 판정을 한 벌로 유지하는 것이 처방이라 이 루프가 유일한 헤딩 스캐너로 남는다.
 */
export function parseTodos(content) {
  // TODOS.md 는 `## <마커> <제목>` 단위 섹션의 나열이다. 마커가 상태, 그 뒤 전부가 본문.
  const items = [];
  let current = null;
  let inFence = false;
  // 현재 열려 있는 H1 절. 첫 H1 앞의 항목은 어느 절에도 안 속하므로 null 로 시작한다.
  let section = null;
  for (const line of content.split('\n')) {
    if (isFenceLine(line)) {
      inFence = !inFence;
      if (current) current.body.push(line);
      continue;
    }
    // ★헤딩 판정은 **펜스 밖에서만** 한다. 안에 있는 것은 코드·예시이지 문서 구조가 아니다.
    //   `## <마커>` 에도 게이트가 필요해진 것은 `TODOS.md` 머리의 **등재 서식 예시**가
    //   코드블록 안에서 `## ⬜ <영역> — …` 모양을 보여 주기 때문이다. 게이트가 없으면
    //   그 예시가 **유령 섹션**이 되어 집계와 순수성 판정을 동시에 오염시킨다.
    const m = inFence ? null : line.match(TODO_HEADING_RE);
    if (m) {
      if (current) items.push(current);
      current = { status: TODO_STATUS_BY_MARKER[m[1]], title: m[2].trim(), section, body: [] };
      continue;
    }
    // ★H1 은 항목을 닫는 **동시에** 새 절을 연다. 펜스 밖에서만 판정하는 것은 위와 같은 이유다.
    if (!inFence && line.startsWith('# ')) {
      if (current) items.push(current);
      current = null;
      section = line.slice(2).trim();
      continue;
    }
    if (current) current.body.push(line);
  }
  if (current) items.push(current);
  return items.map(t => ({ ...t, body: t.body.join('\n').trim() }));
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
      const def = escapeAttr(t.definition);
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
    <h1>BTS Dashboard</h1>
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
  // 중복 항목 정리: "FTS (Full Text Search)" 합본이 있으면 같은 개념의 단독 약어("FTS")·
  // 단독 풀이("Full Text Search") 항목은 목록에서 숨기고 합본 하나만 남긴다.
  // (본문 하이라이트용 분해 항목은 parseYongeo가 그대로 유지하므로 인라인 툴팁/검색은 영향 없음.)
  const acronymRe = /^([A-Z][A-Za-z0-9]+)\s*\(([^)]+)\)\s*$/;
  const acronymForms = new Set();
  for (const t of terms) {
    const m = t.term.match(acronymRe);
    if (m) {
      acronymForms.add(m[1].trim().toLowerCase());
      acronymForms.add(m[2].trim().toLowerCase());
    }
  }
  // 같은 이름이 초보자용 용어집(yongeo)에도 있으면 도메인 사전(glossary) 쪽 항목은 숨긴다.
  // 대시보드는 비개발자용이라 비유 설명(yongeo)을 남기는 편이 유용 (AQL/Epic/LexoRank 등).
  const yongeoNames = new Set(
    terms.filter(t => t.source === 'yongeo').map(t => t.term.toLowerCase())
  );
  const visibleTerms = terms.filter(t => {
    if (acronymRe.test(t.term)) return true;
    if (acronymForms.has(t.term.toLowerCase())) return false;
    if (t.source === 'glossary' && yongeoNames.has(t.term.toLowerCase())) return false;
    return true;
  });

  const byCategory = new Map();
  for (const t of visibleTerms) {
    if (!byCategory.has(t.category)) byCategory.set(t.category, []);
    byCategory.get(t.category).push(t);
  }
  const categories = Array.from(byCategory.keys()).sort();
  const tabs = ['전체', ...categories].map(c =>
    `<button class="tab ${c === '전체' ? 'active' : ''}" data-cat="${escapeAttr(c)}">${escapeHtml(c)}</button>`
  ).join('');
  const items = visibleTerms.map(t =>
    `<div class="term-item" data-cat="${escapeAttr(t.category)}" data-search="${escapeAttr(t.term.toLowerCase() + ' ' + t.definition.toLowerCase())}">
      <div class="term-name">${escapeHtml(t.term)}</div>
      <div class="term-cat">${escapeHtml(t.category)}</div>
      <div class="term-def">${escapeHtml(t.definition)}</div>
    </div>`
  ).join('');

  return `<section id="glossary" class="page">
    <h1>📖 Glossary</h1>
    <input type="search" id="term-search" placeholder="용어 검색 (한글/영문)..." />
    <div class="tab-bar">${tabs}</div>
    <div class="term-list">${items}</div>
  </section>`;
}

function inlineMd(s) {
  let h = escapeHtml(s);
  h = h.replace(/`([^`]+)`/g, '<code>$1</code>');
  h = h.replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>');
  h = h.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<i>$2</i>');
  h = h.replace(/\[\[([^\]]+)\]\]/g, '<span class="md-wikilink">$1</span>');
  h = h.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, '<a href="$2">$1</a>');
  return h;
}

function mdToHtml(md) {
  // TODOS.md 본문에 실제로 쓰이는 요소만 다룬다. 문단·목록·표·코드블록·인용·소제목·구분선.
  const lines = md.split('\n');
  const out = [];
  let para = [];
  const flushPara = () => {
    if (para.length) { out.push(`<p>${inlineMd(para.join(' '))}</p>`); para = []; }
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    if (isFenceLine(line)) {
      flushPara();
      const code = [];
      i++;
      while (i < lines.length && !isFenceLine(lines[i])) { code.push(lines[i]); i++; }
      out.push(`<pre class="md-code">${escapeHtml(code.join('\n'))}</pre>`);
      continue;
    }

    if (line.startsWith('|')) {
      flushPara();
      const rows = [];
      while (i < lines.length && lines[i].startsWith('|')) {
        const cells = lines[i].split('|').slice(1, -1).map(c => c.trim());
        if (!cells.every(c => /^:?-{2,}:?$/.test(c))) rows.push(cells);
        i++;
      }
      i--;
      if (rows.length) {
        const head = rows[0].map(c => `<th>${inlineMd(c)}</th>`).join('');
        const body = rows.slice(1)
          .map(r => `<tr>${r.map(c => `<td>${inlineMd(c)}</td>`).join('')}</tr>`).join('');
        out.push(`<table class="md-table"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`);
      }
      continue;
    }

    const listMatch = line.match(/^\s*(?:[-*]|\d+\.)\s+(.*)$/);
    if (listMatch) {
      flushPara();
      const items = [];
      while (i < lines.length) {
        const m = lines[i].match(/^\s*(?:[-*]|\d+\.)\s+(.*)$/);
        if (m) { items.push(m[1]); i++; continue; }
        // 마커 없이 들여쓴 줄은 직전 항목의 이어짐. 합치지 않으면 줄을 걸친 **볼드**가 쪼개진다.
        if (items.length && /^\s+\S/.test(lines[i])) {
          items[items.length - 1] += ' ' + lines[i].trim();
          i++;
          continue;
        }
        break;
      }
      i--;
      out.push(`<ul class="md-list">${items.map(t => `<li>${inlineMd(t)}</li>`).join('')}</ul>`);
      continue;
    }

    if (line.startsWith('>')) {
      flushPara();
      const quoted = [];
      while (i < lines.length && lines[i].startsWith('>')) {
        quoted.push(lines[i].replace(/^>\s?/, '').trim());
        i++;
      }
      i--;
      out.push(`<blockquote class="md-quote">${inlineMd(quoted.join(' '))}</blockquote>`);
      continue;
    }

    const headMatch = line.match(/^(#{3,6})\s+(.*)$/);
    if (headMatch) {
      flushPara();
      out.push(`<div class="md-head">${inlineMd(headMatch[2])}</div>`);
      continue;
    }

    if (/^---+$/.test(line.trim())) { flushPara(); continue; }

    if (line.trim() === '') { flushPara(); continue; }
    para.push(line.trim());
  }
  flushPara();
  return out.join('');
}

/**
 * 기술 부채 페이지를 그린다.
 *
 * ★그룹을 손으로 열거하지 않고 [TODO_STATUSES] 에서 만든다. 파서가 새 상태를 인식하는데
 * 렌더가 그 그룹을 안 그리면 결과는 「파싱은 됐지만 화면에서 사라짐」으로 **동일**하다 —
 * 파서만 고치는 것은 봉합의 절반이다.
 */
/**
 * 두 줄의 **서식 정본**. 렌더러와 판별식이 **같은 인식기**를 쓴다.
 *
 * ★export 하는 이유. 종전에는 판별식이 `body.includes('**쉬운 말.**')` 로 부분 문자열만 보고
 * 렌더러는 줄머리 앵커 + 같은 줄 내용을 요구했다. 그래서 `> **쉬운 말.** …`(인용) ·
 * `- **쉬운 말.** …`(목록) · 마커 다음 줄에 내용을 두는 세 형태에서 **CI 초록 + 화면 빈칸**이
 * 동시에 성립했다(2026-08-18 리뷰 2종이 각자 실측). 이 PR 의 존재 이유가 「빠뜨리면 CI 가
 * 막는다」인데 그 자리에 가짜 그린을 심는 셈이라, 서식 정의를 여기 한 벌만 둔다.
 */
export const PLAIN_LINE_RE = /^\*\*(쉬운 말|방치하면)\.\*\*\s*(.+)$/;

/**
 * 본문에서 「쉬운 말」·「방치하면」 두 줄을 **떼어 낸다**.
 *
 * ★떼어 내는 것이 핵심이다. 본문을 그대로 두고 두 줄을 접기 밖에 또 그리면 같은 문장이
 * 화면에 **두 번** 나온다(2026-08-18 리뷰 P1 — `renderTodos` 가 `mdToHtml(t.body)` 로
 * 본문 전체를 그리기 때문이다).
 */
function splitPlainLines(body) {
  const easy = [];
  const risk = [];
  const rest = [];
  let inFence = false;
  for (const line of body.split('\n')) {
    // ★펜스 안의 `**쉬운 말.**` 은 **예시**다. 태우지 않으면 코드블록에서 뜯겨 나와
    //   그 항목의 진짜 두 줄인 척 접기 밖에 렌더된다 — 이 PR 이 `parseTodos` 에서 고친
    //   바로 그 맹목을 새 코드에 다시 심은 자리였다(2026-08-18 리뷰 실측).
    if (isFenceLine(line)) { inFence = !inFence; rest.push(line); continue; }
    const m = inFence ? null : line.match(PLAIN_LINE_RE);
    if (m) { (m[1] === '쉬운 말' ? easy : risk).push(m[2].trim()); continue; }
    rest.push(line);
  }
  return { easy: easy.join(' '), risk: risk.join(' '), rest: rest.join('\n').trim() };
}

/**
 * 제목의 영역 접두(`<영역> — …`)를 뽑는다. 없으면 `null`.
 *
 * export 하는 이유는 `PLAIN_LINE_RE` 와 같다 — 판별식이 같은 로직을 다시 적으면 둘이 갈라져
 * 「판별식은 초록인데 화면은 `분류 없음`」이 된다.
 */
export function areaOfTitle(title) {
  const idx = title.indexOf('—');
  if (idx < 0) return null;
  const area = title.slice(0, idx).trim();
  return area.length > 0 ? area : null;
}

export function renderTodos(todos) {
  const groups = TODO_STATUSES.map(s => ({ ...s, items: todos.filter(t => t.status === s.status) }));
  const countOf = (status) => groups.find(g => g.status === status)?.items.length ?? 0;

  const renderItem = (t, icon) => {
    const { easy, risk, rest } = splitPlainLines(t.body);
    const plain = (easy || risk)
      ? `<div class="todo-plain">
        ${easy ? `<p class="todo-easy"><b>쉬운 말.</b> ${inlineMd(easy)}</p>` : ''}
        ${risk ? `<p class="todo-risk"><b>방치하면.</b> ${inlineMd(risk)}</p>` : ''}
      </div>`
      : '';
    return `<div class="todo-entry">
      <div class="todo-head"><span class="todo-icon">${icon}</span><span class="todo-title">${inlineMd(t.title)}</span></div>
      ${plain}
      <details class="todo-item">
        <summary aria-label="기술 상세 — ${escapeAttr(t.title)}">기술 상세 (안 봐도 됨)</summary>
        <div class="todo-body">${mdToHtml(rest)}</div>
      </details>
    </div>`;
  };

  /**
   * 미착수·보류는 **카테고리별로** 다시 묶는다.
   *
   * 순서는 [CATEGORIES] 선언 순서다 — 사용자가 체감하는 것을 먼저 둔다. 항목이 없는
   * 카테고리는 절을 만들지 않는다(빈 묶음이 화면에 남지 않게).
   * 해소분은 묶지 않는다 — 두 줄 계약 대상이 아니고(NFR N2), 76건을 분류해 봐야
   * 「지금 남은 빚」을 보려는 이 화면의 목적과 어긋난다.
   */
  const renderByCategory = (items, icon) => {
    const buckets = new Map(Object.values(CATEGORIES).map((c) => [c, []]));
    // ★분류에 못 걸린 항목이 **사라지지 않게** 마지막 통을 둔다.
    //   실데이터에서는 비어 있어야 하고 그것을 판별식이 강제한다. 그래도 폴백을 두는 이유는
    //   「분류 실패」와 「화면에서 소실」이 같은 결과가 되는 것을 막기 위해서다 —
    //   렌더가 조용히 버리면 판별식이 없는 다른 입력(합성·테스트·미래 서식)에서 그대로 증발한다.
    buckets.set(UNCLASSIFIED, []);
    for (const t of items) {
      const cat = AREA_CATEGORIES[areaOfTitle(t.title) ?? ''] ?? UNCLASSIFIED;
      buckets.get(cat).push(t);
    }
    return [...buckets.entries()]
      .filter(([, list]) => list.length > 0)
      .map(([cat, list]) =>
        `<h3 class="todo-cat">${escapeHtml(cat.name)} <span class="todo-cat-count">${list.length}건</span></h3>
        <p class="todo-cat-desc">${escapeHtml(cat.desc)}</p>
        <div class="todo-list">${list.map((t) => renderItem(t, icon)).join('')}</div>`,
      )
      .join('');
  };

  const renderGroup = (items, icon) => items.map((t) => renderItem(t, icon)).join('');

  return `<section id="todos" class="page">
    <h1>📝 기술 부채 (TODOS)</h1>
    <p class="bc-desc">
      지금 당장 고치지는 않기로 하고 <b>일부러 미뤄 둔 일감</b> 목록이다.
      「나중에 하자」를 머릿속이 아니라 문서에 남겨 두는 곳이라, 잊히거나 조용히 되돌려지는 걸 막는다.
      각 항목은 <b>쉬운 말</b>(무슨 상태인가)과 <b>방치하면</b>(안 고치면 뭐가 생기나) 두 줄을
      먼저 보여 준다 — 접지 않아도 읽힌다. 기술 내용은 그 아래로 접어 뒀다.
      원본은 저장소의 <code>TODOS.md</code> 한 파일이다.
    </p>
    <div class="bc-detail-stats">
      <span class="big-pct" style="color:#9ca3af;">${countOf('미착수')}</span>
      <span class="big-label" style="display:inline;"> 건 남음 · 보류 ${countOf('보류')}건 · 해소 ${countOf('해소')}건 · 전체 ${todos.length}건</span>
    </div>
    ${groups
      // 빈 그룹은 절(節)을 만들지 않는다 — 「📌 … 0건」 빈 제목만 남는 것을 피한다.
      // 건수는 위 요약 줄이 계속 알려 주므로 분류가 존재한다는 사실은 사라지지 않는다.
      .filter(g => g.items.length > 0)
      .map(g => `<h2>${g.marker} ${g.heading} — ${g.items.length}건</h2>
    ${CONTRACTED_STATUSES.includes(g.status)
      ? renderByCategory(g.items, g.marker)
      : `<div class="todo-list">${renderGroup(g.items, g.marker)}</div>`}`)
      .join('\n    ')}
  </section>`;
}

function renderHarness() {
  // 작업 흐름. 한마디 요청 → [계획 단계 ①~⑤] → 게이트1 → [구현 단계 ⑥~⑦] → 게이트2 → 자동 머지
  const arrow = '<div class="flow-arrow">▼</div>';
  const renderTimeline = (steps) => steps.map(s => `<div class="tl-step">
      <div class="tl-rail"><div class="tl-circle">${s.n}</div></div>
      <div class="tl-card">
        <div class="tl-title">${escapeHtml(s.title)}<span class="tl-skill">${escapeHtml(s.skill)}</span></div>
        <div class="tl-desc">${escapeHtml(s.desc)}</div>
      </div>
    </div>`).join('');
  const PLAN_STEPS = [
    { n: 1, title: '시작', skill: '/bts-start', desc: '요청을 종류별로 자동 분류하고, 본체를 건드리지 않을 별도 작업공간(worktree)과 검토 요청서(초안 PR)를 만든다.' },
    { n: 2, title: '도메인 점검', skill: '/bts-domain', desc: '쓸 용어·규칙·과거 결정을 확인한다. 옵시디언의 용어 사전(glossary)과 해당 영역 도메인 노트를 읽어 엉뚱한 방향을 막는다.' },
    { n: 3, title: '스펙(명세) 작성', skill: '/bts-spec', desc: '무엇을 만들지 — 사용자 시나리오·요구사항·예외 상황 — 를 글로 또렷하게 정리한다.' },
    { n: 4, title: '계획 세우기', skill: '/bts-plan', desc: '스펙을 잘게 쪼갠 작업 목록으로 바꾼다. 각 작업은 "테스트 먼저" 순서.' },
    { n: 5, title: '계획 리뷰', skill: '/bts-review-plan', desc: '엔지니어·사업·디자인·개발경험 등 여러 관점에서 계획을 미리 점검한다.' },
  ];
  const IMPL_STEPS = [
    { n: 6, title: '구현', skill: '/bts-impl', desc: '일꾼(에이전트)들이 테스트를 먼저 쓰고(빨강) → 통과시키고(초록) → 다듬는다. 겹치지 않는 작업은 동시에.' },
    { n: 7, title: '코드 리뷰', skill: '/bts-codereview', desc: '완성된 코드 전체를 한 번에 모아 규칙 위반·결함이 없는지 검수한다.' },
  ];
  const flowHtml = `
    <div class="flow-cap flow-start">
      <span class="cap-icon">🗣</span>
      <div><div class="cap-title">사용자 한마디 요청</div><div class="cap-desc">"이슈에 마감일 기능 넣어줘" 같은 평범한 말 한마디로 시작.</div></div>
    </div>
    ${arrow}
    <div class="flow-cap flow-read">
      <span class="cap-icon">📖</span>
      <div><div class="cap-title">선행 읽기 — 지식 노트부터 확인</div><div class="cap-desc">옵시디언에서 색인(_index) · 최근 작업 이력(history) · 과거 교훈(learnings)을 자동으로 먼저 읽고 시작한다.</div></div>
    </div>
    ${arrow}
    <div class="flow-phase flow-phase-blue">
      <div class="flow-phase-label">📐 1부 · 계획 단계</div>
      <div class="flow-phase-sub">무엇을, 어떻게 만들지 사람과 AI가 함께 정한다. 아직 코드는 안 짠다.</div>
      ${renderTimeline(PLAN_STEPS)}
    </div>
    ${arrow}
    <div class="flow-gate">
      <span class="gate-icon">🛑</span>
      <div><div class="gate-title">게이트 1 — 계획 승인</div><div class="gate-desc">여기서 멈춘다. 당신이 계획을 확인하고 승인해야 비로소 코드를 짜기 시작한다.</div></div>
    </div>
    ${arrow}
    <div class="flow-phase flow-phase-green">
      <div class="flow-phase-label">🔨 2부 · 구현 단계</div>
      <div class="flow-phase-sub">승인된 계획대로 실제 코드를 만들고 검수한다.</div>
      ${renderTimeline(IMPL_STEPS)}
    </div>
    ${arrow}
    <div class="flow-gate">
      <span class="gate-icon">🛑</span>
      <div><div class="gate-title">게이트 2 — 머지 승인</div><div class="gate-desc">다시 멈춘다. 당신이 "합쳐도 좋다"고 승인해야 완료된다.</div></div>
    </div>
    ${arrow}
    <div class="flow-cap flow-finish">
      <span class="cap-icon">✅</span>
      <div><div class="cap-title">자동 머지 + 정리 + 지식 기록</div><div class="cap-desc">본체(main)에 합치고 작업공간을 지운다. 그리고 옵시디언에 작업 이력(history)·새 결정(decisions)·교훈(learnings)을 다시 정리해 둔다 — 다음 작업의 선행 읽기 재료가 된다.</div></div>
    </div>`;

  // Git 용어 풀이 (비개발자용 비유)
  const GIT_TERMS = [
    { icon: '🔁', title: 'Git', desc: '코드의 모든 변경을 시점별로 저장하는 "무한 되돌리기" 장치. 언제든 과거 상태로 돌아갈 수 있다.' },
    { icon: '💾', title: '커밋 (commit)', desc: '변경 한 묶음을 저장하는 게임의 "세이브 포인트". 메시지로 무엇을 바꿨는지 남긴다.' },
    { icon: '🏛', title: 'main (본체)', desc: '모두가 공유하는 진짜 최신본. 검증 안 된 변경을 여기에 직접 올리지 않는다.' },
    { icon: '🧰', title: 'worktree (작업공간)', desc: '본체를 복사해 따로 펼친 "작업용 책상". 여기서 마음껏 고쳐도 본체는 안전하다.' },
    { icon: '📨', title: 'PR (Pull Request)', desc: '"이 변경을 본체에 합쳐도 될까요?" 하고 검토를 요청하는 결재 서류.' },
    { icon: '🔀', title: '머지 (merge)', desc: '검토와 승인을 통과한 변경을 본체(main)에 합치는 것.' },
  ];
  const gitTermsHtml = GIT_TERMS.map(t => `<div class="role-card">
    <div class="role-card-head">${t.icon} ${escapeHtml(t.title)}</div>
    <div class="role-card-desc">${escapeHtml(t.desc)}</div>
  </div>`).join('');

  // 작업공간 생애주기 (worktree 한 칸의 일생)
  const GIT_LANE = [
    { icon: '📋', title: '작업 시작', desc: '요청이 들어오면 본체와 분리된 작업공간 .worktrees/<이름> 을 새로 만든다.' },
    { icon: '💾', title: '첫 커밋 → 초안 PR', desc: '첫 변경을 저장하는 순간 "초안 PR(검토 요청서)"이 자동으로 열린다.' },
    { icon: '🧪', title: 'test: → 💻 feat:', desc: '테스트 커밋이 기능 커밋보다 반드시 먼저 와야 한다 (컴퓨터가 기록을 검사).' },
    { icon: '🛑', title: '게이트 통과', desc: '코드 리뷰가 끝나고 당신이 머지를 승인하면 다음으로 간다.' },
    { icon: '🔀', title: '머지 + 정리', desc: '본체에 합친 뒤 작업공간을 자동으로 삭제하고 지식 노트를 갱신한다.' },
  ];
  const gitLaneHtml = GIT_LANE.map(s => `<div class="git-stage">
    <div class="git-stage-icon">${s.icon}</div>
    <div class="git-stage-title">${escapeHtml(s.title)}</div>
    <div class="git-stage-desc">${escapeHtml(s.desc)}</div>
  </div>`).join('');

  // 작업 상태가 "어디를 보면 알 수 있는지"
  const STATUS_ROWS = [
    ['진행 중', '체크아웃된 작업공간(worktree) + 열려 있는 PR'],
    ['리뷰 대기', '초안이 해제된 열린 PR'],
    ['완료(머지 후)', 'docs/completed-work-log.md + 지식 노트(history.md)에 자동 기록'],
  ];
  const statusRowsHtml = STATUS_ROWS.map(([k, v]) =>
    `<tr><td><b>${escapeHtml(k)}</b></td><td>${escapeHtml(v)}</td></tr>`
  ).join('');

  // 6 일꾼 (서브에이전트)
  const ROLES = [
    { icon: '🔐', name: '보안', sub: 'security-engineer', desc: '로그인·2단계 인증·SSO·권한 검사처럼 잘못되면 폭발 반경이 큰 영역을 전담한다.' },
    { icon: '⚙️', name: '백엔드', sub: 'backend-engineer', desc: '이슈·워크플로우·자동화 등 화면 뒤에서 도는 서버 로직 일반 (Kotlin/Spring).' },
    { icon: '🎨', name: '프론트엔드', sub: 'frontend-engineer', desc: '눈에 보이는 화면 부분 (React). 디자인 스펙을 실제 동작하는 화면으로 구현한다.' },
    { icon: '✏️', name: '디자이너', sub: 'designer', desc: '새 화면의 레이아웃·색·여백을 정하고 목업을 만든다. 코드 구현은 하지 않는다.' },
    { icon: '🗄', name: 'DB', sub: 'db-engineer', desc: '데이터를 담는 표(테이블) 구조와 그 변경 이력(마이그레이션)을 관리한다.' },
    { icon: '🧪', name: 'QA', sub: 'qa-engineer', desc: '사용자 흐름 전체를 자동으로 눌러보는 E2E 테스트와 테스트 환경을 담당한다.' },
  ];
  const rolesHtml = ROLES.map(r => `<div class="role-card">
    <div class="role-card-head">${r.icon} ${escapeHtml(r.name)} <span class="role-card-sub">${escapeHtml(r.sub)}</span></div>
    <div class="role-card-desc">${escapeHtml(r.desc)}</div>
  </div>`).join('');

  // 요청 자동 분류 (classify-task)
  const CLASSIFY = [
    ['인증', 'auth', '로그인·2단계 인증·SSO·권한', '보안'],
    ['백엔드', 'backend', '이슈·워크플로우·자동화 등 서버 로직', '백엔드'],
    ['화면', 'ui', '페이지·컴포넌트 등 보이는 부분', '프론트엔드'],
    ['디자인', 'design', '새 컴포넌트·목업·시안', '디자이너 → 프론트엔드'],
    ['데이터', 'migration', '표 구조 변경·DB 마이그레이션', 'DB'],
    ['연결 통로', 'api', '서버와 화면을 잇는 API', '백엔드 (+디자이너 검토)'],
    ['테스트', 'qa', 'E2E·테스트 인프라', 'QA'],
    ['버그 수정', 'bugfix', 'fix: 로 시작하는 작업', '영향 영역에 따라'],
    ['잡무', 'chore', '문서·설정·정리', '자동'],
    ['신규 기능', 'feature', '"만들어줘" + 새/여러 영역', '영향 영역에 따라'],
  ];
  const classifyRowsHtml = CLASSIFY.map(([ko, tag, what, who]) =>
    `<tr><td><b>${escapeHtml(ko)}</b> <span class="tag">${escapeHtml(tag)}</span></td><td>${escapeHtml(what)}</td><td>${escapeHtml(who)}</td></tr>`
  ).join('');

  // 핵심 규칙 4개
  const RULES = [
    { icon: '🧪', title: '테스트 먼저 (TDD)', desc: '코드보다 테스트를 먼저 쓴다. 컴퓨터가 git 기록을 보고 "test: 커밋이 feat: 커밋보다 앞에 있는지" 검사해, 어기면 작업을 막는다.', why: '정답지(테스트)를 먼저 만들고 나서 문제(코드)를 푸는 셈. 만든 게 정말 의도대로 도는지 항상 증명된다.' },
    { icon: '🧰', title: '작업공간 격리 (worktree)', desc: '모든 코드 편집은 별도 작업공간 안에서만 일어난다. 본체(main)는 직접 건드릴 수 없다.', why: '공용 원본은 금고에 두고 사본으로만 작업하는 것. 실수해도 본체는 멀쩡하니 언제든 되돌릴 수 있다.' },
    { icon: '🧩', title: '영역 격리 (BC)', desc: '한 번의 작업 = 한 업무 영역만. 다른 영역은 직접 부르지 않고 "메시지"로만 알린다.', why: '부서끼리 남의 책상을 직접 뒤지지 않고 쪽지(이벤트)를 보내는 것. 한 곳을 고쳐도 다른 곳이 안 깨진다.' },
    { icon: '👁', title: '한 번에 검수 (PR 단위 리뷰)', desc: '작업마다 잘게 검수하지 않고, 완성된 변경 전체를 PR 단위로 한 번 모아서 검수한다.', why: '조각조각 보다 완성본 전체를 보면 앞뒤가 맞는지 한눈에 판단할 수 있다.' },
  ];
  const rulesHtml = RULES.map(r => `<div class="rule-card">
    <div class="rule-card-head">${r.icon} ${escapeHtml(r.title)}</div>
    <div class="rule-card-desc">${escapeHtml(r.desc)}</div>
    <div class="rule-why"><b>왜.</b> ${escapeHtml(r.why)}</div>
  </div>`).join('');

  // 옵시디언 지식 순환. 읽기(시작 전) → 읽기(도메인) → 쓰기(머지 후) → 다음 작업이 다시 읽음
  const kloopHtml = `
    <div class="kloop">
      <div class="kloop-card kloop-read">
        <div class="kloop-when">자동 · 작업 시작 전</div>
        <div class="kloop-title">📥 1. 먼저 읽는다</div>
        <ul>
          <li><code>_index</code> — 전체 노트 색인</li>
          <li><code>history</code> — 최근 작업 이력(끝 50줄)</li>
          <li><code>learnings</code> — 과거 사고·교훈(회귀 방지)</li>
        </ul>
      </div>
      <div class="kloop-arrow">→</div>
      <div class="kloop-card kloop-read">
        <div class="kloop-when">②도메인 단계</div>
        <div class="kloop-title">📖 2. 더 읽는다</div>
        <ul>
          <li><code>glossary</code> — 도메인 용어 사전</li>
          <li><code>domain/&lt;영역&gt;</code> — 해당 업무 영역 노트</li>
        </ul>
      </div>
      <div class="kloop-arrow">→</div>
      <div class="kloop-card kloop-write">
        <div class="kloop-when">머지 직후</div>
        <div class="kloop-title">📤 3. 다시 정리한다</div>
        <ul>
          <li><code>history</code>에 이번 작업 1줄 추가</li>
          <li>새 결정·계획을 <code>decisions</code>로 복사</li>
          <li><code>learning:</code> 라벨 시 <code>learnings</code>에 교훈 추가</li>
        </ul>
      </div>
    </div>
    <div class="kloop-back">⟲ 다음 작업은 이렇게 정리된 기록을 다시 "1. 먼저 읽는다"로 흡수한다 — 그래서 순환이다</div>
    <div class="callout"><b>한 방향으로만 흐른다.</b> 정리는 항상 <b>코드 저장소 → 옵시디언</b> 방향이다. 옵시디언은 Maxi의 사고 공간이라, 거기 적은 메모가 코드 영역으로 자동 반영되지는 않는다.</div>`;

  return `<section id="harness" class="page">
    <h1>🛠 이 프로젝트는 어떻게 만들어지나</h1>
    <div class="harness-intro">
      BTS는 <b>Maxi 한 사람</b>과 <b>Claude Code(AI 개발 도우미)</b>가 함께 만든다.
      사람이 코드를 일일이 지시하는 대신, <b>하네스</b>라 부르는 정해진 작업 틀을 따른다.
      하네스(harness)란 작업자에게 채우는 "안전벨트·작업 틀"로, AI가 제멋대로 가지 않도록 정해둔 작업 순서와 안전장치 묶음이다.
      요청을 받아 → 계획하고 → 만들고 → 검수하는 과정을 항상 같은 순서로 진행하고, 중요한 길목 두 곳에서 반드시 당신(사람)의 승인을 받는다.
    </div>
    <p class="harness-note">아래는 그 전체 그림이다. 스크롤하며 순서대로 읽으면 된다.</p>

    <h2>1. 작업 흐름 — 한마디 요청에서 완성까지</h2>
    <div class="flowchart">${flowHtml}</div>

    <h2>2. 옵시디언 지식 순환 — 정리하고, 다시 읽고 시작한다</h2>
    <p class="harness-note">매 작업은 빈 종이에서 시작하지 않는다. 옵시디언(Maxi의 메모 보관소)에 쌓인 지식을 먼저 읽고, 끝나면 새로 배운 걸 다시 정리해 둔다.</p>
    ${kloopHtml}

    <h2>3. Git과 작업공간(worktree) — 안전하게 고치는 법</h2>
    <p class="harness-note">코드를 다루는 토대다. 먼저 용어부터 한 줄씩.</p>
    <div class="role-grid">${gitTermsHtml}</div>
    <div class="callout">
      <b>왜 이렇게 하나.</b> 본체(main)에는 항상 검증된 코드만 둔다. 실험은 별도 책상(worktree)에서 하고,
      합치기 전 반드시 검토(PR)와 당신의 승인(게이트)을 거친다. 잘못돼도 본체는 멀쩡하니 언제든 되돌릴 수 있다.
    </div>
    <h2 style="font-size:16px; margin-top:24px;">작업공간 하나의 일생</h2>
    <div class="git-lane">${gitLaneHtml}</div>
    <h2 style="font-size:16px; margin-top:24px;">지금 무슨 작업이 어느 단계인지 어디서 보나</h2>
    <table class="h-table"><tbody>${statusRowsHtml}</tbody></table>
    <div class="callout">
      <b>커밋 메시지 약속.</b> 변경마다 종류를 앞에 붙인다.
      <b>feat:</b> 기능 · <b>test:</b> 테스트 · <b>fix:</b> 버그 수정 · <b>chore:</b> 잡무 · <b>docs:</b> 문서.
      덕분에 기록만 봐도 무엇을 왜 바꿨는지 알 수 있고, 한 번에 하나의 변경만 담아 언제든 되돌리기 쉽다.
    </div>

    <h2>4. 일꾼들 — 6명의 전문 에이전트</h2>
    <p class="harness-note">구현 단계에서 작업 종류에 맞는 전문 일꾼(AI 서브에이전트)이 투입된다.</p>
    <div class="role-grid">${rolesHtml}</div>

    <h2>5. 요청이 자동으로 분류된다</h2>
    <p class="harness-note">시작 단계에서 요청 내용을 보고 종류를 자동으로 가려, 알맞은 일꾼과 검토 방식을 정한다.</p>
    <table class="h-table">
      <thead><tr><th>작업 종류</th><th>어떤 요청인가</th><th>담당 일꾼</th></tr></thead>
      <tbody>${classifyRowsHtml}</tbody>
    </table>

    <h2>6. 절대 지키는 핵심 규칙</h2>
    <div class="rule-grid">${rulesHtml}</div>
  </section>`;
}

function renderSidebar() {
  const items = Object.entries(BC_MAPPING).map(([slug, meta]) =>
    `<li><a data-nav="bc-${slug}">${meta.emoji} ${escapeHtml(meta.label)}</a></li>`
  ).join('');
  return `<aside class="sidebar">
    <div class="sidebar-head">
      <div class="logo">🏢 BTS Dashboard</div>
      <div class="updated">${new Date().toISOString().slice(0, 10)} 갱신</div>
    </div>
    <nav>
      <ul class="nav-top">
        <li><a data-nav="overview" class="active">🏠 전체 현황</a></li>
        <li><a data-nav="harness">🛠 Project process</a></li>
      </ul>
      <div class="nav-group">📋 Project progress</div>
      <ul class="nav-bc">${items}</ul>
      <ul class="nav-top">
        <li><a data-nav="todos">📝 기술 부채</a></li>
        <li><a data-nav="glossary">📖 glossary</a></li>
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
.nav-group { padding: 16px 20px 6px; font-size: 12px; color: #6b7280; font-weight: 600; text-transform: uppercase; }
.nav-bc { margin: 2px 0 2px 24px; border-left: 2px solid #e5e7eb; }
.nav-bc a { padding: 6px 20px 6px 14px; font-size: 13px; color: #6b7280; }
.nav-bc a:hover { color: #374151; }
.nav-bc a.active { color: #1d4ed8; }
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
.term:hover::after { content: attr(data-def); position: absolute; left: 0; top: 100%; margin-top: 4px; background: #1f2937; color: #fff; padding: 10px 14px; border-radius: 6px; font-size: 12px; font-weight: 400; line-height: 1.65; width: max-content; max-width: 480px; white-space: normal; z-index: 100; box-shadow: 0 4px 12px rgba(0,0,0,0.15); }
/* ── 작업 방식(하네스) 페이지 ── */
.harness-intro { background: #fff; padding: 20px 24px; border-radius: 10px; box-shadow: 0 1px 3px rgba(0,0,0,0.05); margin-bottom: 8px; color: #374151; font-size: 15px; }
.harness-intro b { color: #1d4ed8; }
.harness-note { font-size: 13px; color: #6b7280; margin: 4px 0 24px; }
.flowchart { max-width: 680px; }
.flow-arrow { text-align: center; color: #9ca3af; font-size: 20px; line-height: 1; margin: 7px 0; }
.flow-cap { display: flex; align-items: center; gap: 14px; border-radius: 12px; padding: 14px 20px; }
.flow-cap .cap-icon { font-size: 26px; line-height: 1; }
.flow-cap .cap-title { font-weight: 700; font-size: 15px; }
.flow-cap .cap-desc { font-size: 13px; margin-top: 2px; opacity: 0.85; }
.flow-start { background: #eef2ff; color: #3730a3; border: 1px solid #e0e7ff; }
.flow-finish { background: #ecfdf5; color: #065f46; border: 1px solid #a7f3d0; }
.flow-phase { border: 1px solid #e5e7eb; border-radius: 14px; padding: 14px 16px 4px; }
.flow-phase-label { font-weight: 700; font-size: 14px; }
.flow-phase-sub { font-size: 12px; color: #6b7280; margin: 2px 0 14px; }
.flow-phase-blue { background: #f5f8ff; border-color: #dbe4ff; }
.flow-phase-blue .flow-phase-label { color: #1d4ed8; }
.flow-phase-green { background: #f2fbf5; border-color: #cdeede; }
.flow-phase-green .flow-phase-label { color: #15803d; }
.tl-step { display: flex; gap: 14px; position: relative; padding-bottom: 14px; }
.tl-rail { flex: 0 0 36px; display: flex; justify-content: center; position: relative; }
.tl-circle { width: 36px; height: 36px; border-radius: 50%; color: #fff; font-weight: 700; display: flex; align-items: center; justify-content: center; font-size: 15px; z-index: 1; box-shadow: 0 1px 3px rgba(0,0,0,0.15); }
.flow-phase-blue .tl-circle { background: #2563eb; }
.flow-phase-green .tl-circle { background: #16a34a; }
.tl-step:not(:last-child) .tl-rail::after { content: ''; position: absolute; top: 36px; bottom: -4px; left: 50%; width: 3px; transform: translateX(-50%); }
.flow-phase-blue .tl-step:not(:last-child) .tl-rail::after { background: #bfdbfe; }
.flow-phase-green .tl-step:not(:last-child) .tl-rail::after { background: #bbf7d0; }
.tl-card { flex: 1; background: #fff; border-radius: 10px; padding: 11px 15px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.tl-title { font-weight: 600; font-size: 14px; }
.tl-skill { font-family: monospace; font-size: 11px; color: #9ca3af; margin-left: 6px; }
.tl-desc { font-size: 13px; color: #4b5563; margin-top: 3px; line-height: 1.55; }
.flow-gate { display: flex; align-items: center; gap: 14px; background: #fef2f2; border: 1px solid #fecaca; border-left: 5px solid #ef4444; border-radius: 12px; padding: 14px 18px; }
.flow-gate .gate-icon { font-size: 26px; line-height: 1; }
.flow-gate .gate-title { font-weight: 700; color: #b91c1c; font-size: 15px; }
.flow-gate .gate-desc { color: #991b1b; font-size: 13px; margin-top: 2px; }
.flow-read { background: #f5f3ff; color: #5b21b6; border: 1px solid #ddd6fe; }
/* 옵시디언 지식 순환 */
.kloop { display: flex; align-items: stretch; gap: 8px; flex-wrap: wrap; }
.kloop-card { flex: 1 1 220px; background: #fff; border-radius: 10px; padding: 14px 16px; box-shadow: 0 1px 3px rgba(0,0,0,0.05); border-top: 3px solid #d1d5db; }
.kloop-read { border-top-color: #7c3aed; }
.kloop-write { border-top-color: #16a34a; }
.kloop-arrow { display: flex; align-items: center; color: #9ca3af; font-size: 20px; }
.kloop-title { font-weight: 700; font-size: 14px; margin-bottom: 4px; }
.kloop-read .kloop-title { color: #6d28d9; }
.kloop-write .kloop-title { color: #15803d; }
.kloop-when { font-size: 11px; color: #9ca3af; text-transform: uppercase; letter-spacing: 0.03em; margin-bottom: 8px; }
.kloop-card ul { margin: 0; padding-left: 16px; font-size: 13px; color: #4b5563; line-height: 1.75; }
.kloop-card code { background: #f3f4f6; padding: 1px 5px; border-radius: 4px; font-size: 12px; }
.kloop-back { text-align: center; background: #f5f3ff; color: #5b21b6; border: 1px dashed #c4b5fd; border-radius: 8px; padding: 10px 14px; font-size: 13px; font-weight: 600; margin: 12px 0; }
.callout { background: #eff6ff; border-left: 4px solid #1d4ed8; border-radius: 8px; padding: 14px 18px; font-size: 14px; color: #1e3a5f; margin: 16px 0; line-height: 1.7; }
.git-lane { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.git-stage { flex: 1 1 160px; background: #fff; border-radius: 10px; padding: 14px; box-shadow: 0 1px 3px rgba(0,0,0,0.05); }
.git-stage-icon { font-size: 22px; }
.git-stage-title { font-weight: 600; font-size: 14px; margin: 6px 0 4px; }
.git-stage-desc { font-size: 13px; color: #6b7280; }
.role-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
.role-card { background: #fff; padding: 16px; border-radius: 10px; box-shadow: 0 1px 3px rgba(0,0,0,0.05); }
.role-card-head { font-weight: 600; font-size: 15px; }
.role-card-sub { font-family: monospace; font-size: 11px; color: #9ca3af; }
.role-card-desc { font-size: 13px; color: #4b5563; margin-top: 6px; }
.h-table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 10px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.05); font-size: 13px; }
.h-table th, .h-table td { text-align: left; padding: 10px 14px; border-bottom: 1px solid #f3f4f6; vertical-align: top; }
.h-table th { background: #f9fafb; color: #6b7280; font-weight: 600; }
.h-table tr:last-child td { border-bottom: none; }
.h-table .tag { font-family: monospace; font-size: 12px; color: #6366f1; font-weight: 600; }
.rule-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(290px, 1fr)); gap: 12px; }
.rule-card { background: #fff; padding: 18px; border-radius: 10px; box-shadow: 0 1px 3px rgba(0,0,0,0.05); }
.rule-card-head { font-weight: 600; font-size: 15px; margin-bottom: 8px; }
.rule-card-desc { font-size: 13px; color: #4b5563; line-height: 1.65; }
.rule-why { font-size: 12px; color: #6b7280; margin-top: 8px; }
.rule-why b { color: #4b5563; }
.todo-list { display: flex; flex-direction: column; gap: 8px; margin-bottom: 8px; }
/* 카테고리 소분류 — 미착수·보류 그룹 안에서만 쓴다. 사람 말 이름 + 한 줄 설명. */
.todo-cat { font-size: 15px; margin: 22px 0 2px; display: flex; gap: 8px; align-items: baseline; }
.todo-cat-count { font-size: 12px; font-weight: 400; color: #6b7280; }
.todo-cat-desc { font-size: 12px; color: #6b7280; margin: 0 0 10px; }
/* 항목 한 벌 = 제목 + 두 줄(항상 보임) + 접힌 기술 상세 */
.todo-entry { background: #fff; border-radius: 8px; padding: 12px 16px; box-shadow: 0 1px 2px rgba(0,0,0,0.04); }
.todo-head { display: flex; gap: 10px; align-items: baseline; }
.todo-plain { margin: 8px 0 2px; }
.todo-easy, .todo-risk { font-size: 13px; line-height: 1.65; margin: 0 0 4px; color: #374151; }
.todo-easy b { color: #111827; }
.todo-risk { color: #6b7280; }
.todo-risk b { color: #b45309; }
.todo-item { border-radius: 8px; }
.todo-item summary { cursor: pointer; font-size: 12px; color: #6b7280; display: flex; gap: 10px; align-items: baseline; list-style: none; }
.todo-item summary::-webkit-details-marker { display: none; }
.todo-icon { font-size: 15px; }
.todo-title { flex: 1; font-size: 14px; }
.todo-body { margin: 12px 0 4px; padding-top: 12px; border-top: 1px solid #f3f4f6; font-size: 13px; color: #374151; }
.todo-body p { margin-bottom: 10px; }
.todo-body code { background: #f3f4f6; border-radius: 4px; padding: 1px 5px; font-size: 12px; }
.todo-body a { color: #1d4ed8; }
.md-head { font-weight: 600; font-size: 14px; margin: 16px 0 8px; color: #374151; }
.md-list { margin: 0 0 10px 20px; }
.md-list li { margin-bottom: 4px; }
.md-code { background: #1f2937; color: #e5e7eb; padding: 12px 14px; border-radius: 8px; font-size: 12px; overflow-x: auto; margin-bottom: 10px; white-space: pre; }
.md-table { width: 100%; border-collapse: collapse; margin-bottom: 12px; font-size: 12px; }
.md-table th, .md-table td { text-align: left; padding: 7px 10px; border-bottom: 1px solid #f3f4f6; vertical-align: top; }
.md-table th { background: #f9fafb; color: #6b7280; font-weight: 600; }
.md-quote { border-left: 3px solid #fbbf24; background: #fffbeb; padding: 10px 14px; margin-bottom: 10px; border-radius: 0 6px 6px 0; }
.md-wikilink { color: #6366f1; font-family: monospace; font-size: 12px; }
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

function renderHtml({ overall, bcSummaries, bcDetails, terms, todos }) {
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
<title>BTS Dashboard</title>
<style>${CSS}</style>
</head>
<body>
<div class="layout">
${renderSidebar()}
<main>
${renderOverview({ overall, bcSummaries })}
${renderHarness()}
${detailSections}
${renderTodos(todos)}
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

  const todos = parseTodos(readFileSafe(TODOS_PATH));

  const html = renderHtml({ overall, bcSummaries, bcDetails, terms, todos });

  fs.mkdirSync(path.dirname(OUTPUT_PATH), { recursive: true });
  fs.writeFileSync(OUTPUT_PATH, html, 'utf-8');

  console.log(`✅ ${path.relative(REPO_ROOT, OUTPUT_PATH)} 생성 완료`);
  console.log(`   전체. ${overall['완료']} / ${overall.total} 완료 (${overall.pct}%)`);
  console.log(`   용어. ${terms.length}개`);
  console.log(`   기술 부채. ${todos.filter(t => t.status === '미착수').length} 미착수 / ${todos.length}건`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main();
}
