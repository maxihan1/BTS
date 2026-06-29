// 타임라인 날짜 축 헤더 컴포넌트 — 주(월요일)/월 눈금 렌더 (FR-TL-01 D6)
import type { JSX } from 'react'
import type { DateRange } from '@/lib/timeline-layout'
import { daysBetweenUtc } from '@/lib/timeline-layout'

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

// ─────────────────────────────────────────────────────────────────────────────
// TimelineAxis 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** TimelineAxis Props */
export interface TimelineAxisProps {
  /** 전체 날짜 범위 */
  range: DateRange
  /** 일 단위 열 폭(px) */
  dayWidth: number
}

/**
 * 타임라인 날짜 축 헤더.
 *
 * - 상단 행: 월 레이블 (YYYY.MM 형식, 매달 1일 위치)
 * - 하단 행: 주 레이블 (MM/DD 형식, 매주 월요일 위치)
 * - 모든 눈금은 UTC 기준으로 계산한다 (NFR4).
 */
export function TimelineAxis({ range, dayWidth }: TimelineAxisProps): JSX.Element {
  const totalDays = daysBetweenUtc(range.startMs, range.endMs)
  const totalWidth = totalDays * dayWidth
  const monthTicks = computeMonthTicks(range)
  const weekTicks = computeWeekTicks(range)

  return (
    <div className="relative select-none" style={{ width: totalWidth, height: AXIS_ROW_HEIGHT_PX * 2 }}>
      {/* 월 눈금 행 */}
      {monthTicks.map((tick) => (
        <div
          key={`month-${tick.offsetDay}`}
          className="absolute top-0 text-xs text-muted-foreground font-medium border-l border-border pl-1 overflow-hidden whitespace-nowrap"
          style={{ left: tick.offsetDay * dayWidth, height: AXIS_ROW_HEIGHT_PX, lineHeight: `${AXIS_ROW_HEIGHT_PX}px` }}
        >
          {tick.label}
        </div>
      ))}

      {/* 주 눈금 행 */}
      {weekTicks.map((tick) => (
        <div
          key={`week-${tick.offsetDay}`}
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
