// GHA route 잡이 읽는 `--json` 판정과 셸 렌더 `renderCommands` 가 같은 계산에서 나오는지 대조
//
// ## 왜 있나
//
// 젠킨스는 계산기를 `select-test-scope.ts > .ci-scope.sh` 로 부르고 `sh -e` 로 통째 실행한다.
// GitHub Actions 는 그럴 수 없다 — 매트릭스로 9 BC 를 펼치려면 **구조화된 판정**이 필요하고,
// 직렬 셸 한 덩어리는 잡으로 쪼개지지 않는다.
//
// 여기서 갈림길이 하나 생긴다. YAML 에 판정을 다시 적는 길과, 계산기가 같은 판정을 다른
// 형식으로도 내는 길이다. 전자는 이 저장소가 이름 붙여 둔 지배 결함 양식이다 —
// `Jenkinsfile:255` 가 새 CI 파일이 생기는 경우를 정확히 예고한다.
//
//     판정 목록을 여기 적지 않는다. `WIDEN_PREFIXES` 가 정본이다 —
//     Groovy 로 다시 적으면 두 목록이 되고 **새 CI 파일이 생길 때 한쪽만 고쳐진다**.
//
// 그래서 후자를 택했다. `computeScope()` 가 유일한 판정이고 렌더러만 둘이다
// (`renderCommands` = 셸 · `routeJson` = GHA). 이 판별식은 **그 둘이 갈리지 않는지**를 본다.
//
// ## 무엇을 강제하나
//
// ① `routeJson` 이 유효한 JSON 이고 `TestScope` 의 필드를 갖는다.
// ② **차집합 0.** JSON 의 `modules` 와 셸 렌더의 `:modules:<bc>:test` 태스크가
//    양방향으로 일치한다. 한쪽에만 있는 모듈이 있으면 두 경로가 갈린 것이다.
// ③ 프론트 판정(`skip`/`all`/`related`)도 양쪽이 같다.
// ④ **비-공허 짝.** 판정을 일부러 흔든 입력으로 ②가 실제로 red 를 내는지 확인한다.
//    이 짝이 없으면 「차집합을 계산했는데 늘 빈 집합」인 공허한 가드가 된다.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { computeScope, renderCommands, routeJson, sectionCommands, SECTIONS } from './select-test-scope.ts'
import type { TestScope } from './select-test-scope.ts'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')

/** 셸 렌더에서 `:modules:<bc>:test` 태스크의 BC 이름을 뽑는다. */
function modulesInShell(shell: string): string[] {
  return [...shell.matchAll(/:modules:([a-z-]+):test\b/g)].map((m) => m[1]!)
}

/** 셸 렌더의 프론트 판정을 되읽는다. */
function frontendModeInShell(shell: string): 'skip' | 'all' | 'related' {
  if (/# 프론트 — 생략/.test(shell)) return 'skip'
  if (/vitest related --run/.test(shell)) return 'related'
  if (/vitest run\)/.test(shell)) return 'all'
  throw new Error(`프론트 판정을 셸에서 못 읽었다:\n${shell}`)
}

/** 판정을 흔드는 입력 묶음. 서로 다른 분기를 타야 의미가 있다. */
const CASES: ReadonlyArray<{ name: string; files: string[] | null }> = [
  { name: '백엔드 한 모듈', files: ['backend/modules/issue-tracking/src/main/kotlin/A.kt'] },
  { name: '프론트만', files: ['apps/web/src/pages/Board.tsx'] },
  { name: '문서만', files: ['docs/rules/traps.md'] },
  { name: '섞임', files: ['backend/modules/notification/src/main/kotlin/B.kt', 'apps/web/src/x.ts'] },
  { name: '비교 기준 없음 — 전량', files: null },
]

describe('route JSON ⟺ 셸 렌더 — 같은 계산에서 나온다', () => {
  test('★① routeJson 형태가 TestScope 필드를 갖는다', () => {
    for (const c of CASES) {
      const scope = computeScope(c.files, null)
      const parsed = JSON.parse(routeJson(scope))
      assert.ok(Array.isArray(parsed.modules), `${c.name}: modules 가 배열이 아니다`)
      assert.ok(
        ['skip', 'all', 'related'].includes(parsed.frontend),
        `${c.name}: frontend 판정이 셋 중 하나가 아니다 — ${parsed.frontend}`,
      )
      assert.equal(typeof parsed.e2e, 'boolean', `${c.name}: e2e 가 boolean 이 아니다`)
    }
  })

  test('★★② 백엔드 모듈 차집합 0 — 한쪽에만 있는 모듈이 없다', () => {
    for (const c of CASES) {
      const scope = computeScope(c.files, null)
      const fromJson = [...(JSON.parse(routeJson(scope)).modules as string[])].sort()
      const fromShell = [...new Set(modulesInShell(renderCommands(scope)))].sort()
      assert.deepEqual(
        fromShell,
        fromJson,
        `${c.name}: 두 렌더가 갈렸다.\n` +
          `  JSON  ${fromJson.join(', ') || '(없음)'}\n` +
          `  셸    ${fromShell.join(', ') || '(없음)'}\n` +
          '★한쪽에만 있는 모듈은 그 경로에서만 검증된다 — 두 목록이다.',
      )
    }
  })

  test('★③ 프론트 판정이 양쪽에서 같다', () => {
    for (const c of CASES) {
      const scope = computeScope(c.files, null)
      assert.equal(
        frontendModeInShell(renderCommands(scope)),
        JSON.parse(routeJson(scope)).frontend,
        `${c.name}: 프론트 판정이 갈렸다`,
      )
    }
  })

  test('★★④ 비-공허 짝 — 판정을 흔들면 ②가 실제로 red 를 낸다', () => {
    // 계산 결과를 손으로 오염시킨다. ②의 단언이 살아 있다면 이 입력에서 반드시 실패한다.
    const scope = computeScope(['backend/modules/issue-tracking/src/main/kotlin/A.kt'], null)
    const tampered: TestScope = {
      ...scope,
      backendModules: [...scope.backendModules, 'notification'],
    }
    const fromJson = [...(JSON.parse(routeJson(tampered)).modules as string[])].sort()
    const fromShell = [...new Set(modulesInShell(renderCommands(scope)))].sort()
    assert.notDeepEqual(
      fromShell,
      fromJson,
      '★비-공허 확인 실패 — 판정을 오염시켰는데도 차집합이 0이다. ②는 아무것도 지키지 않는다.',
    )
  })

  // ⑥ 섹션 렌더가 실제로 내용을 낸다. `renderCommands` 는 이 섹션들의 **조합**이므로
  //   「조합 == 전체」는 정의상 참이라 단언할 가치가 없다 — 그 자리에 두면 공허한 가드다.
  //   지킬 값어치가 있는 것은 섹션이 빈 껍데기가 아니라는 것뿐이다.
  test('★⑥ 각 섹션이 실제 내용을 낸다 — 빈 껍데기가 아니다', () => {
    const scope = computeScope(['backend/modules/issue-tracking/src/main/kotlin/A.kt', 'apps/web/src/x.ts'], null)
    for (const s of SECTIONS) {
      if (s === 'plan') continue // plan 은 지정이 없으면 비는 것이 정상이다
      assert.ok(
        sectionCommands(scope, s).trim().length > 0,
        `섹션 '${s}' 이 빈 문자열이다 — GHA 잡이 아무 명령도 못 받는다`,
      )
    }
    assert.equal(sectionCommands(scope, 'plan'), '', 'plan 지정이 없으면 빈 문자열이어야 한다')
  })

  // ⑦ ★진짜 지켜야 할 것 — YAML 이 판정을 **다시 적지 않는가**.
  //   `Jenkinsfile:255` 가 예고한 자리다. 명령을 YAML 에 리터럴로 박으면 계산기와 갈리고,
  //   갈린 것은 「새 CI 파일이 생길 때 한쪽만 고쳐진다」로 나타난다.
  test('★★⑦ verify.yml 이 테스트 명령을 리터럴로 적지 않는다', () => {
    const p = path.resolve(REPO_ROOT, '.github/workflows/verify.yml')
    // ★파일이 없으면 통과가 아니라 실패다. 「검사 대상이 없어서 초록」은 침묵 실패다.
    assert.ok(fs.existsSync(p), `verify.yml 이 없다: ${p}`)
    const src = fs.readFileSync(p, 'utf-8')

    // 주석(#)을 뺀 실행 줄만 본다 — 설명문에 명령이 인용되는 것은 막을 이유가 없다.
    const exec = src
      .split('\n')
      .filter((l) => !l.trim().startsWith('#'))
      .join('\n')

    const BANNED: ReadonlyArray<[RegExp, string]> = [
      [/vitest\s+(run|related)/, 'vitest 실행을 YAML 이 직접 적었다'],
      [/playwright\s+test/, 'playwright 실행을 YAML 이 직접 적었다'],
    ]
    for (const [re, why] of BANNED) {
      assert.ok(
        !re.test(exec),
        `${why} — 계산기(sectionCommands)를 통해 받아야 한다.\n` +
          '★YAML 에 적으면 계산기와 두 목록이 되고, 형식이 바뀌어도 한쪽만 고쳐진다.',
      )
    }

    // 비-공허 짝. 위 정규식이 실제로 무는지 가짜 입력으로 확인한다.
    assert.ok(
      BANNED[0]![0].test('run: pnpm exec vitest run'),
      '★비-공허 확인 실패 — 금지 정규식이 명령을 물지 못한다. ⑦은 아무것도 지키지 않는다.',
    )
  })

  test('★⑤ 양성 대조군 — 케이스가 실제로 다른 분기를 탄다', () => {
    const shapes = new Set(
      CASES.map((c) => {
        const s = computeScope(c.files, null)
        return `${s.backendModules.length}:${s.frontend.mode}`
      }),
    )
    assert.ok(
      shapes.size >= 3,
      `케이스가 ${shapes.size}가지 모양밖에 안 낸다 — 전부 같은 분기라 대조가 공허하다`,
    )
  })
})
