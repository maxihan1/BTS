// 푸시 직전에 **바뀐 백엔드 모듈만** Gradle 테스트로 돌리는 훅 스크립트
//
// ## 왜 있나
//
// 2026-08-21 CI 자동 실행을 껐다. 그 전까지 백엔드 테스트를 돌리던 유일한 자리가 CI 였으므로,
// 지금 이 스크립트가 **백엔드 코드에 대한 유일한 기계 검증**이다.
//
// 훅에 넣는 것은 언제나 「사람이 꺼 버리면 끝」이라는 위험을 안는다. 그래서 두 가지를 지킨다.
//
//   1. **빠를 것.** 전 모듈(9개, 약 55분)이 아니라 바뀐 모듈 폐포만 돈다.
//      느리면 사람이 `--no-verify` 를 쓰고, 그 순간 lint·판별식까지 **함께** 죽는다.
//   2. **왜 멈췄는지 말할 것.** 「Docker 가 꺼졌다」와 「테스트가 깨졌다」를 구분한다.
//      PR #367 에서 설정이 멀쩡한데 데몬만 꺼져 있었고, 사람이 잡 12개 로그를 뒤졌다.
//
// ## ★마이그레이션 넓힘을 끈다 — 의도된 거래
//
// `selectModules` 의 기본값은 「마이그레이션이 있으면 전 모듈」이다. 스키마 영향은 모듈
// 그래프로 계산할 수 없기 때문이고, 그 판단 자체는 옳다. 그러나 로컬 푸시에서 그것을 그대로
// 지키면 마이그레이션 한 줄에 55분이고 훅은 죽는다.
//
// **그래서 여기서만 끈다.** 잃는 것을 명확히 적어 둔다 — 마이그레이션이 **다른 BC 의 테이블**을
// 건드리면 그 BC 의 테스트는 안 돈다. 되찾는 자리는 배포 전 전량 실행이다.
// 역의존 폐포(그래프로 계산 가능한 넓힘)는 그대로 살아 있다.
//
// 사용. node --experimental-strip-types scripts/workflow/push-backend-tests.ts
// 판별식. scripts/workflow/push-backend-tests.test.ts

import { execFileSync, spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { allModules, selectModules } from './select-backend-modules.ts'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')

/** 이 값이 `1` 이면 건너뛴다. 탈출구가 없으면 사람이 `--no-verify` 를 쓰고 훅 전체가 죽는다. */
export const SKIP_ENV = 'BTS_SKIP_MODULE_TEST'

/** 백엔드 변경으로 볼 경로 접두. */
const BACKEND_PREFIX = 'backend/'

/**
 * 푸시될 커밋들이 건드린 파일. 기준을 못 정하면 `null`.
 *
 * `null` 과 `[]` 를 **구분한다.** 전자는 「모른다」이고 후자는 「없다」다. 둘을 뭉개면
 * 판정 실패가 조용한 통과로 바뀐다 — 이 저장소가 반복해 물린 양식이다.
 *
 * ★본체는 `diff-base.ts` 하나다(2026-09-10). 종전에는 사본이 6벌이었고 여섯이 동시에
 *   main 위에서 기준을 HEAD 로 잡았다. 계약. scripts/workflow/diff-base.test.ts
 */
// ★재수출(`export … from`)이 아니라 **임포트 + 재수출**이다. 전자는 이 파일 안에서
//   이름을 안 잡아서 본문의 `changedFiles()` 가 런타임에 ReferenceError 로 죽는다 —
//   판별식이 소스 글자만 보므로 690건 초록인 채로 통과했다(2026-09-10 실측).
import { changedFiles, FALLBACK_BASE } from './diff-base.ts'
export { changedFiles, FALLBACK_BASE }

/** Docker 데몬이 살아 있는지. Testcontainers 를 쓰는 테스트가 256곳이라 없으면 전부 깨진다. */
export function dockerAlive(): boolean {
  const bin = process.env.BTS_DOCKER_BIN ?? 'docker'
  const r = spawnSync(bin, ['info'], { stdio: 'ignore' })
  return r.status === 0
}

function main(): number {
  if (process.env[SKIP_ENV] === '1') {
    process.stderr.write(`⏭  ${SKIP_ENV}=1 — 백엔드 모듈 테스트를 건너뛴다.\n`)
    return 0
  }

  const files = changedFiles()
  if (files === null) {
    // ★조용히 통과시키지 않는다. 무엇을 못 했는지 크게 말하고 사람이 판단하게 한다.
    //   여기서 exit 1 로 막지 않는 이유 — git 상태의 가장자리(얕은 클론·detached HEAD)에서
    //   푸시가 통째로 막히면 훅이 미움받고 결국 꺼진다.
    process.stderr.write(
      '⚠️  비교 기준을 못 정했다 — 백엔드 모듈 테스트를 **0회** 돌렸다.\n' +
        `    upstream 도 ${FALLBACK_BASE} 도 못 읽었다. 푸시 자체는 막지 않는다.\n` +
        '    필요하면 직접. cd backend && ./gradlew test\n',
    )
    return 0
  }

  const backend = files.filter((f) => f.startsWith(BACKEND_PREFIX))
  if (backend.length === 0) {
    process.stderr.write('✓ 백엔드 변경 없음 — 모듈 테스트 생략.\n')
    return 0
  }

  const picked = selectModules(files, [], { widenOnMigration: false })
  process.stderr.write(
    `▶ 백엔드 모듈 ${picked.modules.length}/${allModules().length} — ${picked.modules.join(' · ')}\n` +
      `  사유. ${picked.reason}\n`,
  )

  if (!dockerAlive()) {
    // ★「데몬 부재」를 「테스트 실패」와 구분한다. 뭉개면 사람이 엉뚱한 곳을 판다.
    process.stderr.write(
      '❌ Docker 데몬이 응답하지 않는다 — Testcontainers 테스트가 전부 깨진다.\n' +
        '    이것은 코드 문제가 아니다. Docker Desktop 을 켜고 다시 푸시할 것.\n' +
        `    급하면. ${SKIP_ENV}=1 git push\n`,
    )
    return 1
  }

  const tasks = picked.modules.map((m) => `:modules:${m}:test`)
  try {
    execFileSync('./gradlew', [...tasks, '--console=plain'], {
      cwd: path.join(REPO_ROOT, 'backend'),
      stdio: 'inherit',
    })
    return 0
  } catch {
    process.stderr.write(
      `\n❌ 백엔드 모듈 테스트 실패. 위 Gradle 출력을 읽을 것.\n` +
        `    이 훅을 건너뛰려면. ${SKIP_ENV}=1 git push  (권장하지 않는다)\n`,
    )
    return 1
  }
}

// 판별식이 import 할 때는 실행하지 않는다.
if (process.argv[1] !== undefined && process.argv[1].endsWith('push-backend-tests.ts')) {
  process.exit(main())
}
