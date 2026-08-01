// 인덱스 마크다운을 만든다 — 라우터(MEMORY.md) · 카테고리별 인덱스 · 고아/깨진링크 감사
import { AUTOGEN_HEADER, CATEGORIES } from './config.mjs';

/** hook 우선, 없으면 description. 인덱스 한 줄은 짧아야 한다. */
function oneLiner(e, max = 90) {
  const s = (e.hook && e.hook.trim()) || e.description || '';
  return s.length > max ? `${s.slice(0, max - 1)}…` : s;
}

export function renderRouter(entries, counts, frHistoryCount) {
  const crit = entries.filter((e) => e.priority === 'critical');
  const lines = [AUTOGEN_HEADER, ''];

  lines.push(`## ★ 항상 지킬 것 (${crit.length}건)`, '');
  for (const e of crit) lines.push(`- ${oneLiner(e, 70)} [[${e.slug}]]`);
  lines.push('');

  lines.push('## 상황별 인덱스 — 필요한 것만 열어라', '');
  lines.push('| 지금 하는 일 | 열 파일 | 건수 |');
  lines.push('|---|---|---|');
  for (const c of CATEGORIES) {
    const n = counts[c.key] || 0;
    if (n === 0) continue;
    lines.push(`| ${c.label} | memory/index/${c.key}.md | ${n} |`);
  }
  if (frHistoryCount > 0) {
    lines.push(`| FR 완료 이력 | docs/INDEX-fr.md (memory 열) | ${frHistoryCount} |`);
  }
  lines.push('');
  return lines.join('\n');
}

export function renderCategoryIndex(categoryKey, entries) {
  const meta = CATEGORIES.find((c) => c.key === categoryKey);
  const lines = [
    AUTOGEN_HEADER,
    '',
    `# ${meta ? meta.label : categoryKey} (${entries.length}건)`,
    '',
    '> 라우터. [MEMORY.md](../MEMORY.md)',
    '',
  ];
  for (const e of [...entries].sort((a, b) => a.slug.localeCompare(b.slug))) {
    const star = e.priority === 'critical' ? '★ ' : '';
    lines.push(`- ${star}[[${e.slug}]] — ${oneLiner(e)}`);
  }
  lines.push('');
  return lines.join('\n');
}

function docLink(d) {
  return `[${d.date}](/${d.file})`;
}

/** FR 축 인덱스. memory 열이 178개 고아 문제를 실제로 푸는 지점이다. */
export function renderFrIndex(rows, memoryByFr) {
  const lines = [
    AUTOGEN_HEADER,
    '',
    `# FR 축 인덱스 (${rows.length} FR)`,
    '',
    '> 이 FR 을 작업할 때 읽을 문서. 라우터. [INDEX.md](INDEX.md)',
    '',
    '| FR | spec | plan | decision/adr | memory |',
    '|---|---|---|---|---|',
  ];
  for (const r of rows) {
    const mem = (memoryByFr.get(r.frId) || []).map((s) => `[[${s}]]`).join(' ') || '—';
    const cell = (arr) => (arr.length ? arr.map(docLink).join(' ') : '—');
    // 본문 언급만인 문서는 개수로 축약한다. 전부 링크하면 한 행이 4000자를 넘는다.
    const mention = r.mentioned > 0 ? ` <sub>+${r.mentioned} 언급</sub>` : '';
    lines.push(
      `| ${r.frId} | ${cell(r.specs)} | ${cell(r.plans)} | ` +
        `${cell([...r.decisions, ...r.adr])} | ${mem}${mention} |`,
    );
  }
  lines.push('');
  return lines.join('\n');
}

/** 시간 축 인덱스. FR 없는 문서를 받아내는 그물이다. */
export function renderRecentIndex(rows) {
  const lines = [
    AUTOGEN_HEADER,
    '',
    `# 시간 축 인덱스 (${rows.length}건, 최신순)`,
    '',
    '> 라우터. [INDEX.md](INDEX.md)',
    '',
    '| 날짜 | slug | spec | plan | decision | adr | FR |',
    '|---|---|---|---|---|---|---|',
  ];
  for (const r of rows) {
    const k = (n) => (r.kinds[n] ? '✔' : '—');
    const fr = [...r.frIds].sort().join(' ') || '—';
    lines.push(
      `| ${r.date} | ${r.slug} | ${k('specs')} | ${k('plans')} | ${k('decisions')} | ${k('adr')} | ${fr} |`,
    );
  }
  lines.push('');
  return lines.join('\n');
}

export function renderDocsRouter(stats) {
  return [
    AUTOGEN_HEADER,
    '',
    '# docs 인덱스 — 무엇을 찾느냐에 따라',
    '',
    '| 찾는 것 | 열 파일 |',
    '|---|---|',
    '| 이 FR 을 작업할 때 읽을 문서 | [INDEX-fr.md](INDEX-fr.md) |',
    '| 최근에 무슨 작업을 했나 | [INDEX-recent.md](INDEX-recent.md) |',
    '| FR 목록·진척 | [plan/README.md](plan/README.md) · [plan/fr-index.md](plan/fr-index.md) |',
    '| 설계 26개 챕터 | [sdd/README.md](sdd/README.md) |',
    '',
    '## 읽는 법 — 통째로 열지 말 것',
    '',
    '두 인덱스는 전량을 담아 각각 40KB 를 넘는다. **필요한 행만 grep 한다.**',
    '',
    '```bash',
    'grep "^| FR-UX-09 " docs/INDEX-fr.md      # 이 FR 의 spec·plan·adr·memory 한 줄',
    'head -20 docs/INDEX-recent.md            # 최근 작업 12건',
    'grep " fr-ux-08" docs/INDEX-recent.md    # slug 로 찾기',
    '```',
    '',
    `> 집계. spec ${stats.specs} · plan ${stats.plans} · decision ${stats.decisions} · adr ${stats.adr}`,
    '',
  ].join('\n');
}

/** 양방향 차집합. 한 방향만 보면 봉인이 절반만 닫힌다. */
export function auditOrphans(actualSlugs, indexedSlugs) {
  const idx = new Set(indexedSlugs);
  const act = new Set(actualSlugs);
  return {
    orphans: actualSlugs.filter((s) => !idx.has(s)),
    broken: indexedSlugs.filter((s) => !act.has(s)),
  };
}
