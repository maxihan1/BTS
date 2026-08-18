// TODOS.md 의 두 버전을 대조해 항목·줄이 소실됐는지 열거하는 무손실 판별기 + CLI
//
// ## 왜 있나
//
// `TODOS.md` 는 5,000줄이 넘고, 항목을 카테고리 절로 옮기는 것 같은 대량 이동은
// **diff 로 사람이 판정할 수 없다** — 무엇이 이동이고 무엇이 편집인지 눈으로 못 가린다.
// 그 상태에서 덩어리 하나가 조용히 사라지면 아무도 모른다.
//
// 선행 계획서(`docs/plans/2026-08-18-debt-dashboard-plain-language.md:221`)가 이 안전망을
// 「#389 가 만든다」고 적었으나 실제로는 만들어지지 않았다. PR #390 이 그 미이행분을 갚는다.
//
// ## 두 축을 쓰는 이유
//
// ① **항목 축** — `(상태, 제목, 본문)` 멀티셋. 사라진 것을 **제목으로 지목**해 준다.
//    집합이 아니라 멀티셋이다. 집합으로 세면 같은 항목 2건 중 1건 소실이 조용히 통과한다.
// ② **줄 축** — 파일의 모든 줄 멀티셋. `parseTodos` 는 항목 밖 줄(머리 산문·절 사이 설명)을
//    **버리므로** ① 만으로는 그 소실이 초록이다. ② 가 그 구멍을 덮는다.
//    이 축이 있어서 「항목 밖 줄은 사람이 판정한다」로 미룰 필요가 없다.
//
// ## 파서를 새로 적지 않는다
//
// 항목 파싱은 `build-dashboard.mjs` 의 `parseTodos` 를 import 한다. 헤딩을 읽는 코드를
// 새로 적을 때마다 코드펜스 판정을 잊는 것이 이 저장소의 반복 사고다(#389 한 세션 5회).
// 줄 축은 파싱이 아니라 줄 나누기라 스캐너가 아니다.

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

import { parseTodos } from '../build-dashboard.mjs';

/**
 * 항목 하나의 지문. 세 값을 **전부** 넣는다.
 *
 * 본문을 빼면 「본문만 사라진 항목」이 통과하고, 상태를 빼면 `⬜` 가 `✅` 로 조용히
 * 바뀐 것이 통과한다. JSON 배열로 직렬화하는 이유는 구분자가 값 안에 나타나
 * 경계가 모호해지는 일이 없기 때문이다 — 되읽어 제목만 뽑을 수도 있다.
 */
function todoFingerprint(t) {
  return JSON.stringify([t.status, t.title, t.body]);
}

/** 멀티셋 차집합. `a` 에 있는 만큼 `b` 에 없는 원소를 **개수만큼** 돌려준다. */
function multisetDiff(a, b) {
  const remaining = new Map();
  for (const x of b) remaining.set(x, (remaining.get(x) ?? 0) + 1);
  const out = [];
  for (const x of a) {
    const n = remaining.get(x) ?? 0;
    if (n > 0) remaining.set(x, n - 1);
    else out.push(x);
  }
  return out;
}

/** 지문에서 사람이 읽을 표시로. 본문은 길어서 상태·제목까지만 보인다. */
function label(fingerprint) {
  const [status, title] = JSON.parse(fingerprint);
  return `${status} — ${title}`;
}

/**
 * 항목 축 대조.
 *
 * ★기대값은 호출자가 넘긴 `before` 다. 대상 파일에서 파생시키지 않는다 —
 * 파생시키면 무엇을 바꿔도 둘이 함께 변해 영원히 초록이 된다
 * (`[[expected-value-derived-from-subject-is-a-tautology]]`).
 *
 * ★**개명은 소실이 아니다.** 한쪽에서 사라지고 다른 쪽에서 생긴 항목 중 **상태와 본문이
 * 똑같은** 것은 제목만 바뀐 것이다. 그것까지 소실로 세면 정당한 제목 수정이 전부 red 가
 * 되고, 판별식은 「고칠 때마다 판별식을 고쳐야 하는」 마찰 장치로 전락한다.
 * 개명을 접는 조건이 **본문 동일**이라 관대하지 않다 — 본문이 한 글자라도 다르면
 * 그대로 `lost` 와 `gained` 양쪽에 남는다.
 *
 * 제목이 장부의 조인 키라는 계약은 `debt-ledger-mapping.test.ts` 가 마스터 계획과 대조해
 * 따로 본다. 이 파일의 책임은 **내용이 사라졌는가** 하나다.
 */
export function compareTodoIntegrity(before, after) {
  const a = parseTodos(before).map(todoFingerprint);
  const b = parseTodos(after).map(todoFingerprint);
  const lostRaw = multisetDiff(a, b);
  const gainedRaw = multisetDiff(b, a);

  // (상태, 본문) 이 같은 lost/gained 를 짝지어 개명으로 뺀다.
  const bodyKey = (fp) => {
    const [status, , body] = JSON.parse(fp);
    return JSON.stringify([status, body]);
  };
  const pool = new Map();
  for (const g of gainedRaw) {
    const k = bodyKey(g);
    if (!pool.has(k)) pool.set(k, []);
    pool.get(k).push(g);
  }
  const stillLost = [];
  const renamed = [];
  for (const l of lostRaw) {
    const bucket = pool.get(bodyKey(l));
    if (bucket && bucket.length > 0) renamed.push(`${label(l)}  →  ${label(bucket.shift())}`);
    else stillLost.push(l);
  }

  // ★**본문 편집도 소실이 아니다.** 개명(제목만 바뀜)을 접었으면 그 거울상인 편집
  // (본문만 바뀜)도 접어야 한다. 접지 않으면 **어떤 PR 도 부채 항목 본문을 고칠 수 없다** —
  // 실측으로 갱신하는 순간 F2b 가 「항목이 사라졌다」로 red 를 낸다(2026-08-18, 용어 전수
  // 교체 PR 이 실제로 밟았다). 접는 조건은 **상태·제목 동일**이라 관대하지 않다 —
  // 둘 다 바뀌면 같은 항목이라는 증거가 없으므로 `lost`·`gained` 양쪽에 그대로 남는다.
  // 접되 **조용히 접지 않는다** — 무엇이 편집됐는지 호출자가 반드시 볼 수 있게 열거한다.
  const titleKey = (fp) => {
    const [status, title] = JSON.parse(fp);
    return JSON.stringify([status, title]);
  };
  // ★접는 조건에 **본문이 줄지 않았을 것**을 넣는다. 낱말을 고친 편집은 줄 수가 그대로지만
  //   본문 한 줄을 지운 것은 줄 수가 준다. 이 조건이 없으면 「제목만 남기고 본문을 지웠다」가
  //   편집으로 접혀 조용히 통과한다(그 경우를 잡는 것이 이 판별식의 원래 이유다).
  const bodyLines = (fp) => JSON.parse(fp)[2].split('\n').filter((l) => l.trim() !== '').length;
  const gainedPool = new Map();
  for (const [, bucket] of pool) {
    for (const g of bucket) {
      const k = titleKey(g);
      if (!gainedPool.has(k)) gainedPool.set(k, []);
      gainedPool.get(k).push(g);
    }
  }
  const lost = [];
  const edited = [];
  for (const l of stillLost) {
    const bucket = gainedPool.get(titleKey(l));
    const i = bucket ? bucket.findIndex((g) => bodyLines(g) >= bodyLines(l)) : -1;
    if (i >= 0) {
      const [g] = bucket.splice(i, 1);
      edited.push(`${label(l)}  (본문 ${bodyLines(l)}줄 → ${bodyLines(g)}줄)`);
    } else lost.push(label(l));
  }
  const gained = [];
  for (const [, bucket] of gainedPool) for (const g of bucket) gained.push(label(g));

  return { lost, gained, renamed, edited, beforeCount: a.length, afterCount: b.length };
}

/**
 * 줄 축 대조. 항목 안팎을 가리지 않는다.
 *
 * 순수 이동이면 양쪽 다 비어야 한다. 재배열이 H1 을 새로 만들면 `gained` 에 `# …` 줄이
 * 나오는데, **그 밖의 줄이 하나라도 나오면 이동이 아니라 편집**이다.
 */
export function compareAllLines(before, after) {
  const a = before.split('\n');
  const b = after.split('\n');
  return { lost: multisetDiff(a, b), gained: multisetDiff(b, a) };
}

/** H1 과 빈 줄은 재배열이 만드는 **의도된** 줄 차이다. 그 밖의 줄 차이는 전부 편집이다. */
export function isStructuralLine(line) {
  return line.startsWith('# ') || line.trim() === '';
}

/**
 * 순수 이동 판정. **CLI 와 판별식이 같은 이 함수를 부른다.**
 *
 * ★판정을 호출자마다 다시 적으면 안 된다. 2026-08-18 코드 리뷰 실측 —
 * `isStructuralLine` 을 `return true` 로 바꿔도 387종 전량이 초록이었다. 판별식이 CLI 의
 * 판정을 import 하지 않고 **손으로 다시 적었기** 때문이다(`[[two-lists-never-check-each-other]]`).
 *
 * ★**H1 소실은 이동이 아니다.** 절 H1 이 사라지면 그 절의 항목이 통째로 앞 절에 흡수되는데,
 * 항목 지문에 절이 없어 항목 축이 이것을 보지 못한다. 같은 실측에서 실파일의 H1 한 줄을
 * 지우고 돌렸더니 「순수 이동이다」 EXIT 0 이었다. 절을 실제로 없애는 재배열은 정당하지만
 * 그 승인은 **호출자의 명시 선택**이어야 한다 — 기본값이 아니다.
 */
export function judgePureMove(before, after, { allowH1Loss = false } = {}) {
  const items = compareTodoIntegrity(before, after);
  const lines = compareAllLines(before, after);
  const editedLost = lines.lost.filter((l) => !isStructuralLine(l));
  const editedGained = lines.gained.filter((l) => !isStructuralLine(l));
  const h1Lost = lines.lost.filter((l) => l.startsWith('# '));

  // ★순수 이동 판정에는 개명도 0 이어야 한다. 개명은 정당한 편집이지 이동이 아니다 —
  //   F2b(상시 소실 검사)는 개명을 허용하지만, 이동 커밋의 검증은 허용하지 않는다.
  const clean =
    items.lost.length === 0 &&
    items.gained.length === 0 &&
    items.renamed.length === 0 &&
    items.edited.length === 0 &&
    editedLost.length === 0 &&
    editedGained.length === 0 &&
    (allowH1Loss || h1Lost.length === 0);

  return { clean, items, lines, editedLost, editedGained, h1Lost };
}

/**
 * merge-base 시점의 `TODOS.md` 를 읽는다.
 *
 * ★**못 얻었을 때 조용히 통과하면 안 된다.** 그래서 `content: null` 과 함께 **이유**를
 * 반드시 돌려준다 — 호출자가 그 이유를 출력하지 않으면 「검사가 돌았다」와 「검사가
 * 건너뛰어졌다」가 구분되지 않는다. 이 저장소가 여러 번 겪은 「0 이 나오면 판별식을
 * 의심하라」의 같은 계열이다.
 */
export function readBaseTodos(repoRoot, refs = ['origin/main', 'main']) {
  const git = (args) =>
    execFileSync('git', args, { cwd: repoRoot, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();

  // ★후보를 **목록**으로 받는다. `origin/main` 하나만 보면 원격 이름이 `upstream` 인 클론이나
  //   fork 체크아웃에서 이 축만 꺼지고 다른 판별식은 `main` 폴백으로 멀쩡히 돈다 —
  //   「검사가 깨졌다」가 아니라 「검사가 안 돌았다」쪽이라 아무도 모른다.
  //   같은 정책의 정본은 `changed-paths.ts` 의 `firstExistingRef` 다. 여기서 다시 적는 이유는
  //   이 파일이 타입 스트립 플래그 없이 도는 CLI 라 `.ts` 를 import 할 수 없기 때문이다.
  let sha = null;
  let picked = null;
  for (const ref of refs) {
    try {
      sha = git(['merge-base', ref, 'HEAD']);
      picked = ref;
      break;
    } catch {
      // 다음 후보로. 전부 실패하면 아래에서 이유를 만든다.
    }
  }
  if (sha === null) {
    return {
      content: null,
      sha: null,
      reason: `merge-base 실패 — 후보 ${refs.join(' · ')} 가 전부 부재이거나 shallow clone`,
    };
  }
  try {
    return { content: git(['show', `${sha}:TODOS.md`]), sha, reason: `${picked} 병합기점 ${sha.slice(0, 9)}` };
  } catch {
    return { content: null, sha, reason: `${sha.slice(0, 9)}:TODOS.md 를 읽지 못했다` };
  }
}

/**
 * CLI.
 *
 * ```
 * node scripts/workflow/todos-reorder-integrity.mjs <before-file> <after-file> [--allow-h1-loss]
 * ```
 *
 * `--allow-h1-loss` 는 **절을 실제로 없애는** 재배열에만 쓴다. 무엇을 승인했는지는 붙이든
 * 안 붙이든 출력에 남는다 — 승인이 곧 침묵이 되면 안 된다.
 */
function main(argv) {
  const allowH1Loss = argv.includes('--allow-h1-loss');
  const [beforePath, afterPath] = argv.filter((a) => !a.startsWith('--'));
  if (!beforePath || !afterPath) {
    console.error(
      'Usage: node scripts/workflow/todos-reorder-integrity.mjs <before-file> <after-file> [--allow-h1-loss]',
    );
    return 2;
  }
  const before = fs.readFileSync(beforePath, 'utf-8');
  const after = fs.readFileSync(afterPath, 'utf-8');

  const { clean, items, lines, editedLost, editedGained, h1Lost } = judgePureMove(before, after, { allowH1Loss });

  console.log(`항목  before ${items.beforeCount} → after ${items.afterCount}`);
  console.log(`  사라진 항목 ${items.lost.length}건`);
  for (const s of items.lost) console.log(`    - ${s}`);
  console.log(`  생긴 항목 ${items.gained.length}건`);
  for (const s of items.gained) console.log(`    + ${s}`);
  console.log(`  개명 ${items.renamed.length}건 (상태·본문 동일)`);
  for (const s of items.renamed) console.log(`    ~ ${s}`);
  console.log(`줄    구조 차이(H1·빈 줄) 제외 — 사라진 ${editedLost.length} · 생긴 ${editedGained.length}`);
  for (const s of editedLost) console.log(`    - ${JSON.stringify(s)}`);
  for (const s of editedGained) console.log(`    + ${JSON.stringify(s)}`);
  console.log(
    `줄    구조 차이 — 사라진 ${lines.lost.length - editedLost.length} · 생긴 ${lines.gained.length - editedGained.length}`,
  );
  console.log(`H1    사라진 절 제목 ${h1Lost.length}건${allowH1Loss ? ' (--allow-h1-loss 로 승인됨)' : ''}`);
  for (const s of h1Lost) console.log(`    - ${JSON.stringify(s)}`);

  console.log(clean ? '\n순수 이동이다 — 차집합 0.' : '\n★이동이 아니라 편집이 섞였다.');
  return clean ? 0 : 1;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.exit(main(process.argv.slice(2)));
}
