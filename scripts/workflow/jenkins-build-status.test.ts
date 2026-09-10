// 젠킨스 빌드 상태 조회기의 계약 — 3분기 종료 코드 · 「판정 불가」를 통과로 뭉개지 않음
//
// ## 왜 이 파일이 있나
//
// 두 가지를 지킨다.
//
// 1. **종료 코드가 셋으로 갈린다.** 0(초록) · 1(빨강) · 2(판정 불가).
//    이 도구는 `bts-merge` Step 1 과 게이트 2 요약이 읽는 자리다. 2 를 0 으로 뭉개면
//    「빌드가 없다」가 「통과했다」가 되고, 그것이 `gh pr checks` 의 「체크 0건」과
//    정확히 같은 함정이다. 환경이 어떻든 **문서화되지 않은 종료 코드로 죽지 않는다**를 잰다.
//
// 2. **git 격리.** 이 스크립트는 `git rev-parse` 를 부른다. 훅 컨텍스트에서 상속된 `GIT_DIR`
//    가 자식의 `cwd` 를 이기므로, 스크럽 없이 부르면 실저장소를 건드릴 수 있다.
//    `git-fixture-isolation.test.ts` 의 sweep 이 「git 을 부르는 전량이 **자식으로 돈다**」를
//    요구하는 이유가 그것이고, 이 파일이 그 자식 실행을 만든다.
//    ★2026-09-09 — 이 테스트를 안 만들어 그 sweep 이 red 를 냈다. 규약이 먼저 잡았다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
// ★`gitFixtureEnv` 를 임포트하지 않는다. 이 파일은 git 을 **직접 부르지 않고** node 만 spawn 한다.
//   `git-spawn-sweep` 은 「헬퍼 임포트 집합 ⟺ git spawn 집합」의 **양방향** 일치를 요구하므로,
//   안 부르면서 임포트하면 「배선만 남은 자리」로 red 가 된다(2026-09-09 실측).
//   자식이 부르는 git 은 대상 스크립트가 자기 안에서 스크럽한다.
//
// ★import 가 필수다. sweep 은 「git 을 부르는 파일을 테스트가 **import** 하거나 그 자신이
//   테스트여야 한다」를 요구하고, spawn 만으로는 인정하지 않는다 — 자식 후보는 테스트 파일뿐이다.
//   대상이 `isMain` 가드를 갖고 있어 import 해도 SSH 가 나가지 않는다.
import { main } from './jenkins-build-status.ts';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const SCRIPT = 'scripts/workflow/jenkins-build-status.ts';

function run(args: string[]) {
  return spawnSync('node', ['--experimental-strip-types', SCRIPT, ...args], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    timeout: 60_000,
  });
}

describe('젠킨스 빌드 상태 조회기', () => {
  test('★종료 코드가 문서화된 셋 중 하나다 (0·1·2)', () => {
    // 환경에 따라 어느 값이 나오는지는 다르다 — 젠킨스가 없는 기계에서는 2 다.
    // 잴 수 있는 것은 **문서화 안 된 코드로 죽지 않는다**이고, 그것이 이 도구의 계약이다.
    const r = run([]);
    assert.ok(
      [0, 1, 2].includes(r.status ?? -1),
      `종료 코드 ${r.status} — 0(초록)·1(빨강)·2(판정 불가) 밖이다.\n` +
        `stdout: ${r.stdout}\nstderr: ${r.stderr}`,
    );
  });

  test('★판정을 사람이 읽을 수 있는 한 줄로 낸다', () => {
    const r = run([]);
    assert.match(
      r.stdout,
      /초록|빨강|판정 불가/,
      `판정 문구가 없다 — 게이트 2 요약이 인용할 것이 없다.\nstdout: ${r.stdout}`,
    );
  });

  test('★없는 브랜치를 물으면 통과로 답하지 않는다', () => {
    // 다른 브랜치의 초록을 이 브랜치의 초록으로 읽는 것이 가장 나쁜 오독이다.
    const r = run(['definitely-not-a-real-branch-xyz']);
    assert.notEqual(
      r.status,
      0,
      `존재하지 않는 브랜치에 초록(0)을 냈다 — 가짜 초록이다.\nstdout: ${r.stdout}`,
    );
  });

  test('★import 해도 실행되지 않는다 (isMain 가드)', () => {
    // 가드가 없으면 이 파일을 import 하는 순간 SSH 가 나가고, 그러면 sweep 이 요구하는
    // import 배선 자체를 걸 수 없다. 함수로 존재하되 부르지 않았음을 확인한다.
    assert.equal(typeof main, 'function', 'main 이 export 되지 않았다 — 테스트가 import 할 수 없다');
  });

  test('★스크럽 헬퍼를 거쳐 git 을 부른다', () => {
    const src = fs.readFileSync(path.join(REPO_ROOT, SCRIPT), 'utf8');
    const spawns = src.split('\n').filter((l) => /execFileSync\('git'/.test(l)).length;
    assert.ok(spawns > 0, '양성 대조군 — git 을 부르는 자리가 하나도 없다(추출기가 깨졌다)');
    assert.match(src, /gitFixtureEnv/, '스크럽 헬퍼를 임포트하지 않는다');
    // 호출부마다 env 를 넘기는지는 `git-spawn-sweep.ts` 가 저장소 전량에 대해 본다.
  });
});
