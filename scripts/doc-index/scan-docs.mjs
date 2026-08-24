// docs/ 문서에서 파일명(날짜·slug) · H1 제목 · 본문 FR ID 를 뽑아 FR축/시간축으로 묶는다
const FILENAME_RE = /^(\d{4}-\d{2}-\d{2})-(.+)\.md$/;

// ★ 앞에 대문자가 붙으면 FR ID 가 아니다. `NFR-SEC-01`(Non-Functional Requirement)의 뒷부분을
//   잘라 `FR-SEC-01` 로 잡는 오탐이 실제로 5건 있었다 — 문서가 아니라 판별식이 틀린 것이었다.
//   같은 계열 사고. verify-master-plan 의 헤더 스캐너가 산문 FR-XX-NN 을 오인해 EXIT 1 을 낸 건.
const FR_IN_BODY_RE = /(?<![A-Z])FR-[A-Z]{2,3}-\d{2}/g;
const FR_IN_NAME_RE = /(?<![a-z])fr-([a-z]{2,3})-(\d{2})/g;

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
 * ★★ **승격 판정은 종류별이다(행 단위가 아니다).** 종전에는 행에 주 문서가 하나라도 생기면
 *   **모든 열**의 승격이 꺼졌다. 그래서 FR-WF-04 에 파일명 FR ID 를 가진 plan 이 처음
 *   추가되자 spec 2 + adr 4 를 포함한 9건이 한꺼번에 사라지고 「+9 언급」이라는 숫자만
 *   남았다(2026-08-24 실측). spec·adr 는 자기 열에 주 문서가 없었을 뿐인데 남의 열 때문에
 *   지워진 것이다. 4000자 우려는 **주 문서가 있는 열**에만 해당하므로 그 열에서만 좁힌다.
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
        if (row[d.kind]) {
          row[d.kind].push(d);
          // 승격 단계에서 「원래 주 문서가 있던 열」인지 구별해야 한다. 승격으로 들어간
          // 문서까지 세면 첫 승격 이후 그 열이 잠겨 나머지가 조용히 빠진다.
          (row.__primaryKinds ??= new Set()).add(d.kind);
        }
      } else {
        row.mentionedDocs.push(d);
      }
    }
  }
  for (const row of byFr.values()) {
    // 종류별로 승격을 가른다 — 자기 열에 주 문서가 없는 종류만 언급을 올린다.
    let promoted = 0;
    for (const d of row.mentionedDocs) {
      const column = row[d.kind];
      if (!column) continue;
      // ★ `column.length > 0` 을 보면 안 된다 — 승격으로 들어간 문서까지 세어 첫 승격
      //   이후 그 열이 잠기고 나머지가 조용히 빠진다(실측으로 밟았다). 주 문서가 들어간
      //   종류만 기억해 둔 집합으로 판정한다.
      const hasPrimaryInKind = row.__primaryKinds?.has(d.kind) === true;
      if (!hasPrimaryInKind) {
        column.push(d);
        promoted += 1;
      }
    }
    row.mentioned = row.mentionedDocs.length - promoted;
    delete row.mentionedDocs;
    delete row.__primaryKinds;
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
