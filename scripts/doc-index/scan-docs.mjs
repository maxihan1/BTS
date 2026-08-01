// docs/ 문서에서 파일명(날짜·slug) · H1 제목 · 본문 FR ID 를 뽑아 FR축/시간축으로 묶는다
const FILENAME_RE = /^(\d{4}-\d{2}-\d{2})-(.+)\.md$/;
const FR_IN_BODY_RE = /FR-[A-Z]{2,3}-\d{2}/g;
const FR_IN_NAME_RE = /fr-([a-z]{2,3})-(\d{2})/g;

export function parseDocMeta(filename, content) {
  const m = filename.match(FILENAME_RE);
  const inName = [
    ...new Set(
      [...filename.matchAll(FR_IN_NAME_RE)].map((x) => `FR-${x[1].toUpperCase()}-${x[2]}`),
    ),
  ].sort();
  return {
    date: m ? m[1] : '',
    slug: m ? m[2] : filename.replace(/\.md$/, ''),
    title: (content.match(/^#\s+(.+)$/m) || [, ''])[1].trim(),
    frIds: [...new Set([...(content.match(FR_IN_BODY_RE) || []), ...inName])].sort(),
    frIdsInName: inName,
  };
}

/** docs/plan/fr-index.md · product/*.md 의 FR ID 집합. 이것이 FR 의 정본이다. */
export function readCanonicalFrIds(...contents) {
  const s = new Set();
  for (const c of contents) for (const id of c.match(FR_IN_BODY_RE) || []) s.add(id);
  return s;
}

/**
 * FR 축으로 묶는다.
 *
 * ★ 파일명에 FR ID 가 있는 문서만 **주 문서**로 링크한다. 본문에 스쳐 언급한 것까지 전부
 *   링크하면 한 행이 4000자를 넘어 사람도 에이전트도 못 읽는다 (FR-UX-06 실측 4236자).
 *   다만 주 문서가 하나도 없으면 언급을 승격한다 — adr 는 파일명 FR ID 가 1/34 뿐이라
 *   좁히기만 하면 열이 통째로 비어버린다.
 *
 * canonical 을 주면 그 집합 밖의 FR 은 버리고 rejected 로 돌려준다 (오타·폐기 ID 노출용).
 */
export function groupByFr(docs, canonical = null) {
  const byFr = new Map();
  for (const d of docs) {
    for (const fr of d.frIds) {
      if (canonical && !canonical.has(fr)) continue;
      if (!byFr.has(fr)) {
        byFr.set(fr, {
          frId: fr,
          specs: [],
          plans: [],
          decisions: [],
          adr: [],
          mentionedDocs: [],
          mentioned: 0,
        });
      }
      const row = byFr.get(fr);
      const isPrimary = (d.frIdsInName || []).includes(fr);
      if (isPrimary) {
        if (row[d.kind]) row[d.kind].push(d);
      } else {
        row.mentionedDocs.push(d);
      }
    }
  }
  for (const row of byFr.values()) {
    const primaryCount = row.specs.length + row.plans.length + row.decisions.length + row.adr.length;
    if (primaryCount === 0) {
      // 주 문서가 없다 — 언급을 승격해야 행이 비지 않는다
      for (const d of row.mentionedDocs) if (row[d.kind]) row[d.kind].push(d);
      row.mentioned = 0;
    } else {
      row.mentioned = row.mentionedDocs.length;
    }
    delete row.mentionedDocs;
  }
  const rows = [...byFr.values()].sort((a, b) => a.frId.localeCompare(b.frId));

  if (!canonical) return rows;
  const rejected = [
    ...new Set(docs.flatMap((d) => d.frIds).filter((fr) => !canonical.has(fr))),
  ].sort();
  return { rows, rejected };
}

export function groupByDate(docs) {
  const bySlug = new Map();
  for (const d of docs) {
    const key = `${d.date}/${d.slug}`;
    if (!bySlug.has(key)) {
      bySlug.set(key, { date: d.date, slug: d.slug, title: d.title, kinds: {}, frIds: new Set() });
    }
    const row = bySlug.get(key);
    row.kinds[d.kind] = true;
    if (!row.title && d.title) row.title = d.title;
    for (const fr of d.frIds) row.frIds.add(fr);
  }
  return [...bySlug.values()].sort((a, b) =>
    a.date === b.date ? a.slug.localeCompare(b.slug) : b.date.localeCompare(a.date),
  );
}
