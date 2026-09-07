// recharts 차트 컴포넌트가 하드코딩 hex 대신 --chart-* 토큰을 소비하는지 전수 스캔하는 회귀 가드 (FR-UX-06 PR22)
import { readFileSync, readdirSync } from 'node:fs'
import { relative, resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

/**
 * 차트 색 토큰을 써야 하는 소스 전량.
 *
 * ★「recharts 를 쓰는 파일」이 아니다. `CycleTimeBoxPlot` 은 recharts 가 박스플롯을 네이티브로
 * 지원하지 않아 **커스텀 SVG** 인데, 색 규칙의 대상인 것은 마찬가지다. 종전 주석은 이 목록이
 * `grep -rl recharts src` 실측과 「일치해야 한다」고 적었지만 그 주장은 이미 거짓이었다 —
 * 아래 대조 테스트가 그것을 드러냈다.
 *
 * 그래서 대조는 **한 방향**만 한다. recharts 를 쓰면 반드시 목록에 있어야 하고(빠지면 하드코딩
 * 색이 새어도 아무도 안 잡는다), 목록에 recharts 안 쓰는 항목이 있는 것은 정상이다.
 */
const CHART_SOURCES = [
  'burndown/BurndownChart.tsx',
  'cfd/CfdChart.tsx',
  'cycle-time/CycleTimeBoxPlot.tsx',
  'cycle-time/CycleTimeHistogram.tsx',
  'dashboard/gadgets/DistributionChartGadget.tsx',
  'velocity/VelocityChart.tsx',
  'worklog/WorklogAggregateChart.tsx',
] as const

/** 6자리 hex 리터럴 (#RRGGBB). 3자리 축약형은 이 코드베이스에서 쓰이지 않는다. */
const HEX_LITERAL = /#[0-9a-fA-F]{6}\b/

function sourceOf(relativePath: string): string {
  return readFileSync(resolve(import.meta.dirname, '..', relativePath), 'utf-8')
}

describe('FR-UX-06 PR22 — 차트 색은 --chart-* 토큰으로만 지정한다', () => {
  it.each(CHART_SOURCES)('%s — 하드코딩 hex 리터럴 0건', (path) => {
    const matches = sourceOf(path).match(new RegExp(HEX_LITERAL, 'g')) ?? []
    expect(matches).toEqual([])
  })

  it.each(CHART_SOURCES)('%s — var(--chart-N)를 실제로 소비한다', (path) => {
    expect(sourceOf(path)).toMatch(/var\(--chart-[1-5]\)/)
  })

  it('전체가 --chart-1~5를 모두 소비한다 (정의만 하고 안 쓰는 토큰 0)', () => {
    const all = CHART_SOURCES.map(sourceOf).join('\n')
    for (const n of [1, 2, 3, 4, 5]) {
      expect(all).toContain(`var(--chart-${n})`)
    }
  })

  /**
   * ★손목록이 실측과 갈리는 것을 막는다.
   *
   * `CHART_SOURCES` 는 사람이 적는 목록이고, 위 주석은 「`grep -rl recharts src` 실측 결과와
   * 일치해야 한다」고 **자연어로만** 적고 있었다. 자연어는 강제가 아니다
   * (`docs/rules/behavior-rules.md §3`) — 새 차트를 만들고 등재를 잊으면 그 파일은
   * 하드코딩 hex 를 써도 **아무도 안 잡는다**. 목록과 실측이 서로를 검사하지 않는
   * 이 저장소의 지배 결함 양식 그대로다.
   *
   * 그래서 여기서 실제로 대조한다. 방향은 한쪽이다 — **recharts 를 쓰는 소스는 전부 목록에
   * 있어야 한다**(목록에만 있고 실측에 없는 것은 파일이 지워진 경우라 `sourceOf` 가 먼저 throw 한다).
   */
  it('★recharts 를 import 하는 소스가 전부 목록에 있다 (손목록 ↔ 실측 대조)', () => {
    const componentsRoot = resolve(import.meta.dirname, '..')
    const found: string[] = []

    const walk = (dir: string): void => {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const full = resolve(dir, entry.name)
        if (entry.isDirectory()) {
          walk(full)
          continue
        }
        // 테스트 파일은 recharts 를 **목하려고** import 한다 — 색 규칙의 대상이 아니다.
        if (!entry.name.endsWith('.tsx') || entry.name.includes('.test.')) continue
        if (/from 'recharts'/.test(readFileSync(full, 'utf-8'))) {
          found.push(relative(componentsRoot, full))
        }
      }
    }
    walk(componentsRoot)

    // 비-공허 짝 — 스캐너가 깨져 0건이면 빈 차집합으로 조용히 통과한다.
    // ★목록 길이와 비교하지 않는다. 목록에는 커스텀 SVG 차트도 들어 있어 실측(recharts 사용)이
    //   목록보다 적은 것이 정상이다. 하한은 「스캐너가 살아 있다」만 재면 된다.
    expect(found.length).toBeGreaterThanOrEqual(5)

    const missing = found.filter((f) => !CHART_SOURCES.includes(f as (typeof CHART_SOURCES)[number]))
    expect(missing).toEqual([])
  })
})
