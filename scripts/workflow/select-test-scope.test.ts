// 테스트 범위 계산기의 계약 — 좁힘의 방향 · plan 서식과의 짝 · 스킬 배선
//
// ## 무엇을 지키나
//
// 2026-09-04 진단이 찾은 것은 「좁힘 장치가 없다」가 아니라 **「셋이나 있는데 아무도 안 쓴다」**였다.
// 그래서 이 판별식이 재는 것은 계산이 맞는가만이 아니다. **연결이 살아 있는가**를 함께 본다.
//
//   1. 넓힘의 방향이 한쪽뿐이다 — 모르면 전량. 좁게 고르는 실수가 유일하게 치명적이다.
//   2. `plan-format.md` 의 `**검증**:` 템플릿과 추출기가 서로를 검사한다 (비-공허 짝).
//   3. `bts-impl` Step 4 가 실제로 이 스크립트를 부른다 — 안 부르면 1·2 가 전부 장식이다.
//   4. 푸시 훅과 상수가 같다 — 두 자리가 서로 다른 「백엔드 경로」를 쓰면 조용히 갈린다.
//
// ## ★3번이 이 파일의 핵심이다
//
// 계산기는 순수 함수라 단위 테스트가 쉽다. 쉬운 것만 재면 **정확히 진단이 지적한 상태**
// — 잘 만든 장치가 아무 데도 연결되지 않은 상태 — 로 되돌아간다. 배선 대조를 같이 둔다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

import {
  BACKEND_PREFIX,
  FE_WIDEN,
  FRONTEND_E2E,
  FRONTEND_SRC,
  computeScope,
  e2eTouched,
  frontendScope,
  planVerifyCommands,
  renderCommands,
  vitestArgs,
} from './select-test-scope.ts'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const SCRIPT = 'scripts/workflow/select-test-scope.ts'
const IMPL_SKILL = '.claude/skills/bts-impl/SKILL.md'
const PLAN_FORMAT = '.claude/skills/bts-plan/plan-format.md'
const PUSH_SCRIPT = 'scripts/workflow/push-backend-tests.ts'

function read(rel: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, rel), 'utf-8')
}

describe('넓힘의 방향 — 모르면 전량', () => {
  test('★비교 기준을 못 정하면(null) 프론트는 전량이다', () => {
    const s = frontendScope(null)
    assert.equal(s.mode, 'all', '판정 실패를 「변경 없음」으로 읽으면 검증 0회로 머지된다')
    assert.equal(s.files.length, 0)
  })

  test('★비교 기준을 못 정하면 백엔드도 전 모듈이다', () => {
    const scope = computeScope(null, null)
    assert.ok(scope.backendModules.length >= 9, `전 모듈이어야 한다 — 실제 ${scope.backendModules.length}`)
    assert.match(scope.backendReason, /기준을 못/)
  })

  test('설정·의존성이 바뀌면 프론트를 전량으로 넓힌다', () => {
    for (const f of ['pnpm-lock.yaml', 'apps/web/vite.config.ts', 'apps/web/package.json']) {
      const s = frontendScope([f, 'apps/web/src/routes/a.tsx'])
      assert.equal(s.mode, 'all', `${f} 는 모듈 그래프 밖이라 전량이어야 한다`)
    }
  })

  test('src 밖의 프론트 변경은 전량으로 넓힌다', () => {
    const s = frontendScope(['apps/web/public/logo.svg'])
    assert.equal(s.mode, 'all')
  })

  test('src 안 변경만 있으면 related 로 좁힌다', () => {
    const s = frontendScope(['apps/web/src/routes/issue.tsx', 'apps/web/src/lib/fmt.ts'])
    assert.equal(s.mode, 'related')
    assert.deepEqual(s.files, ['apps/web/src/routes/issue.tsx', 'apps/web/src/lib/fmt.ts'])
  })

  test('프론트 변경이 없으면 생략한다', () => {
    assert.equal(frontendScope(['backend/modules/notification/src/main/kotlin/A.kt']).mode, 'skip')
  })

  test('e2e 만 바뀌면 vitest 는 생략하되 e2e 는 돈다', () => {
    const files = [`${FRONTEND_E2E}login.spec.ts`]
    assert.equal(frontendScope(files).mode, 'skip')
    assert.equal(e2eTouched(files), true)
  })

  test('e2e 판정도 모르면 참이다', () => {
    assert.equal(e2eTouched(null), true)
  })

  /**
   * ★E2E 범위를 「전량이냐 아니냐」에서 **「바뀐 파일만」**으로 좁힌다 (2026-09-10).
   *
   * 종전 `computeScope` 는 `e2e: boolean` 이었고 참이면 이렇게 냈다.
   *   apps/web/node_modules/.bin/playwright test        ← 167파일 전량
   *
   * 2코어 실측으로 전량이 **약 3시간**이다(1파일 46초 · 3파일 165초 → 파일당 약 60초,
   * 고정 비용 거의 0). E2E 한 줄을 고쳐도 3시간이 뜨면 **아무도 안 돌린다** —
   * 가드가 있어도 쓰이지 않으면 없는 것과 같다. 실제로 이 명령이 훅 어디에도 배선되지
   * 않았고, E2E 를 고쳐도 푸시 전에 아무것도 확인되지 않았다.
   *
   * 바뀐 파일만 돌리면 1파일 46초다 — 그 정도면 사람이 실제로 돌린다.
   */
  test('e2eSpecs — 바뀐 E2E 파일만 돌린다 (전량이 아니다)', () => {
    const scope = computeScope(
      ['apps/web/e2e/board-manage.spec.ts', 'apps/web/src/components/board/Board.tsx'],
      null,
    )
    assert.deepEqual(scope.e2eSpecs, ['e2e/board-manage.spec.ts'])
  })

  test('e2eSpecs — E2E 변경이 없으면 빈 배열이다', () => {
    const scope = computeScope(['apps/web/src/components/board/Board.tsx'], null)
    assert.deepEqual(scope.e2eSpecs, [])
  })

  test('e2eSpecs — 변경 목록을 못 구하면(null) 전량을 뜻하는 null 이다', () => {
    // 「비었다(돌 것 없음)」와 「모른다(전부 돌아야)」를 구분한다. 둘을 같은 값으로 만들면
    // 판정 불가가 조용히 「생략」이 된다 — 이 저장소가 이름 붙인 침묵 실패다.
    assert.equal(computeScope(null, null).e2eSpecs, null)
  })

  test('★렌더 — 바뀐 파일만 인자로 붙는다', () => {
    const out = renderCommands(
      computeScope(['apps/web/e2e/board-manage.spec.ts'], null),
    )
    assert.match(out, /playwright test e2e\/board-manage\.spec\.ts/)
    assert.doesNotMatch(
      out,
      /playwright test\s*$/m,
      '인자 없는 `playwright test` 는 전량이다 — 그러면 아무도 안 돌린다.',
    )
  })

  test('백엔드만 바뀌면 프론트 명령이 렌더에 없다', () => {
    const out = renderCommands(computeScope(['backend/modules/notification/src/main/kotlin/A.kt'], null))
    assert.doesNotMatch(out, /vitest run/, '프론트 전량 명령이 새어 나왔다')
    assert.match(out, /:modules:notification:test/)
  })
})

describe('plan 의 검증 칸 ↔ 추출기 (비-공허 짝)', () => {
  test('★plan-format.md 의 실제 템플릿에서 명령을 뽑아낸다', () => {
    // 이 저장소의 지배 결함 양식은 「두 목록이 서로를 안 본다」다.
    // 픽스처를 새로 지어 재면 서식이 바뀌어도 초록이 된다 — **정본 파일을 직접 읽는다.**
    const found = planVerifyCommands(read(PLAN_FORMAT))
    assert.ok(
      found.length > 0,
      'plan-format.md 의 `**검증**:` 줄에서 명령을 하나도 못 뽑았다 — 서식이 바뀌었거나 추출기가 깨졌다',
    )
    assert.ok(
      found.some((c) => c.includes('gradlew')),
      `Gradle 검증 예시를 못 찾았다 — 실제로 뽑힌 것: ${JSON.stringify(found)}`,
    )
  })

  test('★템플릿의 Gradle 경로가 실제 모듈 경로여야 한다', () => {
    // `:backend:<bc>:test` 는 존재하지 않는 경로다. settings.gradle.kts 는 `:modules:<bc>` 로 include 한다.
    // 실행하는 기계가 없어서 200개 가까운 plan 문서에 틀린 채로 복사돼 왔다 (2026-09-04 진단).
    const settings = read('backend/settings.gradle.kts')
    assert.match(settings, /include\("?:modules:/, '전제가 깨졌다 — settings.gradle.kts 가 :modules: 를 안 쓴다')
    for (const cmd of planVerifyCommands(read(PLAN_FORMAT))) {
      assert.doesNotMatch(cmd, /:backend:/, `실행 불가능한 Gradle 경로가 템플릿에 있다. ${cmd}`)
    }
  })

  test('여러 줄·여러 백틱에서 전부 뽑는다', () => {
    const got = planVerifyCommands(
      ['**검증**: `a --tests X`', '- **검증**: `b` 그리고 `c`', '검증: `안뽑힘`'].join('\n'),
    )
    assert.deepEqual(got, ['a --tests X', 'b', 'c'])
  })

  test('검증 칸이 있으면 렌더 결과에 그대로 실린다', () => {
    const out = renderCommands(computeScope([], '**검증**: `./gradlew :modules:x:test --tests YTest`'))
    assert.match(out, /--tests YTest/, 'plan 이 지정한 검증이 명령 블록에서 사라졌다')
  })
})

describe('★vitest 호출 형태 — 실행으로 확인한다', () => {
  // 문자열 계약만으로는 못 잡는 축이다. 실제로 2026-09-04 에 `--related` 플래그로 썼다가
  // `CACError: Unknown option` 을 맞았다 — vitest 4 에는 플래그 형태가 없고 서브커맨드뿐이다.
  // 다행히 빨강이었지만, 다음 메이저에서 서브커맨드가 사라지면 그때는 조용할 수 있다.
  const bin = path.join(REPO_ROOT, 'apps/web/node_modules/.bin/vitest')

  test('vitest related 서브커맨드가 실재한다', (t) => {
    if (!fs.existsSync(bin)) {
      // worktree 심볼릭이 없는 환경에서 위양성을 내지 않는다. 부재 사실은 남긴다.
      t.diagnostic('vitest 바이너리 없음 — 이 검사를 건너뛴다')
      return
    }
    const r = spawnSync(bin, ['related', '--help'], { encoding: 'utf-8', timeout: 60_000 })
    assert.equal(r.status, 0, `vitest related 서브커맨드가 없다.\n${r.stderr ?? ''}`)
    assert.match(r.stdout, /vitest related/, 'related 사용법이 안 나온다')
  })

  test('렌더가 서브커맨드 형태를 쓴다', () => {
    const out = renderCommands(computeScope(['apps/web/src/lib/fmt.ts'], null))
    assert.match(out, /vitest related --run/, 'related 서브커맨드 형태가 아니다')
    assert.doesNotMatch(out, /--related/, 'vitest 4 에 없는 --related 플래그를 쓴다')
  })

  test('★렌더 경로가 apps/web 기준 상대경로다', () => {
    // 저장소 루트 기준 경로를 주면 매칭 0건 → **테스트 0개 돌고 초록**. 조용한 통과다.
    const s = frontendScope(['apps/web/src/lib/fmt.ts'])
    assert.deepEqual(vitestArgs(s), ['src/lib/fmt.ts'])
    const out = renderCommands(computeScope(['apps/web/src/lib/fmt.ts'], null))
    assert.match(out, /cd apps\/web/, 'cwd 를 apps/web 으로 옮기지 않는다 — 설정을 못 찾는다')
    assert.doesNotMatch(out, /related --run apps\/web\//, '루트 기준 경로가 인자로 새어 나갔다')
  })
})

describe('배선 — 만들어 두고 안 쓰는 상태로 돌아가지 않는다', () => {
  test('★bts-impl Step 4 가 이 스크립트를 부른다', () => {
    const skill = read(IMPL_SKILL)
    assert.ok(
      skill.includes(SCRIPT),
      `${IMPL_SKILL} 이 ${SCRIPT} 를 부르지 않는다 — 범위 계산기가 아무 데도 연결돼 있지 않다`,
    )
  })

  test('★bts-impl Step 4 에 무조건 전량 명령이 남아 있지 않다', () => {
    const skill = read(IMPL_SKILL)
    // `./gradlew test` 와 `pnpm test`(단독)는 전량 명령이다. 범위 계산기를 도입한 뒤에도
    // 이것들이 남아 있으면 사람이 그쪽을 복사해 쓰고 계산기는 장식이 된다.
    assert.doesNotMatch(
      skill,
      /^\s*\.\/gradlew test\b/m,
      'Step 4 에 백엔드 전량 명령(./gradlew test)이 남아 있다',
    )
    assert.doesNotMatch(
      skill,
      /^\s*pnpm test\s*$/m,
      'Step 4 에 프론트 전량 명령(pnpm test)이 남아 있다',
    )
  })

  test('★판별식 전량 실행은 그대로 남는다 — 좁히면 안 되는 유일한 축', () => {
    const out = renderCommands(computeScope(['docs/x.md'], null))
    assert.match(out, /scripts\/\*\*\/\*\.test\.ts/, '판별식 전량 실행이 사라졌다')
  })

  test('푸시 훅과 백엔드 경로 접두가 같다', () => {
    const push = read(PUSH_SCRIPT)
    const m = /BACKEND_PREFIX = '([^']+)'/.exec(push)
    assert.ok(m !== null, 'push-backend-tests.ts 에서 BACKEND_PREFIX 를 못 읽었다')
    assert.equal(BACKEND_PREFIX, m![1], '두 자리가 서로 다른 「백엔드 경로」를 쓰면 조용히 갈린다')
  })

  test('FE_WIDEN 의 모든 항목이 실재하는 경로를 가리킨다', () => {
    // 오타나 이름 변경으로 넓힘이 조용히 사라지는 것을 막는다.
    for (const w of FE_WIDEN) {
      const p = path.join(REPO_ROOT, w)
      assert.ok(fs.existsSync(p), `FE_WIDEN 항목이 실재하지 않는다 — ${w}`)
    }
  })

  test('FRONTEND_SRC 아래에 실제 테스트가 있다', () => {
    assert.ok(fs.existsSync(path.join(REPO_ROOT, FRONTEND_SRC)), `${FRONTEND_SRC} 가 없다`)
  })

  test('★vitest 의 전역 셋업 파일이 FE_WIDEN 에 덮인다', () => {
    // 두 목록이 서로를 안 보면 썩는다. 설정을 직접 읽어 대조한다 —
    // setupFiles 경로가 바뀌었는데 FE_WIDEN 이 안 따라오면 전역 셋업 변경이
    // `--related` 로 좁혀져 **전 테스트에 영향을 주는 변경이 거의 안 돌게** 된다.
    const cfg = read('apps/web/vitest.config.ts')
    const m = /setupFiles\s*:\s*\[([^\]]*)\]/.exec(cfg)
    assert.ok(m !== null, 'vitest.config.ts 에서 setupFiles 를 못 읽었다')
    const paths = [...m![1].matchAll(/['"]([^'"]+)['"]/g)].map((x) => x[1])
    assert.ok(paths.length > 0, 'setupFiles 가 비었다 — 전제가 바뀌었다')
    for (const p of paths) {
      const rel = path.posix.join('apps/web', p.replace(/^\.\//, ''))
      assert.ok(
        FE_WIDEN.some((w) => (w.endsWith('/') ? rel.startsWith(w) : rel === w)),
        `전역 셋업 ${rel} 이 FE_WIDEN 에 없다 — 이 파일이 바뀌어도 전량으로 안 넓혀진다`,
      )
    }
  })
})
