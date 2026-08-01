// 메모리·docs 인덱스를 생성하는 단일 진입점. 저장소 밖(메모리)은 CI 가 못 지키므로 여기서 자가진단한다
// 실행. node scripts/build-doc-index.mjs [--check]
//
// --check. 파일을 **쓰지 않고** 현재 내용과 비교만 한다. 다르면 exit 1.
//   왜 필요한가. 판별식이 "재생성 후 git diff" 로 drift 를 잡으려 하면, 그 재생성이
//   검사 대상인 손 수정을 덮어써서 판별식이 스스로 증거를 지운다 — 항상 통과하는 장식이 된다.
//   실제로 뮤테이션 주입에서 룰 I·J-삭제·K 가 이 이유로 전부 green 이었다.

import fs from 'node:fs';
import path from 'node:path';
import {
  MEMORY_DIR,
  REPO_ROOT,
  SOURCES,
  AUTOGEN_HEADER,
  HOME,
  KNOWN_HISTORICAL_FR_IDS,
} from './doc-index/config.mjs';
import { parseFrontmatter, parseLegacyIndex } from './doc-index/parse-memory.mjs';
import { classify, partition, frIdOf } from './doc-index/classify.mjs';
import {
  parseDocMeta,
  groupByFr,
  groupByDate,
  readCanonicalFrIds,
} from './doc-index/scan-docs.mjs';
import {
  renderRouter,
  renderCategoryIndex,
  renderFrIndex,
  renderRecentIndex,
  renderDocsRouter,
  auditOrphans,
} from './doc-index/render.mjs';

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

const CHECK = process.argv.includes('--check');
const drift = [];

/** --check 면 비교만, 아니면 쓴다. 파일이 없거나 다르면 drift 에 기록. */
function emit(filePath, body) {
  const cur = fs.existsSync(filePath) ? fs.readFileSync(filePath, 'utf8') : null;
  if (cur !== body) drift.push(path.relative(REPO_ROOT, filePath));
  if (!CHECK) fs.writeFileSync(filePath, body);
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
  if (!CHECK) fs.mkdirSync(indexDir, { recursive: true });
  // ★ 구획에는 **전량**을 넘긴다. categorized 만 넘기면 FR 축으로 간 ★ 가 라우터에서 사라진다.
  const routerBody = renderRouter(all, counts, frHistory.length);
  emit(path.join(MEMORY_DIR, 'MEMORY.md'), routerBody);
  for (const cat of Object.keys(counts)) {
    const entries = categorized.filter((s) => catOf.get(s) === cat).map((s) => bySlug.get(s));
    emit(path.join(indexDir, `${cat}.md`), renderCategoryIndex(cat, entries));
  }

  // --- docs 인덱스 ---
  const docs = [];
  const stats = {};
  for (const src of SOURCES) {
    const dir = path.join(REPO_ROOT, src.dir);
    const files = fs.existsSync(dir) ? fs.readdirSync(dir).filter((f) => f.endsWith('.md')) : [];
    stats[src.key] = files.length;
    for (const f of files) {
      const meta = parseDocMeta(f, fs.readFileSync(path.join(dir, f), 'utf8'));
      docs.push({ ...meta, kind: src.key, file: `${src.dir}/${f}` });
    }
  }
  // memory 열은 **전체 메모리**에서 FR ID 로 모은다. 카테고리 분류와 무관하다.
  //   fr-co-01-…-done 은 사람이 `백엔드` 그룹에 등록해 category=backend 지만,
  //   FR-CO-01 을 작업할 때 반드시 보여야 한다. 두 축은 서로 배타적이지 않다 —
  //   "FR 로 찾을 때"와 "백엔드 작업할 때" 양쪽에 나오는 것이 맞다.
  const memoryByFr = new Map();
  for (const e of all) {
    const frId = frIdOf(e.slug);
    if (!frId) continue;
    if (!memoryByFr.has(frId)) memoryByFr.set(frId, []);
    memoryByFr.get(frId).push(e.slug);
  }
  // FR 정본은 docs/plan/{fr-index.md,product/*.md} 다. 그 밖의 FR ID 는 오타이거나 폐기된 것이다.
  // 조용히 버리지 않고 rejected 로 노출한다 — 버리기만 하면 오타를 영영 못 잡는다.
  const planDir = path.join(REPO_ROOT, 'docs/plan');
  const canonicalSrc = [fs.readFileSync(path.join(planDir, 'fr-index.md'), 'utf8')];
  for (const f of fs.readdirSync(path.join(planDir, 'product')).filter((x) => x.endsWith('.md'))) {
    canonicalSrc.push(fs.readFileSync(path.join(planDir, 'product', f), 'utf8'));
  }
  const canonical = readCanonicalFrIds(...canonicalSrc);
  const { rows: frRows, rejected } = groupByFr(docs, canonical);
  const frBody = renderFrIndex(frRows, memoryByFr);
  const recentBody = renderRecentIndex(groupByDate(docs));
  emit(path.join(REPO_ROOT, 'docs/INDEX.md'), renderDocsRouter(stats));
  emit(path.join(REPO_ROOT, 'docs/INDEX-fr.md'), frBody);
  emit(path.join(REPO_ROOT, 'docs/INDEX-recent.md'), recentBody);

  // docs 고아 — 렌더 **결과 문자열**에 실제로 나타나는지로 판정한다.
  //
  // ★ 여기서 `docs.map(d => d.file)` 같은 입력 배열로 검사하면 안 된다. 시간축은 입력 전량을
  //   받으므로 차집합이 정의상 항상 공집합이 되어, 렌더가 통째로 망가져도 "고아 0"이 뜬다.
  //   0 이 나오는 판별식은 먼저 판별식을 의심하라 — 렌더 산출물을 봐야 비-공허하다.
  const docOrphans = docs.filter((d) => !recentBody.includes(`| ${d.slug} |`));
  console.log(
    `→ docs ${docs.length}건. FR축 ${frRows.length}/${canonical.size} FR · 시간축 등록 ${docs.length - docOrphans.length} · 고아 ${docOrphans.length}`,
  );
  // 알려진 역사적 ID 는 경고에서 뺀다 — 매번 뜨면 경고 피로로 진짜 오타를 놓친다.
  const unknownFr = rejected.filter((fr) => !KNOWN_HISTORICAL_FR_IDS.has(fr));
  if (unknownFr.length) {
    console.warn(
      `WARN. 정본에 없는 FR ID ${unknownFr.length}건 (오타 의심, 인덱스에서 제외).`,
      unknownFr,
    );
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
  if (docOrphans.length) {
    console.error(
      `FAIL. docs 고아 ${docOrphans.length}건.`,
      docOrphans.slice(0, 10).map((d) => d.file),
    );
    bad = true;
  }
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
  // --check 는 파일을 쓰지 않으므로, 현재 내용과 생성 결과가 다르면 그것이 drift 다.
  if (CHECK && drift.length) {
    console.error(`FAIL. 인덱스 drift ${drift.length}건 — 재생성 결과와 현재 파일이 다르다.`);
    drift.forEach((f) => console.error(`  - ${f}`));
    console.error('  → node scripts/build-doc-index.mjs 후 커밋할 것.');
    bad = true;
  }

  if (bad) process.exit(1);

  // --- Obsidian. 저장소 밖이고 CI 가 검증하지 못하므로 명시적 플래그를 요구한다 ---
  if (process.argv.includes('--obsidian') && !CHECK) {
    const OBS = path.join(HOME, 'Maxi_wiki/BTS');
    if (fs.existsSync(OBS)) {
      const files = fs
        .readdirSync(OBS, { recursive: true })
        .filter((f) => typeof f === 'string' && f.endsWith('.md') && f !== 'INDEX.md')
        .sort();
      // 낡음을 눈에 보이게 한다 — 자동 차단이 없는 층이므로 생성 시각이 유일한 신선도 신호다.
      const stamp = new Date().toISOString().slice(0, 10);
      fs.writeFileSync(
        path.join(OBS, 'INDEX.md'),
        [
          AUTOGEN_HEADER,
          '',
          `# Maxi_wiki/BTS 인덱스 (${files.length}건 · 생성 ${stamp})`,
          '',
          '> ⚠ 저장소 밖이라 CI 가 검증하지 않는다. 낡았을 수 있다 — 생성일을 확인할 것.',
          '> 재생성. `node scripts/build-doc-index.mjs --obsidian`',
          '',
          ...files.map((f) => `- [[${f.replace(/\.md$/, '')}]]`),
          '',
        ].join('\n'),
      );
      console.log(`→ Obsidian ${files.length}건 인덱스 생성 (CI 검증 없음).`);
    }
  }

  console.log(
    `PASS. 고아 0 · 깨진 링크 0 · ★ ${critRendered.length}/${critExpected.length} 렌더` +
      `${CHECK ? ' · drift 0' : ''}.`,
  );
}

main();
