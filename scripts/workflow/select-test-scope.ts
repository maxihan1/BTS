// 변경 파일에서 **이번 작업이 실제로 돌려야 할 테스트 범위**를 계산하는 단일 진입점
//
// ## 왜 있나
//
// 2026-09-04 진단. `bts-impl` Step 4 가 `./gradlew test`(8,734개) + `pnpm test`(9,755개)를
// **무조건 전량** 돌고 있었다. 알림 모듈 한 곳을 고쳐도 18,489개가 돈다 — 무관한 것이 97.6%.
//
// 좁히는 장치가 없어서가 아니었다. 세 겹이 이미 있었다.
//
//   | 정밀도        | 장치                              | 실제 실행                |
//   |---------------|-----------------------------------|--------------------------|
//   | 테스트 1개    | plan 의 `**검증**:` 칸            | **읽는 코드가 0줄**      |
//   | 모듈 1개      | `CLAUDE.md` 의 `:modules:<bc>:test` | 푸시 훅에서만           |
//   | 변경 기반 폐포 | `selectModules()`                 | 푸시 훅에서만           |
//
// 정밀도 순서가 거꾸로였다 — 가장 정밀한 정의는 문서에만 남고, 가장 거친 정의(전량)가 실행됐다.
// 이 파일이 그 셋을 하나로 모아 **구현 종료 시점에도** 쓸 수 있게 한다.
//
// ## ★넓힘의 방향은 한쪽뿐이다
//
// `select-backend-modules.ts:12-20` 이 세운 원칙을 그대로 따른다.
//
//   | 방향             | 결과                                        |
//   |------------------|---------------------------------------------|
//   | 너무 넓게 고름   | 시간만 든다 — 고치려던 문제가 그대로        |
//   | **너무 좁게 고름** | **검증 안 된 코드가 초록으로 머지된다**    |
//
// 그래서 **판정을 못 하는 모든 경우의 기본값은 전량**이다. 비교 기준을 못 읽었을 때,
// 설정·의존성이 바뀌었을 때, 모듈 참조를 파싱 못 했을 때 — 전부 전량으로 넓힌다.
//
// ## 프론트를 좁히는 방법 — `vitest related`
//
// 백엔드는 Gradle 모듈 그래프로 역의존 폐포를 계산한다. 프론트에는 그런 단위가 없어서
// vitest 자체의 모듈 그래프를 쓴다 — `vitest related --run <파일들>` 은 그 파일을
// **import 하는** 테스트를 전부 찾아 돌린다. 공용 유틸이 바뀌면 그것을 쓰는 테스트가
// 자동으로 딸려 온다. 실측(2026-09-04) — `src/lib/active-project.ts` 하나로 20파일 424개.
// 전량 640파일 9,755개 대비 4.3% 다.
//
// ★`--related` 가 아니라 `related` **서브커맨드**다. vitest 4 에서 플래그 형태는 없고,
//   틀린 형태를 쓰면 `CACError: Unknown option` 으로 죽는다(초록이 아니라 빨강이라 다행이다).
//   `select-test-scope.test.ts` 가 `vitest related --help` 를 실제로 실행해 이 형태를 지킨다.
//   경로는 `apps/web` 기준 상대경로여야 한다 — 그래서 렌더에서 접두를 떼고 cwd 를 옮긴다.
//
// 그래프가 못 닫는 것(설정·의존성·전역 셋업)은 `FE_WIDEN` 으로 전량 처리한다.
//
// 사용. node --experimental-strip-types scripts/workflow/select-test-scope.ts [--plan <경로>]
// 판별식. scripts/workflow/select-test-scope.test.ts

import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

import { allModules, selectModules } from './select-backend-modules.ts'
// @ts-ignore — .mjs 는 타입 선언이 없다. 런타임 export 는 실재한다.
import { gitFixtureEnv } from './git-fixture-env.mjs'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')

/** 백엔드 변경으로 볼 경로 접두. `push-backend-tests.ts` 와 같은 값이어야 한다. */
export const BACKEND_PREFIX = 'backend/'

/** 프론트 변경으로 볼 경로 접두. */
export const FRONTEND_PREFIX = 'apps/web/'

/** vitest 가 도는 소스 루트. 이 아래의 변경만 `--related` 로 좁힐 수 있다. */
export const FRONTEND_SRC = 'apps/web/src/'

/** Playwright 시나리오. vitest 대상이 아니다. */
export const FRONTEND_E2E = 'apps/web/e2e/'

/**
 * 하나라도 걸리면 프론트를 **전량**으로 넓히는 경로.
 *
 * 이유가 각각 다르다.
 *   - 설정/빌드 — 모듈 그래프 밖에서 모든 테스트의 동작을 바꾼다.
 *   - 의존성 잠금 — 라이브러리 동작이 바뀐다. import 그래프로는 안 보인다.
 *   - 전역 셋업/목 — 모든 테스트가 암묵적으로 의존한다.
 */
export const FE_WIDEN: readonly string[] = [
  'package.json',
  'pnpm-lock.yaml',
  'pnpm-workspace.yaml',
  '.nvmrc',
  'apps/web/package.json',
  'apps/web/vite.config.ts',
  'apps/web/vitest.config.ts',
  'apps/web/tsconfig.json',
  'apps/web/eslint.config.js',
  // 전역 셋업. `vitest.config.ts` 의 `setupFiles` 가 가리키는 곳 —
  // 판별식이 그 설정을 직접 읽어 이 목록이 덮는지 대조한다(경로가 바뀌면 red).
  'apps/web/src/test/',
  'apps/web/src/mocks/',
]

/** 비교 기준을 못 정했을 때의 마지막 후보. `push-backend-tests.ts` 와 같다. */
export const FALLBACK_BASE = 'origin/main'

function git(args: string[]): string | null {
  const r = spawnSync('git', args, { cwd: REPO_ROOT, encoding: 'utf-8', env: gitFixtureEnv() })
  if (r.status !== 0) return null
  return r.stdout.trim()
}

/**
 * 브랜치가 건드린 파일. 기준을 못 정하면 `null`.
 *
 * `null`(모른다)과 `[]`(없다)를 구분한다 — 뭉개면 판정 실패가 조용한 통과로 바뀐다.
 */
export function changedFiles(): string[] | null {
  const base = git(['rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{u}']) ?? FALLBACK_BASE
  const merged = git(['merge-base', base, 'HEAD'])
  if (merged === null) return null
  // ★`--no-renames` 가 없으면 **좁게 고른다.** git 은 rename 을 감지하면 새 경로 하나로
  //   접어서, 파일이 모듈 A → B 로 옮겨졌을 때 A 가 목록에서 통째로 빠진다. A 의 테스트가
  //   안 돌고 그것이 초록으로 보인다 — 넓게 고르는 실수는 느릴 뿐이지만 좁게 고르는 실수는
  //   검증 안 된 코드를 머지시킨다.
  //
  // ★이 플래그는 종전에 `.github/workflows/backend-ci.yml` 의 select 잡에만 있었다.
  //   젠킨스는 YAML 대신 이 계산기가 내부에서 diff 하므로 그 배선이 통째로 사라져 있었다
  //   (2026-09-09 · P4 포팅 중 적발). 「YAML 이 하던 일을 계산기가 물려받았다」는 이전에서
  //   가장 놓치기 쉬운 자리다 — 옮긴 쪽에는 그 줄이 애초에 없기 때문이다.
  //   계약. scripts/workflow/select-backend-modules.test.ts §--no-renames
  const out = git(['diff', '--name-only', '--no-renames', merged, 'HEAD'])
  if (out === null) return null
  return out === '' ? [] : out.split('\n')
}

export interface FrontendScope {
  /** `skip` 프론트 변경 없음 · `related` 바뀐 파일을 import 하는 테스트만 · `all` 전량 */
  mode: 'skip' | 'related' | 'all'
  /** `related` 일 때 vitest 에 넘길 파일들. 그 밖에는 빈 배열. */
  files: string[]
  reason: string
}

/**
 * 프론트 테스트 범위.
 *
 * `files` 가 `null` 이면 「기준을 모른다」이므로 전량이다.
 */
export function frontendScope(files: string[] | null): FrontendScope {
  if (files === null) {
    return { mode: 'all', files: [], reason: '비교 기준을 못 정했다 — 전량으로 넓힌다' }
  }

  const fe = files.filter((f) => f.startsWith(FRONTEND_PREFIX))
  if (fe.length === 0) {
    return { mode: 'skip', files: [], reason: '프론트 변경 없음' }
  }

  const widen = files.filter((f) => FE_WIDEN.some((w) => (w.endsWith('/') ? f.startsWith(w) : f === w)))
  if (widen.length > 0) {
    return { mode: 'all', files: [], reason: `모듈 그래프 밖 변경 — ${widen.join(' · ')}` }
  }

  // e2e 는 vitest 대상이 아니다. src 밖의 프론트 변경(스크립트·정적파일)은 그래프로 못 닫으므로 전량.
  const outsideSrc = fe.filter((f) => !f.startsWith(FRONTEND_SRC) && !f.startsWith(FRONTEND_E2E))
  if (outsideSrc.length > 0) {
    return { mode: 'all', files: [], reason: `src 밖 프론트 변경 — ${outsideSrc.join(' · ')}` }
  }

  const related = fe.filter((f) => f.startsWith(FRONTEND_SRC))
  if (related.length === 0) {
    return { mode: 'skip', files: [], reason: 'e2e 만 변경 — vitest 대상 없음' }
  }

  return {
    mode: 'related',
    files: related,
    reason: `바뀐 소스 ${related.length}개를 import 하는 테스트만`,
  }
}

/**
 * vitest 에 넘길 인자. **`apps/web` 기준 상대경로**로 바꾼다.
 *
 * vitest 는 `apps/web` 을 cwd 로 돌아야 설정(`vitest.config.ts`)을 찾는다. 저장소 루트 기준
 * 경로를 그대로 주면 매칭이 0건이 되고 — **테스트 0개를 돌고 초록**이 된다. 조용한 통과라
 * 가장 위험한 형태다.
 */
export function vitestArgs(scope: FrontendScope): string[] {
  return scope.files.map((f) => f.slice(FRONTEND_PREFIX.length))
}

/** Playwright 시나리오가 바뀌었나. 바뀌었으면 e2e 를 돌려야 한다. */
export function e2eTouched(files: string[] | null): boolean {
  if (files === null) return true
  return files.some((f) => f.startsWith(FRONTEND_E2E))
}

/**
 * plan 이 task 마다 적어 둔 검증 명령을 전부 뽑는다.
 *
 * 서식 정본. `.claude/skills/bts-plan/plan-format.md` 의 `**검증**:` 줄.
 * 그 파일의 템플릿과 이 추출기가 서로를 검사한다 — `select-test-scope.test.ts` 참조.
 * 한쪽만 바뀌면 조용히 썩는 「두 목록」 양식을 막기 위한 짝이다.
 */
export function planVerifyCommands(planText: string): string[] {
  const out: string[] = []
  for (const line of planText.split('\n')) {
    const m = /^\s*(?:[-*]\s*)?\*\*검증\*\*\s*[:：]\s*(.+)$/.exec(line)
    if (m === null) continue
    for (const cmd of m[1].matchAll(/`([^`]+)`/g)) {
      const v = cmd[1].trim()
      if (v !== '') out.push(v)
    }
  }
  return out
}

export interface TestScope {
  backendModules: string[]
  backendReason: string
  frontend: FrontendScope
  e2e: boolean
  planVerify: string[]
}

export function computeScope(files: string[] | null, planText: string | null): TestScope {
  const backend = files === null ? null : files.filter((f) => f.startsWith(BACKEND_PREFIX))

  let backendModules: string[]
  let backendReason: string
  if (files === null) {
    backendModules = allModules()
    backendReason = '비교 기준을 못 정했다 — 전 모듈로 넓힌다'
  } else if (backend!.length === 0) {
    backendModules = []
    backendReason = '백엔드 변경 없음'
  } else {
    // ★마이그레이션 넓힘을 켠 채로 부른다. 푸시 훅과 다른 선택이다 —
    //   여기는 「푸시 한 번」이 아니라 「작업 종료 점검」이라 스키마 영향을 놓치면 안 된다.
    const picked = selectModules(files, [], { widenOnMigration: true })
    backendModules = picked.modules
    backendReason = picked.reason
  }

  return {
    backendModules,
    backendReason,
    frontend: frontendScope(files),
    e2e: e2eTouched(files),
    planVerify: planText === null ? [] : planVerifyCommands(planText),
  }
}

/** 사람이 읽고 그대로 실행할 수 있는 명령 블록. */
export function renderCommands(scope: TestScope): string {
  const lines: string[] = []

  if (scope.backendModules.length === 0) {
    lines.push('# 백엔드 — 생략 (변경 없음)')
  } else {
    const tasks = scope.backendModules.map((m) => `:modules:${m}:test`).join(' ')
    const lint = scope.backendModules.map((m) => `:modules:${m}:ktlintCheck :modules:${m}:detekt`).join(' ')
    lines.push(`# 백엔드 ${scope.backendModules.length}/${allModules().length} — ${scope.backendReason}`)
    lines.push(`(cd backend && ./gradlew ${tasks} --console=plain)`)
    lines.push(`(cd backend && ./gradlew ${lint} --rerun-tasks --console=plain)`)
  }

  lines.push('')
  if (scope.frontend.mode === 'skip') {
    lines.push(`# 프론트 — 생략 (${scope.frontend.reason})`)
  } else if (scope.frontend.mode === 'all') {
    lines.push(`# 프론트 전량 — ${scope.frontend.reason}`)
    lines.push('(cd apps/web && node_modules/.bin/vitest run)')
  } else {
    lines.push(`# 프론트 — ${scope.frontend.reason}`)
    lines.push(`(cd apps/web && node_modules/.bin/vitest related --run ${vitestArgs(scope.frontend).join(' ')})`)
  }

  lines.push('')
  lines.push(scope.e2e ? '# E2E — 시나리오가 바뀌었다. 돌린다.' : '# E2E — 생략 (시나리오 변경 없음)')
  if (scope.e2e) lines.push('apps/web/node_modules/.bin/playwright test')

  if (scope.planVerify.length > 0) {
    lines.push('')
    lines.push(`# plan 이 지정한 검증 ${scope.planVerify.length}건 — 위 범위와 별개로 반드시 돈다`)
    for (const c of scope.planVerify) lines.push(c)
  }

  lines.push('')
  lines.push('# 판별식 — ★조건 없이 전량. 경로별 선별은 두 목록이 서로를 안 보게 만든다.')
  lines.push("node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'")

  return lines.join('\n')
}

function main(argv: string[]): number {
  const planIdx = argv.indexOf('--plan')
  let planText: string | null = null
  if (planIdx !== -1) {
    const p = argv[planIdx + 1]
    if (p === undefined) {
      process.stderr.write('--plan 뒤에 경로가 필요하다.\n')
      return 2
    }
    // 파일이 없으면 「검증 0건」이 아니라 오류다 — 조용한 축소를 막는다.
    const r = spawnSync('cat', [p], { encoding: 'utf-8' })
    if (r.status !== 0) {
      process.stderr.write(`plan 파일을 읽지 못했다. ${p}\n`)
      return 2
    }
    planText = r.stdout
  }

  const scope = computeScope(changedFiles(), planText)
  process.stdout.write(renderCommands(scope) + '\n')
  return 0
}

// 판별식이 import 할 때는 실행하지 않는다.
if (process.argv[1] !== undefined && process.argv[1].endsWith('select-test-scope.ts')) {
  process.exit(main(process.argv.slice(2)))
}
