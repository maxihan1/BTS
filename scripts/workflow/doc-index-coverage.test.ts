// 문서 인덱스 판별식 — 재생성 diff(I) · 고아(J·J-역) · 깨진 링크(K)
//
// ## ★「룰 L」은 없다 (2026-09-09 정정)
//
// 종전 이 줄은 `SOURCES⟺CI paths(L)` 을 광고했고 `doc-index/config.mjs` 도 「룰 L 이
// 강제한다」고 적어 뒀다. **그 테스트는 쓰인 적이 없다.** 남아 있던 것은 참조 0 인
// `CI_FILE` 상수 하나뿐이었다 — 없는 가드를 「막는다」고 적은 상태이고,
// `behavior-rules.md` 머리말이 「강제 수단이 비면 그 규칙은 지켜지지 않을 수 있다」고
// 적은 그것보다 나쁘다. **있다고 믿게 만들기 때문**이다.
//
// 그리고 이제는 필요도 없다. 룰 L 이 막으려던 사고는 「생성기가 읽는 폴더를 늘렸는데
// CI 트리거 경로에 안 넣어서 검사가 아예 안 도는」 것이었는데, 젠킨스는 **판별식을
// 조건 없이 전량** 돌린다(`Jenkinsfile` 빠른 게이트 · `.husky/pre-push`). 어느 폴더를
// 고쳤든 무조건 돌므로 「조건 목록」이라는 두 번째 목록 자체가 존재하지 않는다.
//
// 되살릴 일이 생긴다면(= 경로별 선별을 도입한다면) 그때 **테스트를 실제로 쓰고** 이 주석을
// 고쳐라. 주석만 되돌리면 같은 상태로 돌아간다.
// 실행. node --experimental-strip-types --test scripts/workflow/doc-index-coverage.test.ts
//
// 왜 판별식인가. 이 저장소의 지배 결함은 "두 하드코딩 목록이 서로를 안 봐서 조용히 갈라지는" 것이다.
// 인덱스와 실제 파일이 정확히 그 두 목록이다. 차집합을 기계가 보게 만든다.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { MEMORY_DIR } from '../doc-index/config.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const INDEX_FILES = ['docs/INDEX.md', 'docs/INDEX-fr.md', 'docs/INDEX-recent.md'];

// 생성기가 승계 원천으로 읽는 저장소 **밖** 경로. 사본을 두지 않고 생성기의 상수를 그대로 쓴다 —
// 여기 경로를 다시 적으면 그것이 두 번째 목록이 되고, 생성기가 경로를 옮겨도 안 따라온다.
const MEMORY_MD = path.join(MEMORY_DIR, 'MEMORY.md');

test('룰 I — 현재 인덱스가 재생성 결과와 같다 (--check, 파일을 쓰지 않는다)', (t) => {
  // ★ 여기서 생성기를 **쓰기 모드**로 돌리면 안 된다. 재생성이 검사 대상인 손 수정을
  //   덮어써서 판별식이 스스로 증거를 지운다 — 뮤테이션에서 이 테스트뿐 아니라
  //   뒤따르는 룰 J·K 까지 전부 green 이 됐다(1차 주입 실측). --check 는 비교만 한다.

  // ★★메모리가 없는 기계에서는 이 룰이 성립하지 않는다 — 건너뛴다.
  //
  // 생성기는 승계 원천으로 `MEMORY_DIR/MEMORY.md` 를 읽는데 그 경로는 **저장소 밖**
  // (`$HOME/.claude/projects/…`)이다. `doc-index/config.mjs` 가 그 자리에
  // 「메모리는 저장소 밖이다. CI 가 볼 수 없다」고 이미 적어 두었는데, 이 룰은 그 생성기를
  // 조건 없이 불러서 두 진술이 서로를 안 보고 있었다 — 이 저장소의 지배 결함 양식 그대로다.
  //
  // 실측으로 드러난 증상이 있다. `self-hosted-runner.md` 가 「러너 2대가 같은 라벨이라
  // 판정이 배정에 따라 갈렸다(판별식 잡 최근 12회 중 7회 실패)」를 원인 미상으로 적어 뒀는데,
  // 그 원인이 이것이다 — 맥 러너에는 그 경로가 있고 리눅스 러너에는 없다.
  // 2026-09-09 젠킨스 빌드에서 `ENOENT … memory/MEMORY.md` 로 재현했다.
  //
  // ★건너뛰기가 조용히 영구화되지 않게 두 가지를 함께 단언한다.
  //   ① 건너뛰는 경로가 **저장소 밖**일 것 — 저장소 안 경로가 없어서 통과하는 일은 없다
  //   ② 봉인 자리가 남아 있을 것 — pre-commit 이 이 검사를 여전히 들고 있어야 한다
  //      (`behavior-rules.md §4` 가 「저장소 밖 메모리의 유일한 봉인」이라 적은 그 자리)
  if (!fs.existsSync(MEMORY_MD)) {
    assert.ok(
      !path.resolve(MEMORY_MD).startsWith(path.resolve(REPO_ROOT) + path.sep),
      `건너뛸 수 있는 것은 저장소 밖 경로뿐이다. 저장소 안인데 없다면 그것은 결함이다: ${MEMORY_MD}`,
    );
    const hook = fs.readFileSync(path.join(REPO_ROOT, '.husky/pre-commit'), 'utf8');
    assert.match(
      hook,
      /build-doc-index\.mjs[^\n]*--check/,
      'CI 에서 건너뛰는 대신 pre-commit 이 이 검사를 들고 있어야 한다. 훅에서 사라지면 봉인이 0곳이 된다.',
    );
    t.skip(`메모리 부재(${MEMORY_MD}) — 저장소 밖이라 CI 가 볼 수 없다. 봉인은 pre-commit 이 유지한다.`);
    return;
  }

  let failed = false;
  let out = '';
  try {
    out = execFileSync('node', ['scripts/build-doc-index.mjs', '--check'], {
      cwd: REPO_ROOT,
      encoding: 'utf8',
      stdio: 'pipe',
    });
  } catch (e) {
    failed = true;
    out = `${(e as { stdout?: string }).stdout ?? ''}${(e as { stderr?: string }).stderr ?? ''}`;
  }
  assert.equal(failed, false, `인덱스 drift.\n${out}`);
});

test('룰 J — docs 문서 전량이 시간축 인덱스에 등록되어 있다', () => {
  const recent = fs.readFileSync(path.join(REPO_ROOT, 'docs/INDEX-recent.md'), 'utf8');
  const orphans: string[] = [];
  for (const dir of ['docs/plans', 'docs/specs', 'docs/decisions', 'docs/adr']) {
    for (const f of fs.readdirSync(path.join(REPO_ROOT, dir)).filter((x) => x.endsWith('.md'))) {
      const slug = f.replace(/^\d{4}-\d{2}-\d{2}-/, '').replace(/\.md$/, '');
      if (!recent.includes(`| ${slug} |`)) orphans.push(`${dir}/${f}`);
    }
  }
  assert.deepEqual(orphans, [], `인덱스 미등록 문서 ${orphans.length}건`);
});

test('룰 J-역 — 시간축 인덱스의 slug 가 전부 실재한다 (삭제 방향)', () => {
  // 봉인은 기본적으로 절반만 닫힌다. 추가 방향만 막으면 삭제된 문서가 인덱스에 남는다.
  const recent = fs.readFileSync(path.join(REPO_ROOT, 'docs/INDEX-recent.md'), 'utf8');
  const actual = new Set<string>();
  for (const dir of ['docs/plans', 'docs/specs', 'docs/decisions', 'docs/adr']) {
    for (const f of fs.readdirSync(path.join(REPO_ROOT, dir)).filter((x) => x.endsWith('.md'))) {
      actual.add(f.replace(/^\d{4}-\d{2}-\d{2}-/, '').replace(/\.md$/, ''));
    }
  }
  const stale: string[] = [];
  for (const line of recent.split('\n')) {
    const m = line.match(/^\| \d{4}-\d{2}-\d{2} \| ([^|]+) \|/);
    if (m && !actual.has(m[1].trim())) stale.push(m[1].trim());
  }
  assert.deepEqual(stale, [], `실재하지 않는 문서가 인덱스에 ${stale.length}건`);
});

test('룰 K — 인덱스가 가리키는 파일이 전부 실재한다', () => {
  const broken: string[] = [];
  for (const idx of INDEX_FILES) {
    const body = fs.readFileSync(path.join(REPO_ROOT, idx), 'utf8');
    for (const m of body.matchAll(/\]\(\/(docs\/[^)]+\.md)\)/g)) {
      if (!fs.existsSync(path.join(REPO_ROOT, m[1]))) broken.push(`${idx} → ${m[1]}`);
    }
  }
  assert.deepEqual(broken, [], `깨진 링크 ${broken.length}건`);
});
// ★2026-08-21 — 「내 입력이 CI 트리거 paths 에 있는가」 단언을 여기서 지웠다.
//   CI 자동 실행을 껐고, 판별식은 이제 `.husky/pre-push` 가 **조건 없이 전량** 돌린다.
//   그 무조건성은 `scripts/workflow/discriminant-hook-wiring.test.ts` 가 강제한다.
//   경로 짝맞춤 목록이 필요 없어졌으므로 보장은 유지되고 유지비만 사라진다.
