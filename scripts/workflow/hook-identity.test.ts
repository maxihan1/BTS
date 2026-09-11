// 실제로 실행될 훅이 **이 체크아웃의** 훅인지 대조 — worktree 에서 조용히 갈리는 자리
//
// ## 무엇을 막는가 — 2026-09-11 실측
//
// worktree 에서 푸시했는데 훅이 **메인 체크아웃의 낡은 파일**을 돌렸다.
//
//     $ git config core.hooksPath
//     /Users/…/Projects/BTS/.husky/_        ← **절대경로**
//
// husky 의 shim 은 `$(dirname $0)/../pre-push` 를 부르므로, 그 절대경로가 가리키는
// **메인 체크아웃의 `.husky/pre-push`** 가 실행된다. worktree 안의 훅은 안 돈다.
//
// 실측 결과. 이 저장소의 훅에는 「바뀐 E2E 를 푸시 전에 돌린다」 블록이 있는데,
// 실행된 훅에는 **그 블록이 없었다**(메인 체크아웃이 낡아 있었다).
//
//     저장소 .husky/pre-push   E2E_SPECS 블록 있음
//     실행된 훅                 없음 · 대신 철거된 push-backend-tests.ts 를 부름
//
// ## ★왜 판별식으로 못 잡고 있었나
//
// `changed-e2e-specs.test.ts` 의 배선 대조가 **저장소 파일**을 읽는다. 그 파일에는
// 블록이 있으니 초록이다. 「적혀 있다」와 「돈다」가 갈린 자리를 아무도 안 봤다 —
// 정책 1층(개발 워크플로우의 E2E 검증)이 통째로 무효인 채 전량 초록이었다.
//
// CLAUDE.md 의 함정 「worktree 가 husky 훅을 침묵 무력화」의 변종이다. 그쪽은 훅이
// **안 도는** 경우이고, 이쪽은 **낡은 사본이 도는** 경우다. 후자가 더 나쁘다 —
// 로그에 출력이 있어서 돌았다고 믿게 된다.
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// @ts-ignore — .mjs 는 타입 선언이 없다. 런타임 export 는 실재한다.
import { gitFixtureEnv } from './git-fixture-env.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

/** `core.hooksPath` 설정값. 없으면 null(= git 기본 `.git/hooks`). */
function hooksPath(): string | null {
  const r = spawnSync('git', ['config', '--get', 'core.hooksPath'], {
    cwd: ROOT,
    encoding: 'utf-8',
    env: gitFixtureEnv(),
  });
  const v = r.status === 0 ? r.stdout.trim() : '';
  return v === '' ? null : v;
}

describe('실행될 훅 ⟺ 이 체크아웃의 훅', () => {
  test('★★worktree 에서 실행될 pre-push 가 이 체크아웃의 것이다', (t) => {
    const hp = hooksPath();
    if (hp === null) {
      // CI 는 푸시를 안 하고 husky 도 안 건다. 검사 대상이 없다.
      t.skip('core.hooksPath 가 없다 — 훅을 쓰지 않는 환경(CI 등)이다.');
      return;
    }

    // husky shim 은 `$(dirname $0)/../pre-push` 를 실행한다.
    const shimDir = isAbsolute(hp) ? hp : resolve(ROOT, hp);
    const willRun = resolve(shimDir, '..', 'pre-push');
    const ours = join(ROOT, '.husky/pre-push');

    assert.ok(existsSync(ours), '이 체크아웃에 .husky/pre-push 가 없다.');
    assert.ok(
      existsSync(willRun),
      `실행될 훅 파일이 없다: ${willRun}\n` +
        '★훅이 **조용히 안 돈다** — 푸시는 막히지 않는다.',
    );

    assert.equal(
      readFileSync(willRun, 'utf-8'),
      readFileSync(ours, 'utf-8'),
      '실제로 실행될 훅이 이 체크아웃의 훅과 다르다.\n' +
        `  실행될 것  ${willRun}\n` +
        `  이 체크아웃 ${ours}\n\n` +
        '★`core.hooksPath` 가 **절대경로**면 어느 worktree 에서 푸시하든 그 경로의 훅이\n' +
        '  돈다. 훅을 고쳐도 그 수정이 안 돌고, 저장소 파일을 읽는 판별식은 초록이다 —\n' +
        '  「적혀 있다」와 「돈다」가 갈린다.\n\n' +
        '처방 (둘 중 하나).\n' +
        '  ① 메인 체크아웃을 최신으로 — `git -C <repo> checkout main && git pull --ff-only`\n' +
        '  ② 훅 경로를 상대경로로 — `git config core.hooksPath .husky/_`\n' +
        '     (git 은 상대 hooksPath 를 **현재 worktree 루트** 기준으로 푼다)',
    );
  });

  test('★비-공허 확인 — 비교 대상 두 경로를 실제로 구했다', (t) => {
    const hp = hooksPath();
    if (hp === null) {
      t.skip('core.hooksPath 가 없다.');
      return;
    }
    const shimDir = isAbsolute(hp) ? hp : resolve(ROOT, hp);
    const willRun = resolve(shimDir, '..', 'pre-push');
    assert.notEqual(willRun, '', '실행될 훅 경로를 못 구했다 — 위 대조가 공허해진다.');
    assert.match(willRun, /pre-push$/, `경로 계산이 깨졌다: ${willRun}`);
  });
});
