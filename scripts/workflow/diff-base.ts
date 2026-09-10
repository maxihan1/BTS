// 「무엇이 바뀌었나」의 비교 기준을 정하는 한 곳 — 범위 계산기 전부가 여기를 본다
//
// ## 왜 한 곳인가
//
// 2026-09-10 이전에는 이 계산이 **5벌**이었다.
//   `select-test-scope.ts` · `push-backend-tests.ts` · `requires-full-build.ts` ·
//   `changed-e2e-specs.ts` · `Jenkinsfile`(inline groovy)
//
// 다섯이 전부 「`@{u}` 아니면 `origin/main`」이라고 적었고, **다섯이 동시에 같은 방식으로
// 틀렸다.** main 위에서는 `origin/main` 이 곧 HEAD 라 차집합이 항상 공집합이 된다.
// 서로를 검사하지 않는 목록이 다섯이면 하나가 썩어도 나머지가 알려주지 않는다.
//
// ## ★규칙 — 자기 자신은 기준이 될 수 없다
//
// `merge-base(base, HEAD) == HEAD` 는 「변경 없음」이 아니라 **「우리가 곧 base 다」**이다.
// 그때는 직전 커밋으로 물러난다. 물러날 곳도 없으면 `null`(모른다)이고, 호출자는 전량으로 넓힌다.
//
// `[]`(없다)와 `null`(모른다)을 끝까지 구분한다 — 뭉개는 순간 판정 실패가 조용한 통과가 된다.
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { gitFixtureEnv } from './git-fixture-env.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

/** 업스트림을 못 읽었을 때의 기준 후보 — 오류 문구가 이 이름을 쓴다. */
export const FALLBACK_BASE = 'origin/main';

/**
 * 업스트림이 없을 때 순서대로 시도할 ref.
 *
 * ★후보를 **목록**으로 받는다. `origin/main` 하나만 보면 원격을 안 붙인 클론이나
 *   원격 이름이 다른 클론에서 기준을 못 잡는다(`changed-paths.ts` 가 갖고 있던 동작).
 */
const FALLBACK_REFS = ['origin/main', 'main'] as const;

/**
 * CI 가 「직전 성공 빌드의 커밋」을 알고 있을 때 넘기는 환경변수.
 *
 * 이것이 있으면 최우선이다. `HEAD~1` 폴백은 **직전 한 커밋만** 덮으므로,
 * CI 가 몇 번 건너뛰거나 빌드가 중단된 사이 여러 머지가 쌓이면 놓치는 구간이 생긴다.
 * 젠킨스는 `GIT_PREVIOUS_SUCCESSFUL_COMMIT` 로 그 구간을 알려줄 수 있다.
 */
export const BASE_ENV = 'BTS_DIFF_BASE';

function git(cwd: string, args: string[]): string | null {
  const r = spawnSync('git', ['-c', 'core.quotePath=false', ...args], {
    cwd,
    encoding: 'utf-8',
    env: gitFixtureEnv(),
  });
  return r.status === 0 ? r.stdout.trim() : null;
}

/**
 * 비교 기준 커밋을 정한다.
 *
 * 순서.
 *   ① `BTS_DIFF_BASE` — CI 가 직전 성공 커밋을 안다
 *   ② 업스트림(`@{u}`) 과의 분기점 — 흔한 작업 브랜치
 *   ③ `origin/main` 과의 분기점 — 업스트림이 없을 때(젠킨스는 detached HEAD 다)
 *   ④ 분기점이 HEAD 자신이면 `HEAD~1` — **우리가 곧 main 인 경우**
 *
 * @param cwd 저장소 경로. 기본값은 이 저장소 루트
 * @returns 기준 커밋 SHA. 정할 수 없으면 `null`(= 호출자는 전량으로 넓힌다)
 */
export function resolveDiffBase(cwd: string = REPO_ROOT): string | null {
  const explicit = process.env[BASE_ENV]?.trim();
  if (explicit !== undefined && explicit !== '') {
    // 지정값이 이 저장소에서 안 풀리면 조용히 무시하지 않는다 — 못 풀면 모르는 것이다.
    return git(cwd, ['rev-parse', '--verify', `${explicit}^{commit}`]);
  }

  const upstream = git(cwd, ['rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{u}']);
  const candidates = upstream === null ? FALLBACK_REFS : [upstream];
  const merged = candidates
    .map((ref) => git(cwd, ['merge-base', ref, 'HEAD']))
    .find((sha) => sha !== null);
  if (merged === undefined || merged === null) return null;

  const head = git(cwd, ['rev-parse', 'HEAD']);
  if (head === null) return null;
  // ★핵심. 기준이 HEAD 자신이면 비교가 성립하지 않는다 — main 위에서 바로 이 상태다.
  if (merged !== head) return merged;

  return git(cwd, ['rev-parse', '--verify', 'HEAD~1^{commit}']);
}

/**
 * 기준 대비 바뀐 파일.
 *
 * `--no-renames` 가 없으면 **좁게 고른다.** git 이 rename 을 감지하면 새 경로 하나로
 * 접어서, 파일이 모듈 A → B 로 옮겨졌을 때 A 가 목록에서 통째로 빠진다. A 의 테스트가
 * 안 돌고 그것이 초록으로 보인다 — 넓게 고르는 실수는 느릴 뿐이지만 좁게 고르는 실수는
 * 검증 안 된 코드를 머지시킨다.
 * 계약. `scripts/workflow/select-backend-modules.test.ts` §--no-renames
 *
 * @param cwd 저장소 경로. 기본값은 이 저장소 루트
 * @returns 변경 파일 목록. 기준을 못 정하면 `null` — `[]`(변경 없음)과 다르다
 */
export function changedFiles(cwd: string = REPO_ROOT): string[] | null {
  const base = resolveDiffBase(cwd);
  if (base === null) return null;
  const out = git(cwd, ['diff', '--name-only', '--no-renames', base, 'HEAD']);
  if (out === null) return null;
  return out === '' ? [] : out.split('\n').filter((l) => l.trim() !== '');
}
