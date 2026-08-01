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

/** 양방향 차집합. 한 방향만 보면 봉인이 절반만 닫힌다. */
export function auditOrphans(actualSlugs, indexedSlugs) {
  const idx = new Set(indexedSlugs);
  const act = new Set(actualSlugs);
  return {
    orphans: actualSlugs.filter((s) => !idx.has(s)),
    broken: indexedSlugs.filter((s) => !act.has(s)),
  };
}
