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

import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

import { allModules, selectModules } from './select-backend-modules.ts'
// @ts-ignore — .mjs 는 타입 선언이 없다. 런타임 export 는 실재한다.

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

/**
 * 브랜치가 건드린 파일. 기준을 못 정하면 `null`.
 *
 * ★본체는 `diff-base.ts` 하나다(2026-09-10). 종전에는 이 계산이 6벌이었고
 *   **여섯이 동시에 main 위에서 기준을 HEAD 로 잡았다** — 차집합이 항상 공집합이라
 *   빌드 #31 이 3분 만에 초록이면서 백엔드·프론트 테스트를 0건 돌렸다.
 *   계약. scripts/workflow/diff-base.test.ts
 */
// ★재수출(`export … from`)이 아니라 **임포트 + 재수출**이다. 전자는 이 파일 안에서
//   이름을 안 잡아서 본문의 `changedFiles()` 가 런타임에 ReferenceError 로 죽는다 —
//   판별식이 소스 글자만 보므로 690건 초록인 채로 통과했다(2026-09-10 실측).
import { changedFiles, FALLBACK_BASE } from './diff-base.ts'
export { changedFiles, FALLBACK_BASE }

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

  /*
   * ★★넓힘 판정이 **프론트 경로 필터보다 먼저**다 (2026-09-11 수정).
   *
   * 종전에는 `files.filter(f => f.startsWith('apps/web/'))` 가 비면 즉시 `skip` 이었다.
   * 그런데 `FE_WIDEN` 12항목 중 넷(`package.json` `pnpm-lock.yaml` `pnpm-workspace.yaml`
   * `.nvmrc`)은 **저장소 루트**라 절대 `apps/web/` 로 시작하지 않는다. 그래서 그 넷은
   * `apps/web/**` 파일이 **함께** 바뀐 경우에만 발화했다 — 단독이면 도달 불가였다.
   *
   * 실측(2026-09-11). `pnpm-lock.yaml` 단독 → `mode=skip · 프론트 변경 없음`.
   * 의존성 업그레이드 PR(package.json + lock 만)이 **프론트 테스트 0건**으로 초록이었다.
   * react·zod·msw 메이저 업그레이드가 그 문으로 들어온다.
   *
   * ★판별식도 이 구멍을 못 봤다. `select-test-scope.test.ts` 가 넓힘 항목을 넣을 때
   *   `apps/web/src/routes/a.tsx` 를 **항상 끼워** 불렀다 — 단독 케이스를 한 번도 안 쟀다.
   *   짝 파일이 가드를 대신 발화시키고 있었다(가짜 초록).
   */
  const widen = files.filter((f) => FE_WIDEN.some((w) => (w.endsWith('/') ? f.startsWith(w) : f === w)))
  if (widen.length > 0) {
    return { mode: 'all', files: [], reason: `모듈 그래프 밖 변경 — ${widen.join(' · ')}` }
  }

  const fe = files.filter((f) => f.startsWith(FRONTEND_PREFIX))
  if (fe.length === 0) {
    return { mode: 'skip', files: [], reason: '프론트 변경 없음' }
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
 * 바뀐 Playwright 시나리오 목록(`apps/web` 기준 상대경로). 전량을 뜻할 때는 `null`.
 *
 * ## 왜 「돌린다/만다」가 아니라 목록인가
 *
 * 종전 [e2eTouched] 는 참이면 `playwright test` 를 **인자 없이** 냈다 — 167파일 전량이다.
 * 2코어 실측으로 그것이 **약 3시간**이다(1파일 46초 · 3파일 165초 → 파일당 약 60초,
 * 고정 비용 거의 0).
 *
 * E2E 한 줄을 고쳐도 3시간이 뜨면 **아무도 안 돌린다.** 가드가 있어도 쓰이지 않으면 없는
 * 것과 같다 — 실제로 그 명령은 훅 어디에도 배선되지 않았고, E2E 를 고쳐도 푸시 전에
 * 아무것도 확인되지 않았다. 바뀐 파일만이면 1파일 46초라 사람이 실제로 돌린다.
 *
 * ★`null`(전량)과 `[]`(돌 것 없음)를 **구분한다.** 둘을 같은 값으로 만들면 「변경 목록을
 *   못 구했다」가 조용히 「생략」이 된다 — 이 저장소가 이름 붙인 침묵 실패다.
 *
 * @param files 변경 파일 목록. `null` 이면 목록을 못 구한 것이라 전량을 뜻한다.
 * @returns `apps/web` 기준 상대경로 목록. 전량이면 `null`.
 */
export function e2eSpecs(files: string[] | null): string[] | null {
  if (files === null) return null
  return files
    .filter((f) => f.startsWith(FRONTEND_E2E) && f.endsWith('.spec.ts'))
    /*
     * ★★`--project=chromium` 이 못 도는 것을 넘기지 않는다 (2026-09-11).
     *
     * 두 경우가 **거짓 빨강**을 만든다. 둘 다 playwright 가 `No tests found` 로 exit 1 이다.
     *
     *   ① `e2e/visual/` — `playwright.config.ts` 의 chromium 프로젝트가
     *      `testIgnore: '**‍/e2e/visual/**'` 다. 그 스펙은 `--project=visual` 전용이다.
     *   ② **삭제된 스펙** — `diff-base.ts` 가 `--no-renames` 라 개명하면 옛 경로가
     *      반드시 삭제로 목록에 들어온다. 그 파일은 디스크에 없다.
     *
     * 실측. `board-kanban.spec.ts` → `board-board.spec.ts` 로 개명하고 푸시하면
     * 훅이 exit 1 로 막는다. 에러 문구는 「정규식 인자를 확인하라」라 원인이 안 보인다.
     *
     * ★여기서 사람이 배우는 처방은 `git push --no-verify` 이고, 그 순간 **판별식 전량**
     *   (유일한 기계 강제 지점)까지 함께 꺼진다. 훅 주석이 스스로 경고한 그 상태다.
     *   거짓 빨강은 가드를 무력화하는 가장 흔한 경로다.
     */
    .filter((f) => !f.startsWith(`${FRONTEND_E2E}visual/`))
    .filter((f) => fs.existsSync(path.resolve(REPO_ROOT, f)))
    .map((f) => f.slice(FRONTEND_PREFIX.length))
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
  /** 바뀐 시나리오 목록(`apps/web` 상대경로). 전량이면 `null`, 돌 것이 없으면 `[]`. */
  e2eSpecs: string[] | null
  planVerify: string[]
}

/**
 * 범위 계산 옵션.
 *
 * `forceFull` — 백엔드·프론트를 **전량**으로 넓힌다(`전량` stage 가 쓴다).
 *   ★E2E 는 넓히지 않는다. 정책상 전량 E2E(약 3시간)는 **배포 이후**의 몫이고,
 *     CI 의 전량 빌드는 「이 커밋이 괜찮은가」를 보는 자리다. E2E 는 여기서도
 *     **변경 도메인**만 돈다 — 그것이 2층의 약속이다.
 */
export interface ScopeOptions {
  forceFull?: boolean
}

export function computeScope(
  files: string[] | null,
  planText: string | null,
  opts: ScopeOptions = {},
): TestScope {
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

  if (opts.forceFull === true) {
    backendModules = allModules()
    backendReason = '전량 빌드 — 영향 범위를 계산하지 않고 전 모듈을 돈다'
  }

  return {
    backendModules,
    backendReason,
    // ★전량이어도 E2E 는 변경 도메인만이다. 위 ScopeOptions 주석 참조.
    frontend:
      opts.forceFull === true
        ? { mode: 'all', files: [], reason: '전량 빌드' }
        : frontendScope(files),
    e2e: e2eTouched(files),
    e2eSpecs: e2eSpecs(files),
    planVerify: planText === null ? [] : planVerifyCommands(planText),
  }
}

/** 사람이 읽고 그대로 실행할 수 있는 명령 블록. */
/**
 * 환경에 맞는 Playwright 실행 명령. 로컬은 설치된 바이너리, 젠킨스는 공식 컨테이너.
 *
 * ## 왜 갈라지나
 *
 * 젠킨스 컨테이너는 uid 1000 으로 돌아 `playwright install --with-deps` 가 apt 권한 없이
 * **종료 코드 0 으로 조용히** 넘어간다. 그래서 크롬이 `libglib-2.0.so.0` 부재로 뜨지 않는다
 * (2026-09-10 실측 — `browserType.launch: Target page, context or browser has been closed`).
 * 공식 이미지는 그 문제를 통째로 없앤다.
 *
 * 맥에서는 그냥 된다 — 개발 워크플로우를 바꾸지 않으려고 로컬 경로를 기본값으로 둔다.
 *
 * ★이미지 태그를 **여기 적지 않는다.** `Jenkinsfile.e2e` 의 `PW_IMAGE` 가 정본이고
 *   `playwright-image-pin.test.ts` 가 lockfile 버전과 대조한다. 여기 또 적으면 세 번째
 *   목록이 되고, 그 셋은 서로를 검사하지 않는다.
 *
 * @param specs 돌릴 시나리오(`apps/web` 상대경로). 비면 전량이다.
 */
/** 기준 이미지가 실재하나. IO 는 여기 한 줄뿐이라 아래 판정은 순수하다. */
export function hasVisualBaseline(
  dir: string = path.join(REPO_ROOT, 'apps/web/e2e/visual/__screenshots__'),
): boolean {
  return fs.existsSync(dir) && fs.readdirSync(dir).some((f) => f.endsWith('.png'))
}

/** 시각 회귀를 돌 것인가, 못 돈다면 왜인가. */
export type VisualDecision = 'run' | 'no-baseline' | 'no-frontend'

/**
 * 시각 회귀 판정.
 *
 * ★IO 에서 분리한다. 기준 이미지가 0장인 동안에는 「돈다」 갈래를 **디스크로는 잴 수
 *   없어서**, 판별식이 그 분기를 지운 뮤테이션도 통과했다(2026-09-11 실측).
 *   판정을 순수 함수로 두면 도입 전에도 세 갈래를 전부 잰다.
 */
export function visualDecision(
  hasBaseline: boolean,
  frontendMode: FrontendScope['mode'],
): VisualDecision {
  if (!hasBaseline) return 'no-baseline'
  if (frontendMode === 'skip') return 'no-frontend'
  return 'run'
}

/** 판정에 따른 실행 블록 줄. */
export function visualLines(
  hasBaseline: boolean,
  frontendMode: FrontendScope['mode'],
): string[] {
  switch (visualDecision(hasBaseline, frontendMode)) {
    case 'no-baseline':
      return [
        '# 시각 회귀 — 생략 (기준 이미지 0장 · P3 미도입)',
        '#   기준 생성. 젠킨스 bts-ci 를 UPDATE_VISUAL_BASELINE=true 로 돌리고',
        '#   아티팩트의 PNG 를 받아 커밋한다. 커밋되는 순간 이 줄이 명령으로 바뀐다.',
      ]
    case 'no-frontend':
      return ['# 시각 회귀 — 생략 (프론트 변경 없음)']
    default:
      return ['# 시각 회귀 — 기준 이미지와 대조한다.', visualCommand()]
  }
}

/**
 * 시각 회귀 실행 명령. `e2eCommand` 와 같은 갈림(로컬 바이너리 ↔ 젠킨스 컨테이너)을 쓴다.
 *
 * ★`--project=visual` 이다. `chromium` 은 `e2e/visual/**` 를 `testIgnore` 하므로
 *   그 프로젝트로는 이 스펙이 **절대** 안 돈다.
 */
function visualCommand(): string {
  const image = process.env['PW_IMAGE']
  if (image === undefined || image === '') {
    return '(cd apps/web && node_modules/.bin/playwright test --project=visual)'
  }
  const hostWs = process.env['HOST_WS'] ?? '$PWD'
  return (
    `docker run --rm --network host -v "${hostWs}:/w" -w /w/apps/web -e CI=1 ` +
    `"${image}" npx playwright test --project=visual`
  )
}

function e2eCommand(specs: string[]): string {
  const args = specs.length > 0 ? ` ${specs.join(' ')}` : ''
  const image = process.env['PW_IMAGE']
  if (!image) return `(cd apps/web && node_modules/.bin/playwright test${args})`
  // DooD — `-v` 좌변은 **호스트 경로**다. 컨테이너 안 경로를 주면 빈 디렉터리가 마운트되고,
  // 그러면 "no tests found" 가 초록으로 보인다.
  const host = process.env['HOST_WS'] ?? '$PWD'
  return (
    `docker run --rm --network host -v "${host}:/w" -w /w/apps/web -e CI=1 ` +
    `"${image}" npx playwright test${args} --project=chromium`
  )
}

/**
 * 렌더 섹션 이름. GitHub Actions 는 잡을 나누므로 블록 **하나만** 뽑아 쓸 수 있어야 한다.
 *
 * ★YAML 에서 `sed` 로 뽑는 길도 있지만 그것은 형식이 바뀌는 순간 조용히 빈 명령이 된다 —
 *   「no tests found」가 초록이 되는 자리다. 그래서 계산기가 직접 섹션을 낸다.
 *   `renderCommands` 는 이 섹션들을 **조합**해서 만들어진다. 두 경로가 갈릴 수 없다.
 */
export const SECTIONS = ['backend', 'frontend', 'e2e', 'visual', 'plan', 'discriminants'] as const
export type Section = (typeof SECTIONS)[number]

/** 섹션 하나의 명령 블록. 돌 것이 없으면 빈 문자열이 아니라 「생략」 주석을 낸다. */
export function sectionCommands(scope: TestScope, section: Section): string {
  const lines: string[] = []
  switch (section) {
    case 'backend':
      if (scope.backendModules.length === 0) {
        lines.push('# 백엔드 — 생략 (변경 없음)')
      } else {
        const tasks = scope.backendModules.map((m) => `:modules:${m}:test`).join(' ')
        const lint = scope.backendModules
          .map((m) => `:modules:${m}:ktlintCheck :modules:${m}:detekt`)
          .join(' ')
        lines.push(`# 백엔드 ${scope.backendModules.length}/${allModules().length} — ${scope.backendReason}`)
        lines.push(`(cd backend && ./gradlew ${tasks} --console=plain)`)
        lines.push(`(cd backend && ./gradlew ${lint} --rerun-tasks --console=plain)`)
      }
      break
    case 'frontend':
      if (scope.frontend.mode === 'skip') {
        lines.push(`# 프론트 — 생략 (${scope.frontend.reason})`)
      } else if (scope.frontend.mode === 'all') {
        lines.push(`# 프론트 전량 — ${scope.frontend.reason}`)
        lines.push('(cd apps/web && node_modules/.bin/vitest run)')
      } else {
        lines.push(`# 프론트 — ${scope.frontend.reason}`)
        lines.push(
          `(cd apps/web && node_modules/.bin/vitest related --run ${vitestArgs(scope.frontend).join(' ')})`,
        )
      }
      break
    case 'e2e':
      // ★바뀐 파일만 인자로 붙인다. 인자 없는 `playwright test` 는 전량(약 3시간)이라
      //   사람이 안 돌린다 — 안 돌리는 가드는 없는 가드다.
      if (scope.e2eSpecs === null) {
        lines.push('# E2E — 변경 목록을 못 구했다. 전량을 돈다(약 3시간).')
        lines.push(e2eCommand([]))
      } else if (scope.e2eSpecs.length > 0) {
        lines.push(`# E2E — 바뀐 시나리오 ${scope.e2eSpecs.length}개만 돈다.`)
        lines.push(e2eCommand(scope.e2eSpecs))
      } else {
        lines.push('# E2E — 생략 (시나리오 변경 없음)')
      }
      break
    case 'visual':
      lines.push(...visualLines(hasVisualBaseline(), scope.frontend.mode))
      break
    case 'plan':
      if (scope.planVerify.length > 0) {
        lines.push(`# plan 이 지정한 검증 ${scope.planVerify.length}건 — 위 범위와 별개로 반드시 돈다`)
        for (const c of scope.planVerify) lines.push(c)
      }
      break
    case 'discriminants':
      lines.push('# 판별식 — ★조건 없이 전량. 경로별 선별은 두 목록이 서로를 안 보게 만든다.')
      lines.push("node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'")
      break
  }
  return lines.join('\n')
}

export function renderCommands(scope: TestScope): string {
  // ★섹션 조합으로 만든다. 전체 렌더와 섹션 렌더가 **정의상** 같아진다 —
  //   차집합 판별식으로 지키는 것보다 애초에 갈릴 수 없게 만드는 쪽이 낫다.
  //   빈 섹션(`plan` 이 0건인 경우)은 빠진다 — 종전 출력과 바이트 단위로 같다.
  return SECTIONS.map((s) => sectionCommands(scope, s))
    .filter((s) => s !== '')
    .join('\n\n')
}

/**
 * GitHub Actions `route` 잡이 읽는 판정. `renderCommands` 와 **같은 `TestScope`** 를 받는다.
 *
 * ★렌더러가 둘인 것이지 판정이 둘인 것이 아니다. 젠킨스는 직렬 셸 한 덩어리를 `sh -e` 로
 *   돌리면 되지만 Actions 는 9 BC 를 매트릭스로 펼쳐야 하고, 셸 블록은 잡으로 쪼개지지 않는다.
 *   그렇다고 YAML 에 판정을 다시 적으면 `Jenkinsfile:255` 가 예고한 「두 목록」이 된다 —
 *   **새 CI 파일이 생길 때 한쪽만 고쳐진다**. 그래서 같은 계산을 형식만 바꿔 낸다.
 *
 *   두 렌더가 갈리지 않는 것은 `ci-route-json-parity.test.ts` 가 차집합으로 대조한다.
 */
export function routeJson(scope: TestScope): string {
  return JSON.stringify({
    modules: scope.backendModules,
    frontend: scope.frontend.mode,
    e2e: scope.e2e,
    e2eSpecs: scope.e2eSpecs,
    planVerify: scope.planVerify,
  })
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

  /*
   * ★`BTS_FORCE_FULL=1` 이면 전량이다 — `Jenkinsfile` 의 `전량` stage 가 쓴다.
   *
   *   종전에는 그 stage 가 명령 목록을 **손으로 다시 적었다.** 그러면 두 목록이 되고,
   *   실제로 갈려 있었다 — 계산기 블록에는 E2E 가 있는데 전량 stage 에는 없었다.
   *   즉 **넓힐수록 검증이 줄었다.** 야간 크론(조합 위험을 받는 자리)도 그 경로다.
   *
   *   이제 두 stage 가 같은 계산기를 부르고, 다른 것은 이 플래그 하나다.
   */
  const forceFull = (process.env['BTS_FORCE_FULL'] ?? '') !== ''
  const scope = computeScope(changedFiles(), planText, { forceFull })
  // `--json` 은 GitHub Actions `route` 잡 전용이다. 기본은 셸 렌더 — 젠킨스와 로컬 훅이 쓴다.
  if (argv.includes('--json')) {
    process.stdout.write(routeJson(scope) + '\n')
    return 0
  }

  // `--section <이름>` 은 GHA 가 잡 하나에 필요한 블록만 받을 때 쓴다.
  // ★YAML 에서 `sed` 로 뽑는 길을 막기 위한 것이다 — 렌더 형식이 바뀌면 그 추출은
  //   조용히 빈 명령이 되고, 빈 명령은 「돌 것이 없었다」와 구분되지 않는다.
  const secIdx = argv.indexOf('--section')
  if (secIdx !== -1) {
    const name = argv[secIdx + 1]
    if (name === undefined || !(SECTIONS as readonly string[]).includes(name)) {
      process.stderr.write(`--section 뒤에 ${SECTIONS.join('|')} 중 하나가 필요하다.\n`)
      return 2
    }
    process.stdout.write(sectionCommands(scope, name as Section) + '\n')
    return 0
  }

  process.stdout.write(renderCommands(scope) + '\n')
  return 0
}

// 판별식이 import 할 때는 실행하지 않는다.
if (process.argv[1] !== undefined && process.argv[1].endsWith('select-test-scope.ts')) {
  process.exit(main(process.argv.slice(2)))
}
