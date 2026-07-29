// 활성 프로젝트 계약 회귀 봉인 — 프로젝트 키 하드코딩 재발 + 설계 B(진입로 무변경) 고정 (FR-UX-07 Task 8)
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { resolve, join, relative } from 'node:path'
import { describe, it, expect } from 'vitest'

/** apps/web/src 루트 — 이 파일 기준 `..` */
const SRC_ROOT = resolve(import.meta.dirname, '..')

/**
 * 스캔 대상 디렉토리 — **명시 목록**이다. 글롭으로 `src` 전체를 훑으면 `src/mocks/**`의
 * fixture 60건 이상(`projectKey: 'ATLAS'` 등)에 걸려 처음부터 영구 RED가 된다.
 * 프로덕션 코드가 사는 곳만 연다(`button-primitive-usage.test.ts`의 명시 목록 방식 승계).
 */
const SCAN_DIRS = ['routes', 'components', 'hooks', 'lib', 'api'] as const

/** 테스트·목·픽스처는 프로젝트 키 리터럴을 정당하게 쓴다 — 스캔에서 제외 */
function isExcluded(relPath: string): boolean {
  return (
    relPath.includes('__tests__/') ||
    /\.test\.[cm]?[jt]sx?$/.test(relPath) ||
    relPath.startsWith('mocks/') ||
    relPath.startsWith('test/')
  )
}

/**
 * 프로젝트 키 하드코딩 판별식 2종.
 *
 * ★ 초안 판별식(`projectKey`에 대입되는 패턴 하나)은 **없애려던 대상인
 * `const DEFAULT_PROJECT_KEY = 'ATLAS'`를 매치하지 못했다** — 봉인이 자기 목적을 못 잡는
 * 상태였다(plan 독립 리뷰 BLOCKER B5). 상수 선언형과 대입/프로퍼티형을 모두 연다.
 *
 * 대문자 2~10자로 좁히는 이유 — 프로젝트 키는 백엔드 계약상 영문 대문자 + 숫자다.
 * 소문자를 포함하면 `projectKey: projectKey` 같은 정상 코드까지 잡는다.
 */
const HARDCODE_PATTERNS: readonly { readonly code: string; readonly re: RegExp }[] = [
  { code: 'P1-상수선언', re: /(?:DEFAULT_)?PROJECT_KEY\s*=\s*['"][A-Z][A-Z0-9]{1,9}['"]/ },
  { code: 'P2-대입', re: /projectKey\s*[:=]\s*['"][A-Z][A-Z0-9]{1,9}['"]/ },
  // ★ P3 — 별칭 상수 우회 차단(코드리뷰 C7). P1 은 식별자가 `PROJECT_KEY` 로 **끝나야만**
  // 잡으므로 `const FALLBACK = 'ATLAS'` 가 P1·P2 를 모두 빠져나간다.
  // 판별식은 "프로덕션 코드(주석 제외)에 **시드 프로젝트 키 리터럴**이 있으면 안 된다" —
  // 이 값들은 MSW 시드 전용이라 프로덕션 코드가 알 이유가 없다. 실측상 코드 히트 0건,
  // KDoc 예시(`@param projectKey ... 예: "ATLAS"`)만 존재하며 주석은 스캔에서 제외된다.
  { code: 'P3-시드리터럴', re: /['"](?:ATLAS|MIDDLE|ZETA|NOVA)['"]/ },
]

// 한계. 이 판별식은 시드 4키만 막는다. 시드가 아닌 실 프로젝트 키를 임의 이름 상수에
// 넣는 형태는 못 잡는다 — 형태 기반 판별식은 실측 95히트/40파일(정당한 enum 리터럴)이라 채택 불가.

/**
 * 설계 B 고정 — 이슈 목록 진입로 4곳은 활성 프로젝트를 **모른다**.
 *
 * 라우트가 스스로 해소하므로 링크는 손댈 필요가 없다. 여기에 훅을 import하기 시작하면
 * ①비동기 순서 문제(사이드바가 `useProjects()`보다 먼저 렌더) ②`QUICK_LINKS` 순서 계약
 * ③`SHORTCUTS` 5종 동결(프론트 2단언 + 백엔드 `KeymapAction` enum + DB CHECK)이 흔들린다.
 */
const ENTRY_POINT_FILES = [
  'components/layout/Sidebar.tsx',
  'components/command-palette/commands.ts',
  'components/keyboard-shortcuts/shortcuts.ts',
  'lib/start-page.ts',
] as const

/** 활성 프로젝트 모듈 — 진입로가 import하면 안 되는 대상 */
const ACTIVE_PROJECT_MODULES = ['use-active-project', 'use-resolved-active-project'] as const

/** 스캔 대상 디렉토리를 재귀 순회해 프로덕션 소스 파일 상대경로를 모은다 */
function collectSourceFiles(): string[] {
  const found: string[] = []

  function walk(absDir: string): void {
    for (const entry of readdirSync(absDir)) {
      const abs = join(absDir, entry)
      if (statSync(abs).isDirectory()) {
        walk(abs)
        continue
      }
      if (!/\.[cm]?[jt]sx?$/.test(entry)) continue
      const rel = relative(SRC_ROOT, abs)
      if (isExcluded(rel)) continue
      found.push(rel)
    }
  }

  for (const dir of SCAN_DIRS) walk(join(SRC_ROOT, dir))
  return found
}

describe('활성 프로젝트 계약 봉인 (FR-UX-07)', () => {
  it('스캔이 비어 있지 않다 — 목록이 0이면 아래 단언들이 공허 통과한다', () => {
    const files = collectSourceFiles()
    expect(files.length).toBeGreaterThan(200)
  })

  it('프로덕션 코드에 DEFAULT_PROJECT_KEY 계열·projectKey 대입·시드 키 리터럴이 없다', () => {
    const violations: string[] = []

    for (const rel of collectSourceFiles()) {
      const source = readFileSync(join(SRC_ROOT, rel), 'utf8')
      for (const { code, re } of HARDCODE_PATTERNS) {
        source.split('\n').forEach((line, i) => {
          // 주석은 제외한다 — 이 PR이 남긴 "DEFAULT_PROJECT_KEY 하드코딩 대체" 같은 설명문과
          // KDoc 예시(`/** 프로젝트 키. 예: "ATLAS" */`)가 대상이 아니다. AST가 아니라
          // 정규식이므로 줄 단위로 판정한다.
          //
          // ★ `/*`를 빠뜨리면 **한 줄 KDoc**(`/** … */`)이 그대로 새어 들어온다. P3를 넣고서야
          // `api/boards.ts:25,224`로 드러났다 — 패턴을 넓히면 필터의 구멍이 함께 드러난다.
          //
          // ★ 줄 전체를 면제하는 대신 한 줄 블록주석(`/** … */`)만 걷어낸다. `.`는 개행을
          // 안 먹으므로 줄 경계를 넘지 않고, 줄 수가 보존되므로 위반 메시지의 줄번호가
          // 그대로 유효하다(전역 `/\/\*[\s\S]*?\*\//g` 제거는 줄번호를 무너뜨린다).
          const scannable = line.replace(/\/\*.*?\*\//g, ' ')
          const trimmed = scannable.trim()
          if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')) {
            return
          }
          if (re.test(scannable)) violations.push(`${rel}:${i + 1} [${code}] ${line.trim()}`)
        })
      }
    }

    expect(violations).toEqual([])
  })

  it('설계 B — 이슈 진입로 4곳이 활성 프로젝트 모듈을 import하지 않는다', () => {
    const violations: string[] = []

    for (const rel of ENTRY_POINT_FILES) {
      const source = readFileSync(join(SRC_ROOT, rel), 'utf8')
      for (const mod of ACTIVE_PROJECT_MODULES) {
        if (source.includes(mod)) violations.push(`${rel} → ${mod}`)
      }
    }

    expect(violations).toEqual([])
  })

  it('진입로 4파일이 모두 실재한다 — 경로가 바뀌면 위 단언이 조용히 공허해진다', () => {
    for (const rel of ENTRY_POINT_FILES) {
      expect(() => readFileSync(join(SRC_ROOT, rel), 'utf8')).not.toThrow()
    }
  })
})
