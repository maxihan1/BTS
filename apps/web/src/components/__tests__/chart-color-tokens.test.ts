// recharts 차트 컴포넌트가 하드코딩 hex 대신 --chart-* 토큰을 소비하는지 전수 스캔하는 회귀 가드 (FR-UX-06 PR22)
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

/**
 * ADR D7이 "실소비 PR에서 정의한다"고 지목한 recharts 가젯 6종.
 * 이 목록은 `grep -rl recharts src` 실측 결과(테스트 파일 제외)와 일치해야 한다 —
 * 새 차트가 추가되면 여기에도 등재해서 하드코딩 색이 다시 새는 것을 막는다.
 */
const CHART_SOURCES = [
  'burndown/BurndownChart.tsx',
  'cfd/CfdChart.tsx',
  'cycle-time/CycleTimeBoxPlot.tsx',
  'cycle-time/CycleTimeHistogram.tsx',
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

  it('6종이 --chart-1~5를 모두 소비한다 (정의만 하고 안 쓰는 토큰 0)', () => {
    const all = CHART_SOURCES.map(sourceOf).join('\n')
    for (const n of [1, 2, 3, 4, 5]) {
      expect(all).toContain(`var(--chart-${n})`)
    }
  })
})
