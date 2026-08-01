// 메모리·docs 인덱스를 생성하는 단일 진입점. 저장소 밖(메모리)은 CI 가 못 지키므로 여기서 자가진단한다
// 실행. node scripts/build-doc-index.mjs

import fs from 'node:fs';
import path from 'node:path';
import { MEMORY_DIR, AUTOGEN_HEADER } from './doc-index/config.mjs';
import { parseFrontmatter, parseLegacyIndex } from './doc-index/parse-memory.mjs';
import { classify, partition } from './doc-index/classify.mjs';
import { renderRouter, renderCategoryIndex, auditOrphans } from './doc-index/render.mjs';

/**
 * 승계 원천을 읽는다.
 *
 * ★ 자기보호 가드. 이 스크립트는 MEMORY.md 를 **덮어쓴다**. 그런데 손 큐레이션(★·hook·그룹)의
 *   승계 원천도 MEMORY.md 다. 한 번 덮어쓴 뒤 그 결과를 다시 승계 원천으로 읽으면
 *   "자동 생성물에서 손 큐레이션을 승계"하는 꼴이 되어, 파싱 규칙이 안 맞는 순간 조용히 빈 Map 이
 *   되고 ★ 가 전멸한다. 자동 생성 마커가 보이면 승계를 포기하고 frontmatter 를 정본으로 쓴다.
 */
function readLegacySource(memoryMdPath) {
  const raw = fs.readFileSync(memoryMdPath, 'utf8');
  if (raw.startsWith(AUTOGEN_HEADER)) {
    return { legacy: new Map(), fromAutogen: true };
  }
  return { legacy: parseLegacyIndex(raw), fromAutogen: false };
}

function readMemory() {
  const files = fs.readdirSync(MEMORY_DIR).filter((f) => f.endsWith('.md') && f !== 'MEMORY.md');
  return files.map((f) => {
    const slug = f.replace(/\.md$/, '');
    const fm = parseFrontmatter(fs.readFileSync(path.join(MEMORY_DIR, f), 'utf8')) || {};
    return {
      slug,
      description: fm.description || '',
      hook: fm.hook || '',
      priority: fm.priority || 'normal',
      category: fm.category || '',
      type: fm.type || '',
    };
  });
}

function main() {
  const memoryMd = path.join(MEMORY_DIR, 'MEMORY.md');
  const { legacy, fromAutogen } = readLegacySource(memoryMd);
  const all = readMemory();

  // backfill 전에 MEMORY.md 를 덮어쓰면 손 큐레이션이 영구 소실된다 (git 이 추적하지 않는 경로다).
  // frontmatter 에 category 가 하나도 없는데 승계 원천도 없으면 = 아직 backfill 이 안 된 상태다.
  const backfilled = all.filter((e) => e.category).length;
  if (fromAutogen && backfilled === 0) {
    console.error('FAIL. MEMORY.md 는 자동 생성본인데 frontmatter 에 category 가 0건이다.');
    console.error('  → backfill 이 안 된 상태에서 승계 원천을 잃었다. 백업에서 MEMORY.md 를 복원하고');
    console.error('     `node scripts/doc-index/backfill.mjs` 를 먼저 실행할 것.');
    process.exit(1);
  }
  const { frHistory, categorized } = partition(all, legacy);

  const bySlug = new Map(all.map((e) => [e.slug, e]));

  // priority·hook 승계는 **전량**에 적용한다. FR 축으로 가는 메모리도 ★ 일 수 있다.
  for (const e of all) {
    e.priority = e.priority === 'critical' || legacy.get(e.slug)?.star ? 'critical' : 'normal';
    if (!e.hook) e.hook = legacy.get(e.slug)?.hook || '';
  }

  const catOf = new Map();
  for (const slug of categorized) {
    const e = bySlug.get(slug);
    // frontmatter 가 이미 분류를 갖고 있으면 그것이 정본이다 (backfill 이후 경로)
    catOf.set(slug, e.category && e.category !== 'fr-history' ? e.category : classify(slug, legacy));
  }

  const counts = {};
  for (const cat of catOf.values()) counts[cat] = (counts[cat] || 0) + 1;

  // --- 쓰기 ---
  const indexDir = path.join(MEMORY_DIR, 'index');
  fs.mkdirSync(indexDir, { recursive: true });
  // ★ 구획에는 **전량**을 넘긴다. categorized 만 넘기면 FR 축으로 간 ★ 가 라우터에서 사라진다.
  const routerBody = renderRouter(all, counts, frHistory.length);
  fs.writeFileSync(path.join(MEMORY_DIR, 'MEMORY.md'), routerBody);
  for (const cat of Object.keys(counts)) {
    const entries = categorized.filter((s) => catOf.get(s) === cat).map((s) => bySlug.get(s));
    fs.writeFileSync(path.join(indexDir, `${cat}.md`), renderCategoryIndex(cat, entries));
  }

  // --- 자가진단. CI 가 못 보는 층이므로 여기서 막는다 ---
  const indexed = [...categorized, ...frHistory.map((f) => f.slug)];
  const { orphans, broken } = auditOrphans(
    all.map((e) => e.slug),
    indexed,
  );
  const uncat = counts.uncategorized || 0;

  console.log(`→ 메모리 ${all.length}건 = FR축 ${frHistory.length} + 카테고리 ${categorized.length}`);
  console.log(`→ 분포. ${JSON.stringify(counts)}`);

  // ★ 두 목록 차집합 — frontmatter 의 critical 집합 ⟺ 라우터에 실제로 렌더된 집합.
  //   렌더러 단위 테스트는 "넘겨준 것"만 보므로, 진입점이 일부만 넘기는 결함을 못 잡는다.
  //   실제로 categorized 만 넘겨 FR 축 ★ 5건이 조용히 사라진 적이 있다. 산출물을 대조한다.
  const critExpected = all.filter((e) => e.priority === 'critical').map((e) => e.slug);
  const critRendered = critExpected.filter((s) => routerBody.includes(`[[${s}]]`));
  const critMissing = critExpected.filter((s) => !routerBody.includes(`[[${s}]]`));

  let bad = false;
  if (critMissing.length) {
    console.error(
      `FAIL. ★(critical) ${critExpected.length}건 중 ${critMissing.length}건이 라우터에 없다.`,
      critMissing.slice(0, 10),
    );
    bad = true;
  }
  if (orphans.length) {
    console.error(`FAIL. 고아 ${orphans.length}건.`, orphans.slice(0, 10));
    bad = true;
  }
  if (broken.length) {
    console.error(`FAIL. 깨진 링크 ${broken.length}건.`, broken.slice(0, 10));
    bad = true;
  }
  if (uncat > 0) {
    console.warn(`WARN. uncategorized ${uncat}건 — 눈으로 확인해 분류할 것.`);
  }
  if (bad) process.exit(1);
  console.log(`PASS. 고아 0 · 깨진 링크 0 · ★ ${critRendered.length}/${critExpected.length} 렌더.`);
}

main();
