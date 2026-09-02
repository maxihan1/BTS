// use-sidebar-width 직접 소비를 허용목록으로 좁혀 「폭 판정이 두 곳으로 갈라지는」 회귀를 막는 판별식
import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { resolve, relative, sep } from 'node:path'

const SRC = resolve(__dirname, '../..')

/**
 * `use-sidebar-width` 를 **직접** 소비해도 되는 프로덕션 파일.
 *
 * - `hooks/use-sidebar-width.ts` — 자기 자신(스토어 정의)
 * - `hooks/use-sidebar-drawer.ts` — 유일한 래퍼. 접힘·모바일 판정을 얹어
 *   `useSidebarEffectiveWidth`(지금 실제로 그릴 폭)를 파생시킨다.
 *
 * 🛑 여기에 파일을 **추가하지 마라.** 소비처가 늘어난다는 것은 「지금 폭이 얼마인가」를
 *    또 한 곳에서 판정한다는 뜻이다. 형제 축(`use-sidebar-collapsed`)이 정확히 그 양식으로
 *    무너졌다 — `RecentIssuesMenu`(라벨 판정)와 `ShellLayout`(토글 판정)이 뒤처져
 *    모바일 드로어에서 「최근 이슈」가 통째로 사라졌다. 필요한 것은 새 소비가 아니라
 *    `use-sidebar-drawer.ts` 에 파생 훅을 하나 더 만드는 것이다.
 *
 * 특히 **모바일에서 폭을 직접 읽으면 안 된다** — 드로어는 264px 고정이라 저장된 폭(예: 420px)이
 * 그대로 실리면 화면을 거의 다 덮는다. 그 판정은 `useSidebarEffectiveWidth` 하나가 소유한다.
 */
const ALLOWED = new Set(['hooks/use-sidebar-width.ts', 'hooks/use-sidebar-drawer.ts'])

/**
 * 이 모듈을 가리키는 **모든 표기**를 잡는다.
 *
 * 🛑 `from '...'` 만 보면 안 된다. 이 저장소에는 쌍따옴표 import(`components/ui/tabs.tsx`)와
 * 동적 import(`main.tsx`)가 둘 다 존재한다. 정적 `from` 만 매칭하면 그 두 표기로 우회가
 * 가능하고, 그건 이 판별식이 막으려는 결함이 다른 문법으로 재현되는 것일 뿐이다.
 * 그래서 **모듈 지정자 문자열 자체**를 본다 — `from` · `import()` · `require()` 를 한 번에 덮는다.
 *
 * 대가는 주석 안에 `'…use-sidebar-width'` 를 따옴표로 적으면 걸린다는 것이다.
 * 가드는 fail-closed 가 맞으므로 그 오탐을 감수한다(백틱 표기는 걸리지 않는다).
 */
const MODULE_SPECIFIER_RE = /['"][^'"]*use-sidebar-width['"]/

/**
 * 주석을 걷어낸 소스.
 *
 * 🛑 소스 텍스트 가드는 **주석에 적힌 예시**를 코드로 오인한다. 저장소가 반복해 밟은 양식
 *    (`invariant-satisfied-by-helptext-not-logic`)이라 형제 판별식과 같은 방식으로 끊는다.
 */
function withoutComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '') // 블록 주석 · JSX 주석
    .split('\n')
    .filter((line) => !/^\s*(\/\/|\*)/.test(line)) // 줄 주석
    .join('\n')
}

/** 테스트·스토리·목업은 대상이 아니다 — 스토어를 직접 세팅하는 것이 그쪽의 정당한 일이다 */
function isProductionSource(rel: string): boolean {
  if (!/\.(ts|tsx)$/.test(rel)) return false
  if (rel.includes(`__tests__${sep}`)) return false
  if (/\.(test|spec)\.tsx?$/.test(rel)) return false
  if (rel.startsWith(`test${sep}`) || rel.startsWith(`mocks${sep}`)) return false
  return true
}

/** `apps/web/src` 전체를 훑어 프로덕션 소스 목록을 **도출**한다 (하드코딩 목록을 두지 않는다) */
function productionSources(dir: string = SRC): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules') continue
    const abs = resolve(dir, entry)
    if (statSync(abs).isDirectory()) {
      out.push(...productionSources(abs))
      continue
    }
    const rel = relative(SRC, abs)
    if (isProductionSource(rel)) out.push(rel)
  }
  return out
}

describe('use-sidebar-width 직접 소비 허용목록', () => {
  it('허용목록 밖의 프로덕션 파일은 use-sidebar-width 를 직접 import 하지 않는다', () => {
    const offenders: string[] = []
    for (const rel of productionSources()) {
      const source = readFileSync(resolve(SRC, rel), 'utf-8')
      if (!MODULE_SPECIFIER_RE.test(withoutComments(source))) continue
      if (ALLOWED.has(rel.split(sep).join('/'))) continue
      offenders.push(rel)
    }

    expect(
      offenders,
      '허용목록 밖에서 `use-sidebar-width` 를 직접 소비한다:\n' +
        `${offenders.join('\n')}\n\n` +
        '「지금 폭이 얼마인가」를 두 곳에서 판정하면 한쪽만 고쳐졌을 때 나머지가 조용히 썩는다.\n' +
        '모바일 드로어(264px 고정)와 아이콘 레일(64px)을 함께 아는 것은\n' +
        '`useSidebarEffectiveWidth` 하나뿐이다 — 새 파생이 필요하면 `use-sidebar-drawer.ts` 에 만들어라.',
    ).toEqual([])
  })

  it('허용목록의 파일이 실재하고 실제로 그 모듈을 소비한다 (공허한 허용목록 차단)', () => {
    // ★허용목록이 **존재하지 않는 파일**이나 **더는 소비하지 않는 파일**을 가리키면, 위 단언은
    //   무엇을 지워도 통과한다. 허용목록 쪽도 실측으로 묶는다.
    for (const rel of ALLOWED) {
      const source = readFileSync(resolve(SRC, rel), 'utf-8')
      const consumesOrDefines =
        MODULE_SPECIFIER_RE.test(withoutComments(source)) || rel.endsWith('use-sidebar-width.ts')
      expect(consumesOrDefines, `허용목록 항목이 죽어 있다: ${rel}`).toBe(true)
    }
  })

  it('스캐너가 실제로 파일을 훑는다 (0건 스캔으로 통과하는 것을 막는다)', () => {
    // 도출 로직이 조용히 빈 배열을 내면 위 차집합은 항상 통과한다.
    const sources = productionSources()
    expect(sources.length).toBeGreaterThan(100)
    expect(sources).toContain(['hooks', 'use-sidebar-drawer.ts'].join(sep))
  })
})
