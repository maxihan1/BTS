// 타임라인 날짜 축 헤더 컴포넌트 — 줌 레벨별 눈금 렌더 (FR-TL-01 D6 / FR-TL-03)
import type { JSX } from 'react'
import type { DateRange } from '@/lib/timeline-layout'
import { daysBetweenUtc } from '@/lib/timeline-layout'
import type { ZoomLevel } from '@/lib/timeline-zoom'
import { DEFAULT_ZOOM, getAxisConfig } from '@/lib/timeline-zoom'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 월요일 UTC 요일 인덱스 */
const MONDAY_UTC = 1

/** 하루를 ms로 표현한 값 */
const MS_PER_DAY = 86_400_000

/** 축 헤더 행 높이(px) — 월 눈금 행 + 주 눈금 행 */
const AXIS_ROW_HEIGHT_PX = 24

/**
 * 축 헤더 전체 높이(px) — 월 눈금 행 + 주 눈금 행 합계.
 *
 * GanttChart가 이 값을 import해 (a) 레이블 열 스페이서 높이, (b) DependencyOverlay axisOffset,
 * (c) overlayHeight 계산의 단일 출처로 사용한다 (C-1 fix).
 * AXIS_ROW_HEIGHT_PX 변경 시 이 값과 연동되는 모든 좌표가 자동 갱신된다.
 */
export const TIMELINE_AXIS_HEIGHT_PX = AXIS_ROW_HEIGHT_PX * 2

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

interface TickItem {
  /** 범위 시작일로부터 몇 번째 날 (0-indexed) */
  offsetDay: number
  /** 표시 레이블 */
  label: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 눈금 계산
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 날짜 범위에서 월 눈금 목록을 계산한다.
 * 각 월의 첫 번째 날을 기준으로 눈금을 찍는다.
 *
 * @param range 전체 날짜 범위
 * @returns 월 눈금 목록
 */
function computeMonthTicks(range: DateRange): TickItem[] {
  const totalDays = daysBetweenUtc(range.startMs, range.endMs)
  const ticks: TickItem[] = []

  for (let day = 0; day < totalDays; day++) {
    const ms = range.startMs + day * MS_PER_DAY
    const date = new Date(ms)
    if (date.getUTCDate() === 1) {
      const year = date.getUTCFullYear()
      const month = String(date.getUTCMonth() + 1).padStart(2, '0')
      ticks.push({ offsetDay: day, label: `${year}.${month}` })
    }
  }

  return ticks
}

/**
 * 날짜 범위에서 주 눈금 목록을 계산한다.
 * 월요일마다 눈금을 찍는다.
 *
 * @param range 전체 날짜 범위
 * @returns 주 눈금 목록
 */
function computeWeekTicks(range: DateRange): TickItem[] {
  const totalDays = daysBetweenUtc(range.startMs, range.endMs)
  const ticks: TickItem[] = []

  for (let day = 0; day < totalDays; day++) {
    const ms = range.startMs + day * MS_PER_DAY
    const date = new Date(ms)
    if (date.getUTCDay() === MONDAY_UTC) {
      const mm = String(date.getUTCMonth() + 1).padStart(2, '0')
      const dd = String(date.getUTCDate()).padStart(2, '0')
      ticks.push({ offsetDay: day, label: `${mm}/${dd}` })
    }
  }

  return ticks
}

/**
 * 날짜 범위에서 일 눈금 목록을 계산한다.
 * 범위 내 매일 눈금을 찍는다.
 *
 * @param range 전체 날짜 범위
 * @returns 일 눈금 목록
 */
function computeDayTicks(range: DateRange): TickItem[] {
  const totalDays = daysBetweenUtc(range.startMs, range.endMs)
  const ticks: TickItem[] = []

  for (let day = 0; day < totalDays; day++) {
    const ms = range.startMs + day * MS_PER_DAY
    const date = new Date(ms)
    const dd = String(date.getUTCDate()).padStart(2, '0')
    ticks.push({ offsetDay: day, label: dd })
  }

  return ticks
}

/**
 * 날짜 범위에서 분기 눈금 목록을 계산한다.
 * 각 분기의 첫 번째 달(1·4·7·10월) 1일을 기준으로 눈금을 찍는다.
 *
 * @param range 전체 날짜 범위
 * @returns 분기 눈금 목록 (label: 'YYYY Q{n}')
 */
function computeQuarterTicks(range: DateRange): TickItem[] {
  const totalDays = daysBetweenUtc(range.startMs, range.endMs)
  const ticks: TickItem[] = []

  for (let day = 0; day < totalDays; day++) {
    const ms = range.startMs + day * MS_PER_DAY
    const date = new Date(ms)
    const month = date.getUTCMonth() // 0-indexed
    if (date.getUTCDate() === 1 && month % 3 === 0) {
      const year = date.getUTCFullYear()
      const quarter = Math.floor(month / 3) + 1
      ticks.push({ offsetDay: day, label: `${year} Q${quarter}` })
    }
  }

  return ticks
}

// ─────────────────────────────────────────────────────────────────────────────
// 눈금 컴퓨터 맵 — 날짜 단위 → 헬퍼 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 날짜 단위별 눈금 계산 함수 맵.
 *
 * AxisConfig 의 top('month'|'quarter')·bottom('day'|'week'|'month') 를
 * 단일 레코드로 통합해, getAxisConfig 반환값으로 바로 dispatch 할 수 있다.
 *
 * | unit    | 레이블 형식  | 눈금 기준        |
 * |---------|------------|-----------------|
 * | day     | DD         | 매일             |
 * | week    | MM/DD      | 매주 월요일(UTC)  |
 * | month   | YYYY.MM    | 매달 1일         |
 * | quarter | YYYY Q{n}  | 분기 첫달(1·4·7·10월) 1일 |
 */
const TICK_COMPUTERS: Readonly<
  Record<'day' | 'week' | 'month' | 'quarter', (range: DateRange) => TickItem[]>
> = {
  day: computeDayTicks,
  week: computeWeekTicks,
  month: computeMonthTicks,
  quarter: computeQuarterTicks,
}

// ─────────────────────────────────────────────────────────────────────────────
// TimelineAxis 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** TimelineAxis Props */
export interface TimelineAxisProps {
  /** 전체 날짜 범위 */
  range: DateRange
  /** 일 단위 열 폭(px) */
  dayWidth: number
  /**
   * 줌 레벨.
   * 미지정 시 DEFAULT_ZOOM('month')가 적용된다.
   * optional로 선언해 기존 호출부(GanttChart 등)의 타입 에러를 방지한다 (Task 6 이전 무회귀).
   */
  zoomLevel?: ZoomLevel
}

/**
 * 타임라인 날짜 축 헤더.
 *
 * - 상단 행: 줌 레벨에 따라 월(YYYY.MM) 또는 분기(YYYY Q{n}) 레이블
 * - 하단 행: 줌 레벨에 따라 일(DD) / 주(MM/DD) / 월(YYYY.MM) 레이블
 * - 모든 눈금은 UTC 기준으로 계산한다 (NFR4).
 * - `zoomLevel` 미지정 시 'month'가 적용되어 기존 동작을 유지한다.
 */
export function TimelineAxis({
  range,
  dayWidth,
  zoomLevel = DEFAULT_ZOOM,
}: TimelineAxisProps): JSX.Element {
  const totalDays = daysBetweenUtc(range.startMs, range.endMs)
  const totalWidth = totalDays * dayWidth
  const { top, bottom } = getAxisConfig(zoomLevel)

  const topTicks = TICK_COMPUTERS[top](range)
  const bottomTicks = TICK_COMPUTERS[bottom](range)

  return (
    <div className="relative select-none" style={{ width: totalWidth, height: TIMELINE_AXIS_HEIGHT_PX }}>
      {/* 상단 눈금 행 */}
      {topTicks.map((tick) => (
        <div
          key={`top-${tick.offsetDay}`}
          className="absolute top-0 text-xs text-muted-foreground font-medium border-l border-border pl-1 overflow-hidden whitespace-nowrap"
          style={{ left: tick.offsetDay * dayWidth, height: AXIS_ROW_HEIGHT_PX, lineHeight: `${AXIS_ROW_HEIGHT_PX}px` }}
        >
          {tick.label}
        </div>
      ))}

      {/* 하단 눈금 행 */}
      {bottomTicks.map((tick) => (
        <div
          key={`bottom-${tick.offsetDay}`}
          className="absolute text-xs text-muted-foreground border-l border-border pl-1 overflow-hidden whitespace-nowrap"
          style={{
            left: tick.offsetDay * dayWidth,
            top: AXIS_ROW_HEIGHT_PX,
            height: AXIS_ROW_HEIGHT_PX,
            lineHeight: `${AXIS_ROW_HEIGHT_PX}px`,
          }}
        >
          {tick.label}
        </div>
      ))}
    </div>
  )
}
