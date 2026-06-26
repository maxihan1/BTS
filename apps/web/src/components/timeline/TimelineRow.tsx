// 타임라인 단일 행 막대/마일스톤 렌더 컴포넌트 (FR-TL-01 D6)
import type { JSX } from 'react'
import type { TimelineItem } from '@/api/timeline'
import type { DateRange } from '@/lib/timeline-layout'
import { computeBarGeometry } from '@/lib/timeline-layout'
import { timelineLabels } from '@/i18n/timeline-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 행 높이(px) — GanttChart와 동일 값을 사용해야 한다 */
export const ROW_HEIGHT_PX = 32

/** 막대 세로 높이(px) */
const BAR_HEIGHT_PX = 18

/**
 * 이슈 타입별 막대 색 클래스 맵.
 *
 * 기존 색맵 없음(실측 확인됨, PR #192) — timeline 전용으로 신규 정의.
 * epic/story/task/bug + 폴백.
 */
const ISSUE_TYPE_COLORS: Record<string, string> = {
  epic: 'bg-purple-500',
  story: 'bg-blue-400',
  task: 'bg-teal-400',
  bug: 'bg-red-400',
}

/** 알 수 없는 이슈 타입 폴백 색 */
const COLOR_FALLBACK = 'bg-gray-400'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** TimelineRow Props */
export interface TimelineRowProps {
  /** 타임라인 아이템 */
  item: TimelineItem
  /** 전체 날짜 범위 */
  range: DateRange
  /** 일 단위 열 폭(px) */
  dayWidth: number
  /** 행(막대/레이블) 클릭 시 호출 콜백 */
  onSelectIssue: (key: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// TimelineRow 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 타임라인 단일 행 막대 영역 컴포넌트.
 *
 * - `computeBarGeometry`로 barX·barWidth·milestoneX·openStart·openEnd를 계산한다.
 * - 막대는 이슈 타입 색 div로 렌더한다.
 * - `openStart` — 좌측 둥근 모서리 없음(날짜 개방).
 * - `openEnd` — 우측 둥근 모서리 없음(날짜 개방).
 * - `milestoneX !== null` — ◆ 마일스톤 표식을 해당 x 위치에 렌더한다 (FR4).
 * - 막대 클릭 → `onSelectIssue(item.key)` 호출.
 * - **jsdom은 SVG getBBox를 구현하지 않으므로 SVG 대신 div 기반으로 렌더한다.**
 */
export function TimelineRow({ item, range, dayWidth, onSelectIssue }: TimelineRowProps): JSX.Element {
  const geo = computeBarGeometry(item, range, dayWidth)
  const colorClass = ISSUE_TYPE_COLORS[item.issueType] ?? COLOR_FALLBACK

  const roundedLeft = geo.openStart ? '' : 'rounded-l'
  const roundedRight = geo.openEnd ? '' : 'rounded-r'

  const barAriaLabel = timelineLabels.row.barAriaLabel(item.key, item.startDate, item.dueDate)

  return (
    <div className="relative flex-1 border-b border-border" style={{ height: ROW_HEIGHT_PX }}>
      {/* Gantt 막대 */}
      <div
        role="button"
        tabIndex={0}
        aria-label={barAriaLabel}
        className={`absolute cursor-pointer ${colorClass} ${roundedLeft} ${roundedRight} hover:opacity-80`}
        style={{
          left: geo.barX,
          width: geo.barWidth,
          top: (ROW_HEIGHT_PX - BAR_HEIGHT_PX) / 2,
          height: BAR_HEIGHT_PX,
        }}
        onClick={() => onSelectIssue(item.key)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') onSelectIssue(item.key)
        }}
      />

      {/* targetDate 마일스톤 ◆ 표식 (FR4) */}
      {geo.milestoneX !== null && (
        <span
          className="absolute -translate-x-1/2 top-1/2 -translate-y-1/2 text-amber-500 font-bold pointer-events-none select-none"
          style={{ left: geo.milestoneX }}
          aria-label={timelineLabels.row.milestoneAriaLabel(item.key)}
        >
          ◆
        </span>
      )}
    </div>
  )
}
