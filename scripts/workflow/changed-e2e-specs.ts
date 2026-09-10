// 푸시 대상에서 바뀐 Playwright 시나리오만 골라 출력하는 스크립트 — pre-push 훅이 부른다
//
// ## 무엇을 위한 것인가
//
// **기능을 고치면 E2E 도 고친다. 그 E2E 가 실제로 도는지는 CI 전에 확인한다.**
// 확인 없이 올라간 E2E 는 CI 와 배포 후 검증에서 그대로 쓰이므로, 푸시 전에 한 번 걸러야
// 「썼는데 한 번도 실행 안 된 테스트」가 생기지 않는다 — 그건 없는 것보다 나쁘다.
// 있다고 믿게 만들기 때문이다.
//
// ## 왜 전량이 아닌가
//
// `select-test-scope.ts` 는 E2E 판정을 갖고 있었지만 참이면 **인자 없는** `playwright test`
// 를 냈다 — 167파일 전량이고 2코어 실측 약 3시간이다(1파일 46초 · 3파일 165초 →
// 파일당 약 60초, 고정 비용 거의 0). 3시간짜리 명령은 아무도 안 돌린다.
// **가드가 있어도 쓰이지 않으면 없는 가드다.**
//
// ## 출력 계약
//
// `apps/web` 기준 상대경로를 공백으로 이어 한 줄. 없으면 **빈 출력**(훅이 아무것도 안 한다).
// 변경 목록을 못 구하면 역시 빈 출력이다 — 푸시 훅에서 3시간을 시작하지 않는다.
// 그 경우의 안전망은 CI 와 배포 후 전량이다.
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { gitFixtureEnv } from './git-fixture-env.mjs';
import { e2eSpecs } from './select-test-scope.ts';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

/** 푸시로 올라갈 변경 파일 목록. 기준을 못 잡으면 `null`. */
function changedFiles(): string[] | null {
  const git = (args: string[]) =>
    spawnSync('git', ['-c', 'core.quotePath=false', ...args], {
      cwd: REPO_ROOT,
      encoding: 'utf-8',
      env: gitFixtureEnv(),
    });

  // 업스트림이 있으면 그것과의 차이가 곧 「올라갈 것」이다.
  const upstream = git(['rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{u}']);
  const base = upstream.status === 0 ? '@{u}' : 'origin/main';

  const diff = git(['diff', '--name-only', '--no-renames', `${base}...HEAD`]);
  if (diff.status !== 0) return null;
  return diff.stdout.split('\n').filter((l) => l.trim().length > 0);
}

export function main(): number {
  const specs = e2eSpecs(changedFiles());
  // `null`(전량)도 빈 출력으로 떨어뜨린다 — 푸시 훅에서 3시간을 시작하지 않는다.
  if (specs && specs.length > 0) process.stdout.write(` ${specs.join(' ')}`);
  return 0;
}

const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop() ?? ' ');
if (isMain) process.exit(main());
