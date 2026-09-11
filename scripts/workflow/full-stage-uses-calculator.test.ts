// 전량 stage 가 명령을 다시 적지 않고 계산기를 쓰는지 · 넓힘 판정이 도달 가능한지 대조
//
// ## 무엇을 막는가 — 2026-09-11 감사에서 적발한 3건
//
// ### ① 넓힐수록 검증이 줄었다
//
// `stage('전량')` 이 실행할 명령 목록을 **손으로** 갖고 있었다. 그래서 「계산기가 내는
// 블록」과 「전량 stage 의 블록」이라는 두 목록이 생겼고, 실제로 갈려 있었다 —
// 계산기 블록에는 E2E 줄이 있는데 전량 stage 에는 **없었다.**
//
//     RUN_FULL='true' → stage('빠른 게이트') skip   ← E2E 를 내는 유일한 자리
//                     → stage('전량') 실행          ← E2E 가 없다
//                     ⇒ E2E 0회
//
// 조합 위험을 받는다고 선언한 **야간 크론**도 그 경로다.
//
// ### ② requiresFullBuild 가 모든 backend 변경에 발화했다
//
// `WIDEN_PREFIXES` 에 `'backend/'` 가 있는데 `requiresFullBuild` 에는 `moduleOf()` 게이트가
// 없었다(같은 파일 `selectModules` 에는 있다). 실측.
//
//     requiresFullBuild(['backend/modules/notification/src/main/kotlin/Foo.kt']) → true
//
// 백엔드 파일 하나만 고쳐도 빠른 게이트가 통째로 꺼졌다. ①과 겹치면 **백엔드를 건드린
// 모든 main 머지에서 E2E 가 0회**다. 「main 머지도 계산기가 정한 범위로 돈다」도 무효였다.
//
// ### ③ FE_WIDEN 의 저장소 루트 항목이 도달 불가였다
//
// `frontendScope` 가 `apps/web/` 필터를 넓힘 판정보다 **먼저** 걸었다. `FE_WIDEN` 의
// `package.json` `pnpm-lock.yaml` `pnpm-workspace.yaml` `.nvmrc` 넷은 저장소 루트라
// 그 필터를 통과하지 못한다 — 단독 변경이면 영원히 발화하지 않았다.
//
//     frontendScope(['pnpm-lock.yaml']) → mode='skip' · '프론트 변경 없음'
//
// 의존성 업그레이드 PR 이 **프론트 테스트 0건**으로 초록이었다.
// 기존 판별식은 넓힘 항목에 `apps/web/src/routes/a.tsx` 를 **항상 끼워** 불러서
// 단독 케이스를 한 번도 재지 않았다 — 짝 파일이 가드를 대신 발화시킨 가짜 초록이다.
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { FE_WIDEN, FRONTEND_PREFIX, frontendScope } from './select-test-scope.ts';
import { CI_DEFINITION_PREFIXES, requiresFullBuild } from './select-backend-modules.ts';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const JENKINSFILE = join(ROOT, 'Jenkinsfile');

/** `stage('<이름>')` 본문 — 다음 stage 직전까지. */
function bodyOf(src: string, stageName: string): string {
  const at = src.indexOf(`stage('${stageName}')`);
  assert.ok(at >= 0, `stage('${stageName}') 를 못 찾았다.`);
  const next = src.indexOf("stage('", at + 10);
  return src.slice(at, next === -1 ? undefined : next);
}

describe('① 전량 stage 는 명령을 다시 적지 않는다', () => {
  test('★★전량 stage 가 계산기를 부른다', () => {
    const body = bodyOf(readFileSync(JENKINSFILE, 'utf-8'), '전량');
    assert.match(
      body,
      /BTS_FORCE_FULL=1[^\n]*select-test-scope\.ts/,
      '전량 stage 가 계산기를 안 쓴다 — 명령을 손으로 적으면 두 목록이 된다.\n' +
        '★실제로 갈려 있었다. 계산기 블록에는 E2E 가 있는데 전량 stage 에는 없어서,\n' +
        '  넓히는 순간 E2E 가 0회가 됐다(야간 크론 포함).',
    );
  });

  test('★★전량 stage 가 gradle·vitest 를 직접 부르지 않는다', () => {
    const body = bodyOf(readFileSync(JENKINSFILE, 'utf-8'), '전량');
    const direct = body
      .split('\n')
      .filter((l) => !/^\s*(#|\*|\/\*)/.test(l))
      .filter((l) => /gradlew\s|vitest|pnpm --filter/.test(l));
    assert.deepEqual(
      direct.map((l) => l.trim()),
      [],
      '전량 stage 가 테스트 명령을 직접 부른다 — 계산기와 두 목록이 된다.\n' +
        '  계산기가 내는 블록만 실행해야 한다.',
    );
  });

  test('★빠른 게이트와 전량이 같은 계산기를 쓴다 (비-공허 짝)', () => {
    // ★이 짝이 없으면 위 단언은 「전량 stage 를 통째로 지워도」 통과한다.
    const src = readFileSync(JENKINSFILE, 'utf-8');
    for (const stage of ['빠른 게이트', '전량']) {
      assert.match(
        bodyOf(src, stage),
        /select-test-scope\.ts/,
        `stage('${stage}') 가 계산기를 안 부른다.`,
      );
    }
  });
});

describe('② requiresFullBuild 는 CI 정의 변경에만 발화한다', () => {
  test('★★백엔드 모듈 소스 단독 변경은 전량이 아니다', () => {
    for (const f of [
      'backend/modules/notification/src/main/kotlin/com/bts/Foo.kt',
      'backend/modules/issue-tracking/src/test/kotlin/Bar.kt',
    ]) {
      assert.equal(
        requiresFullBuild([f]),
        false,
        `${f} 단독 변경이 전량을 요구한다.\n` +
          '★그러면 빠른 게이트가 통째로 꺼지고, E2E 를 내는 유일한 자리가 사라진다.\n' +
          '  「main 머지도 계산기가 정한 범위로 돈다」도 백엔드 PR 에서 한 번도 성립하지 않는다.',
      );
    }
  });

  test('★★CI 정의 변경은 전량이다 (비-공허 짝)', () => {
    // ★이 짝이 없으면 위 단언은 `requiresFullBuild` 를 `() => false` 로 만들어도 통과한다.
    for (const f of [
      'Jenkinsfile',
      'Jenkinsfile.e2e',
      'scripts/workflow/diff-base.ts',
      'scripts/workflow/select-test-scope.ts',
      'scripts/workflow/select-backend-modules.ts',
      'infra/jenkins/bootstrap.sh',
    ]) {
      assert.equal(requiresFullBuild([f]), true, `${f} 변경이 전량을 요구하지 않는다.`);
    }
    assert.equal(requiresFullBuild(null), true, '기준을 모를 때 전량이 아니다.');
  });

  test('★★계산기가 import 하는 로컬 모듈이 전부 목록에 덮인다 (차집합)', () => {
    // 「목록」과 「실제 계산기 파일 집합」이 갈리는 것을 막는다. 계산기에 파일을 하나 더
    // 끼우고 목록을 안 고치면 그 파일을 고쳐도 전량으로 안 넓어진다 — 조용한 구멍이다.
    const seen = new Set<string>();
    const queue = ['scripts/workflow/requires-full-build.ts'];
    while (queue.length > 0) {
      const rel = queue.pop() as string;
      if (seen.has(rel)) continue;
      seen.add(rel);
      const src = readFileSync(join(ROOT, rel), 'utf-8');
      for (const m of src.matchAll(/from\s+'(\.\/[^']+)'/g)) {
        const dep = `scripts/workflow/${(m[1] as string).slice(2)}`;
        queue.push(dep);
      }
    }
    assert.ok(seen.size >= 4, `계산기 의존을 ${seen.size}개밖에 못 찾았다 — 대조가 공허해진다.`);

    const uncovered = [...seen]
      .filter((f) => !f.endsWith('.mjs')) // git-fixture-env 는 스크럽 헬퍼라 범위 판단과 무관
      .filter((f) => !CI_DEFINITION_PREFIXES.some((p) => f === p || f.startsWith(p)))
      .sort();
    assert.deepEqual(
      uncovered,
      [],
      `계산기가 쓰는데 CI_DEFINITION_PREFIXES 에 없는 파일: ${uncovered.join(', ')}\n` +
        '★그 파일을 고쳐도 전량으로 안 넓어진다 — 좁히는 실수를 그 PR 안에서 못 잡는다.',
    );
  });
});

describe('③ FE_WIDEN 은 단독으로 도달 가능하다', () => {
  test('★★저장소 루트 넓힘 항목이 단독 변경으로 발화한다', () => {
    const rootItems = FE_WIDEN.filter((w: string) => !w.startsWith(FRONTEND_PREFIX));
    assert.ok(
      rootItems.length > 0,
      'FE_WIDEN 에 저장소 루트 항목이 없다 — 아래 대조가 공허해진다(비-공허 짝).',
    );
    for (const item of rootItems) {
      const s = frontendScope([item]);
      assert.equal(
        s.mode,
        'all',
        `${item} 단독 변경이 프론트 전량으로 안 넓어진다 (mode=${s.mode} · ${s.reason}).\n` +
          '★넓힘 판정이 `apps/web/` 필터보다 뒤에 있으면 저장소 루트 항목은 영원히 도달 불가다.\n' +
          '  의존성 업그레이드 PR 이 프론트 테스트 0건으로 초록이 된다.',
      );
    }
  });

  test('★프론트와 무관한 변경은 여전히 생략된다 (비-공허 짝)', () => {
    // ★이 짝이 없으면 위 단언은 `frontendScope` 를 「항상 all」로 만들어도 통과한다.
    const s = frontendScope(['backend/modules/notification/src/main/kotlin/Foo.kt']);
    assert.equal(s.mode, 'skip', `프론트 무관 변경이 mode=${s.mode} 다 — 넓힘이 과하다.`);
  });
});
