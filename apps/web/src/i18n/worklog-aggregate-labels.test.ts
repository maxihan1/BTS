// 워크로그 집계 보고 페이지 i18n 라벨 단위 테스트 — 키 존재 + 콜론 종결 검증 (FR-TT-02 D6)

import { describe, it, expect } from 'vitest'
import { worklogAggregateLabels } from './worklog-aggregate-labels'

describe('worklogAggregateLabels — page 그룹', () => {
  it('page.title 키가 존재한다', () => {
    expect(worklogAggregateLabels.page.title).toBeTruthy()
  })

  it('page.description 키가 존재한다', () => {
    expect(worklogAggregateLabels.page.description).toBeTruthy()
  })
})

describe('worklogAggregateLabels — filter 그룹', () => {
  it('filter.dimensionLabel 키가 존재한다', () => {
    expect(worklogAggregateLabels.filter.dimensionLabel).toBeTruthy()
  })

  // C3 — 날짜범위 에러 인라인 문자열 i18n 분리 (codereview fix)
  it('filter.dateRangeError 키가 존재한다', () => {
    expect(worklogAggregateLabels.filter.dateRangeError).toBeTruthy()
  })

  it('filter.dimensionIssue 키가 존재한다', () => {
    expect(worklogAggregateLabels.filter.dimensionIssue).toBeTruthy()
  })

  it('filter.dimensionUser 키가 존재한다', () => {
    expect(worklogAggregateLabels.filter.dimensionUser).toBeTruthy()
  })

  it('filter.dimensionPeriod 키가 존재한다', () => {
    expect(worklogAggregateLabels.filter.dimensionPeriod).toBeTruthy()
  })

  it('filter.granularityLabel 키가 존재한다', () => {
    expect(worklogAggregateLabels.filter.granularityLabel).toBeTruthy()
  })

  it('filter.granularityDay 키가 존재한다', () => {
    expect(worklogAggregateLabels.filter.granularityDay).toBeTruthy()
  })

  it('filter.granularityWeek 키가 존재한다', () => {
    expect(worklogAggregateLabels.filter.granularityWeek).toBeTruthy()
  })

  it('filter.granularityMonth 키가 존재한다', () => {
    expect(worklogAggregateLabels.filter.granularityMonth).toBeTruthy()
  })

  it('filter.fromLabel 키가 존재한다', () => {
    expect(worklogAggregateLabels.filter.fromLabel).toBeTruthy()
  })

  it('filter.toLabel 키가 존재한다', () => {
    expect(worklogAggregateLabels.filter.toLabel).toBeTruthy()
  })
})

describe('worklogAggregateLabels — summary 그룹', () => {
  it('summary.totalTimeLabel 키가 존재한다', () => {
    expect(worklogAggregateLabels.summary.totalTimeLabel).toBeTruthy()
  })
})

describe('worklogAggregateLabels — chart 그룹', () => {
  it('chart.title 키가 존재한다', () => {
    expect(worklogAggregateLabels.chart.title).toBeTruthy()
  })

  it('chart.ariaLabel 키가 존재한다', () => {
    expect(worklogAggregateLabels.chart.ariaLabel).toBeTruthy()
  })

  it('chart.topNHint 키가 존재한다', () => {
    expect(worklogAggregateLabels.chart.topNHint).toBeTruthy()
  })

  it('chart.topNHint는 {count} 플레이스홀더를 포함한다', () => {
    const hint = worklogAggregateLabels.chart.topNHint
    expect(hint).toContain('{count}')
  })
})

describe('worklogAggregateLabels — table 그룹', () => {
  it('table.headerLabel 키가 존재한다', () => {
    expect(worklogAggregateLabels.table.headerLabel).toBeTruthy()
  })

  it('table.headerTimeSpent 키가 존재한다', () => {
    expect(worklogAggregateLabels.table.headerTimeSpent).toBeTruthy()
  })

  it('table.headerCount 키가 존재한다', () => {
    expect(worklogAggregateLabels.table.headerCount).toBeTruthy()
  })

  it('table.totalRow 키가 존재한다', () => {
    expect(worklogAggregateLabels.table.totalRow).toBeTruthy()
  })

  it('table.unknownDisplayName 키가 존재한다', () => {
    expect(worklogAggregateLabels.table.unknownDisplayName).toBeTruthy()
  })
})

describe('worklogAggregateLabels — empty 그룹', () => {
  it('empty.message 키가 존재한다', () => {
    expect(worklogAggregateLabels.empty.message).toBeTruthy()
  })
})

describe('worklogAggregateLabels — error 그룹', () => {
  it('error.generalMessage 키가 존재한다', () => {
    expect(worklogAggregateLabels.error.generalMessage).toBeTruthy()
  })
})

// ★ 콜론 종결 금지 검증 (글로벌 CLAUDE.md §5 — 한국어 문장은 콜론으로 끝내지 않음)
describe('worklogAggregateLabels — 콜론 종결 금지 검증 (글로벌 §5)', () => {
  /** 중첩 객체를 재귀적으로 순회해 모든 string 값을 추출한다. */
  function collectStrings(obj: Record<string, unknown>): string[] {
    return Object.values(obj).flatMap((v) => {
      if (typeof v === 'string') return [v]
      if (typeof v === 'object' && v !== null) return collectStrings(v as Record<string, unknown>)
      return []
    })
  }

  it('모든 라벨 문자열은 콜론으로 끝나지 않는다', () => {
    const allStrings = collectStrings(worklogAggregateLabels as unknown as Record<string, unknown>)
    for (const value of allStrings) {
      expect(value, `"${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
    }
  })
})
