// 타임라인 줌 레벨 순수 함수 단위 테스트 (FR-TL-03)
import { describe, expect, it } from 'vitest'
import {
  DEFAULT_ZOOM,
  ZOOM_LEVELS,
  ZOOM_PRESETS,
  getAxisConfig,
  nextZoomIn,
  nextZoomOut,
  parseZoomLevel,
} from './timeline-zoom'

// ─────────────────────────────────────────────────────────────────────────────
// ZOOM_LEVELS / DEFAULT_ZOOM 상수
// ─────────────────────────────────────────────────────────────────────────────

describe('ZOOM_LEVELS', () => {
  it('확대→축소 순서로 week, month, quarter 를 포함한다', () => {
    expect(ZOOM_LEVELS).toEqual(['week', 'month', 'quarter'])
  })
})

describe('DEFAULT_ZOOM', () => {
  it('기본 줌 레벨은 month 이다', () => {
    expect(DEFAULT_ZOOM).toBe('month')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ZOOM_PRESETS dayWidth 관계
// ─────────────────────────────────────────────────────────────────────────────

describe('ZOOM_PRESETS', () => {
  it('week dayWidth 은 month 보다 크다 (확대)', () => {
    expect(ZOOM_PRESETS.week.dayWidth).toBeGreaterThan(ZOOM_PRESETS.month.dayWidth)
  })

  it('month dayWidth 은 현행 DAY_WIDTH_PX(20) 과 같다 — 무회귀', () => {
    expect(ZOOM_PRESETS.month.dayWidth).toBe(20)
  })

  it('month dayWidth 은 quarter 보다 크다 (축소)', () => {
    expect(ZOOM_PRESETS.month.dayWidth).toBeGreaterThan(ZOOM_PRESETS.quarter.dayWidth)
  })

  it('quarter dayWidth 은 MIN_BAR_DAYS(1) 가시성을 위해 4 이상이다', () => {
    expect(ZOOM_PRESETS.quarter.dayWidth).toBeGreaterThanOrEqual(4)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// nextZoomIn
// ─────────────────────────────────────────────────────────────────────────────

describe('nextZoomIn', () => {
  it('month 에서 확대하면 week 를 반환한다', () => {
    expect(nextZoomIn('month')).toBe('week')
  })

  it('week 에서 확대하면 끝단 클램프 — week 를 반환한다', () => {
    expect(nextZoomIn('week')).toBe('week')
  })

  it('quarter 에서 확대하면 month 를 반환한다', () => {
    expect(nextZoomIn('quarter')).toBe('month')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// nextZoomOut
// ─────────────────────────────────────────────────────────────────────────────

describe('nextZoomOut', () => {
  it('month 에서 축소하면 quarter 를 반환한다', () => {
    expect(nextZoomOut('month')).toBe('quarter')
  })

  it('quarter 에서 축소하면 끝단 클램프 — quarter 를 반환한다', () => {
    expect(nextZoomOut('quarter')).toBe('quarter')
  })

  it('week 에서 축소하면 month 를 반환한다', () => {
    expect(nextZoomOut('week')).toBe('month')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// parseZoomLevel
// ─────────────────────────────────────────────────────────────────────────────

describe('parseZoomLevel', () => {
  it('유효한 레벨 quarter 를 그대로 반환한다', () => {
    expect(parseZoomLevel('quarter')).toBe('quarter')
  })

  it('유효한 레벨 week 를 그대로 반환한다', () => {
    expect(parseZoomLevel('week')).toBe('week')
  })

  it('유효한 레벨 month 를 그대로 반환한다', () => {
    expect(parseZoomLevel('month')).toBe('month')
  })

  it('화이트리스트에 없는 문자열이면 DEFAULT_ZOOM(month) 를 반환한다', () => {
    expect(parseZoomLevel('garbage')).toBe('month')
  })

  it('null 이면 DEFAULT_ZOOM(month) 를 반환한다', () => {
    expect(parseZoomLevel(null)).toBe('month')
  })

  it('빈 문자열이면 DEFAULT_ZOOM(month) 를 반환한다', () => {
    expect(parseZoomLevel('')).toBe('month')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// getAxisConfig
// ─────────────────────────────────────────────────────────────────────────────

describe('getAxisConfig', () => {
  it('week 레벨: top=month, bottom=day', () => {
    expect(getAxisConfig('week')).toEqual({ top: 'month', bottom: 'day' })
  })

  it('month 레벨: top=month, bottom=week', () => {
    expect(getAxisConfig('month')).toEqual({ top: 'month', bottom: 'week' })
  })

  it('quarter 레벨: top=quarter, bottom=month', () => {
    expect(getAxisConfig('quarter')).toEqual({ top: 'quarter', bottom: 'month' })
  })
})
