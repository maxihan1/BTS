// 푸시 직전에 **바뀐 프론트 소스를 import 하는 테스트만** vitest 로 돌리는 훅 스크립트
//
// ## 왜 있나
//
// 2026-09-04 진단. 프론트 테스트 9,755개(640파일)가 **커밋·푸시 어느 훅에서도 안 돌고 있었다.**
// 백엔드는 `push-backend-tests.ts` 가 최소한 바뀐 모듈을 잡아 주는데 프론트에는 그 자리가 없었다.
// CI 자동 실행이 꺼져 있으므로(2026-08-21), `bts-impl` 최종 점검을 사람이 건너뛰면
// **프론트 검증 0회로 머지**된다. 이 스크립트가 그 구멍을 막는다.
//
// ## ★전량을 돌지 않는 이유는 백엔드와 같다
//
// 훅이 느리면 사람이 `--no-verify` 를 쓰고, 그 순간 판별식과 lint 까지 **함께** 죽는다.
// 그래서 `vitest --related` 로 좁힌다 — 바뀐 파일을 import 하는 테스트만 고른다.
// 공용 유틸이 바뀌면 그것을 쓰는 테스트가 모듈 그래프를 타고 자동으로 딸려 온다.
//
// 좁힘 판정과 전량 넓힘 규칙은 `select-test-scope.ts` 한 곳에만 있다. 여기서 다시 적지 않는다 —
// 두 자리가 각자 판정하면 서로를 안 보고 갈린다.
//
// 사용. node --experimental-strip-types scripts/workflow/push-frontend-tests.ts
// 판별식. scripts/workflow/push-frontend-tests.test.ts

import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { changedFiles, frontendScope, vitestArgs } from './select-test-scope.ts'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')

/** 이 값이 `1` 이면 건너뛴다. 탈출구가 없으면 사람이 `--no-verify` 를 쓰고 훅 전체가 죽는다. */
export const SKIP_ENV = 'BTS_SKIP_FRONTEND_TEST'

/** worktree 에서는 `pnpm` 래퍼가 죽는다. 바이너리를 직접 부른다. */
export const VITEST_BIN = 'apps/web/node_modules/.bin/vitest'

function main(): number {
  if (process.env[SKIP_ENV] === '1') {
    process.stderr.write(`⏭  ${SKIP_ENV}=1 — 프론트 테스트를 건너뛴다.\n`)
    return 0
  }

  const scope = frontendScope(changedFiles())
  if (scope.mode === 'skip') {
    process.stderr.write(`✓ ${scope.reason} — 프론트 테스트 생략.\n`)
    return 0
  }

  // ★cwd 는 `apps/web` 이다. 저장소 루트에서 돌리면 vitest.config.ts 를 못 찾고
  //   매칭 0건 → **테스트 0개를 돌고 초록**이 된다. 조용한 통과가 가장 위험하다.
  const WEB = path.join(REPO_ROOT, 'apps/web')
  const bin = path.join(REPO_ROOT, VITEST_BIN)
  if (!fs.existsSync(bin)) {
    // ★「도구 부재」와 「테스트 실패」를 구분한다. 뭉개면 사람이 엉뚱한 곳을 판다.
    //   worktree 의 node_modules 심볼릭이 안 걸린 상태가 대부분이다.
    process.stderr.write(
      `❌ vitest 를 찾지 못했다 — ${VITEST_BIN}\n` +
        '    이것은 코드 문제가 아니다. worktree 라면 node_modules 심볼릭이 빠졌다.\n' +
        `    ln -s <repo>/apps/web/node_modules apps/web/node_modules\n` +
        `    급하면. ${SKIP_ENV}=1 git push\n`,
    )
    return 1
  }

  // ★`related` 는 서브커맨드다. vitest 4 에 `--related` 플래그는 없다 (CACError 로 죽는다).
  const args = scope.mode === 'all' ? ['run'] : ['related', '--run', ...vitestArgs(scope)]
  process.stderr.write(
    `▶ 프론트 테스트 — ${scope.reason}\n` +
      (scope.mode === 'all' ? '  전량으로 넓혔다.\n' : `  대상 소스 ${scope.files.length}개\n`),
  )

  try {
    execFileSync(bin, args, { cwd: WEB, stdio: 'inherit' })
    return 0
  } catch {
    process.stderr.write(
      '\n❌ 프론트 테스트 실패. 위 vitest 출력을 읽을 것.\n' +
        `    이 훅을 건너뛰려면. ${SKIP_ENV}=1 git push  (권장하지 않는다)\n`,
    )
    return 1
  }
}

// 판별식이 import 할 때는 실행하지 않는다.
if (process.argv[1] !== undefined && process.argv[1].endsWith('push-frontend-tests.ts')) {
  process.exit(main())
}
