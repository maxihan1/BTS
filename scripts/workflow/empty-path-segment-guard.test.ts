// URL 경로에 빈 세그먼트(`//`)를 만드는 호출을 막는 판별식
//
// ─────────────────────────────────────────────────────────────────────────────
// 이 파일이 있는 이유 — 프로덕션 실측 (2026-09-07)
// ─────────────────────────────────────────────────────────────────────────────
// `issues.$key.tsx` 가 이슈 로드 **전에** `useVersions(issue?.projectKey ?? '')` 를 불러
// `GET /api/v1/projects//versions` 를 실제로 쐈다. 콘솔에 401 이 반복해 찍혔고, 사용자는
// 「로그인했는데 왜 401 인가」로 읽었다.
//
// ★인증 문제가 아니다. 빈 세그먼트가 들어가면 Spring Security 의 경로 매처가 그 URL 을
//   원래 규칙에 매칭시키지 못해 `SecurityConfig.kt` 의 포괄 규칙 `/api/**` → authenticated
//   로 떨어진다. 프로덕션 실측으로 증명했다 — 같은 **공개** 엔드포인트가
//     /api/v1/auth/oidc/providers   → 200
//     /api/v1/auth//oidc/providers  → 401
//   즉 토큰이 잘 붙어도 성공할 수 없는 요청이고, 401 은 원인이 아니라 증상이다.
//
// ★왜 유닛 테스트로 부족한가. 결함은 **훅 하나**가 아니라 「비어질 수 있는 값을 경로
//   빌더에 넘기는 호출 양식」이다. 훅마다 테스트를 붙이면 다음에 생기는 새 호출부는
//   아무도 안 본다 — 이 저장소가 이름 붙인 「두 목록이 서로를 검사하지 않는다」 양식이다.
//   그래서 **호출 양식 자체**를 금지하고, 예외는 한 가지 형태(호출부 `enabled`)만 연다.
//
// ─────────────────────────────────────────────────────────────────────────────
// 규칙
// ─────────────────────────────────────────────────────────────────────────────
// `apps/web/src/**` 에서 `use*(<식> ?? '')` · `use*(<식> || '')` 로 **API 훅**을 부르면
// 실패한다. API 훅 = `apps/web/src/hooks/*.ts` 가 export 하고 그 파일이 `@/api/` 를
// import 하는 훅(= 경로를 만들어 서버로 나가는 훅).
//
// 통과하는 길은 둘이다.
//   ① 폴백을 없애고 훅이 스스로 빈 값을 막는다 (`useVersions` 가 택한 길 — 소비처 전부가 산다)
//   ② 같은 호출에 `enabled` 를 함께 넘겨 조회를 미룬다 (`useCustomFields` 선례)
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'

/** 프론트 소스 루트 */
const WEB_SRC = 'apps/web/src'

/** API 훅 정의 디렉터리 — 여기서 훅 이름 목록을 만든다 */
const HOOKS_DIR = path.join(WEB_SRC, 'hooks')

/**
 * 빈 문자열 폴백을 인자로 넘기는 훅 호출.
 *
 * `useVersions(issue?.projectKey ?? '')` 의 `useVersions` 와 인자 전체를 잡는다.
 * 여는 괄호부터 같은 줄 끝까지를 인자로 보므로, 옵션 객체가 같은 줄에 있으면 함께 잡힌다
 * (`enabled` 예외 판정에 쓴다).
 */
const EMPTY_FALLBACK_CALL = /\b(use[A-Z][A-Za-z0-9]*)\(([^\n]*(?:\?\?|\|\|)\s*(?:''|""))([^\n]*)/g

/** 검사 대상 확장자 */
const SOURCE_EXTENSIONS = new Set(['.ts', '.tsx'])

/** 파일 하나가 검사 대상인지 — 테스트·타입 선언은 뺀다 */
function isSourceFile(file: string): boolean {
  if (!SOURCE_EXTENSIONS.has(path.extname(file))) return false
  return !file.includes('.test.') && !file.endsWith('.d.ts')
}

/** 디렉터리를 재귀로 훑어 검사 대상 파일 경로를 모은다 */
function collectSources(dir: string): string[] {
  const found: string[] = []
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry)
    if (statSync(full).isDirectory()) {
      found.push(...collectSources(full))
    } else if (isSourceFile(entry)) {
      found.push(full)
    }
  }
  return found
}

/**
 * 경로를 만들어 서버로 나가는 훅 이름 집합.
 *
 * ★`@/api/` 를 import 하는 훅 파일만 본다. 순수 상태 훅(`use-column-widths` 등)까지 금지하면
 * 판별식이 관계없는 코드를 막아 사람이 규칙을 우회하게 만든다 — 그러면 규칙이 죽는다.
 */
function apiHookNames(): Set<string> {
  const names = new Set<string>()
  for (const file of readdirSync(HOOKS_DIR)) {
    if (!isSourceFile(file)) continue
    const source = readFileSync(path.join(HOOKS_DIR, file), 'utf8')
    if (!source.includes("from '@/api/")) continue
    for (const [, name] of source.matchAll(/export function (use[A-Z][A-Za-z0-9]*)\(/g)) {
      names.add(name)
    }
  }
  return names
}

/** 위반 한 건 */
interface Violation {
  file: string
  line: number
  text: string
}

/** 전 소스를 훑어 위반을 모은다 */
function findViolations(hookNames: ReadonlySet<string>): Violation[] {
  const violations: Violation[] = []

  for (const file of collectSources(WEB_SRC)) {
    const lines = readFileSync(file, 'utf8').split('\n')
    lines.forEach((text, index) => {
      // 주석 줄은 규칙을 설명하느라 같은 문자열을 담는다 — 코드가 아니므로 뺀다
      if (/^\s*(\/\/|\*|\/\*)/.test(text)) return

      for (const match of text.matchAll(EMPTY_FALLBACK_CALL)) {
        const [, hookName, args, rest] = match
        if (!hookNames.has(hookName ?? '')) continue
        // 예외 ② — 같은 호출에서 조회를 미루면 빈 값으로 요청이 나가지 않는다
        if (`${args ?? ''}${rest ?? ''}`.includes('enabled')) continue
        violations.push({ file, line: index + 1, text: text.trim() })
      }
    })
  }

  return violations
}

describe('빈 경로 세그먼트 차단 — API 훅에 빈 문자열 폴백 금지', () => {
  const hookNames = apiHookNames()

  test('훅 목록이 비어 있지 않다 (비-공허 확인)', () => {
    // ★이 짝이 없으면 아래 본 판정은 「훅을 하나도 못 찾아서」 통과한다. 디렉터리 이름이
    //   바뀌거나 import 관례가 달라지는 순간 판별식이 조용히 죽는 자리다.
    assert.ok(
      hookNames.size >= 10,
      `${HOOKS_DIR} 에서 API 훅을 ${hookNames.size}개만 찾았다 — 스캔이 깨졌다`,
    )
  })

  test('알려진 API 훅 이름이 실제로 잡힌다 (스캔 정합)', () => {
    // 이름을 하나 박아 두어, 정규식이 망가져 빈 집합이 아니라 **엉뚱한 집합**이 되는
    // 경우까지 가른다.
    assert.ok(hookNames.has('useVersions'), 'useVersions 를 API 훅으로 인식하지 못한다')
  })

  test('소스 스캔이 실제 파일을 읽는다 (비-공허 확인)', () => {
    assert.ok(collectSources(WEB_SRC).length >= 100, '프론트 소스 스캔 결과가 비정상적으로 적다')
  })

  test("API 훅에 `?? ''` / `|| ''` 를 넘기는 호출이 없다", () => {
    const violations = findViolations(hookNames)

    assert.deepEqual(
      violations.map((v) => `${v.file}:${v.line}  ${v.text}`),
      [],
      [
        '빈 문자열 폴백을 API 훅에 넘기면 `/api/v1/projects//versions` 같은 URL 이 나간다.',
        '빈 세그먼트는 Spring Security 포괄 규칙으로 떨어져 **로그인 상태에서도 401** 이 된다',
        '(프로덕션 실측 2026-09-07 — 공개 엔드포인트조차 `//` 를 끼우면 200 이 401 이 된다).',
        '',
        '고치는 길 둘.',
        '  ① 폴백을 지우고 훅이 빈 값을 막게 한다 — 소비처 전부가 산다 (`useVersions` 선례)',
        '  ② 같은 호출에 `enabled` 를 넘겨 조회를 미룬다 (`useCustomFields` 선례)',
      ].join('\n'),
    )
  })
})
