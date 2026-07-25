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
 * FR-UX-06 PR22에서 Tailwind 리터럴 색 → `--type-*` 토큰으로 이관.
 * 값은 디자인 스펙 §4.2 표(Epic Purple700 · Story Green600 · Task Blue700 · Bug Red700)를 따르며,
 * 이 과정에서 story(파랑→초록)·task(청록→파랑)가 스펙에 맞게 교정된다 — 의도된 시각 변화.
 * 라이트/다크 값은 토큰이 처리하므로 여기서 `dark:` 변형을 쓰지 않는다.
 */
const ISSUE_TYPE_COLORS: Record<string, string> = {
  epic: 'bg-type-epic',
  story: 'bg-type-story',
  task: 'bg-type-task',
  bug: 'bg-type-bug',
}

/** 알 수 없는 이슈 타입 폴백 색 — 중립 토큰 */
const COLOR_FALLBACK = 'bg-type-default'

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
 * **자체 div 기반 좌표 모델 (SVG 미사용)**:
 * - `barX` — 막대 왼쪽 끝의 px 오프셋. `(startDate - rangeStart)일 × dayWidth`.
 * - `barWidth` — 막대 폭(px). `(dueDate - startDate + 1)일 × dayWidth`.
 * - `milestoneX` — ◆ 표식 중앙 x(px). `(targetDate - rangeStart)일 × dayWidth`.
 * - 모든 좌표는 UTC 기준으로 계산되어 타임존 편차가 없다 (NFR4).
 * - jsdom은 SVG getBBox를 구현하지 않으므로 SVG 대신 `position: absolute` div로 렌더한다.
 *   테스트에서 픽셀 위치를 직접 단언하지 않고 DOM 구조만 검증한다.
 *
 * **개방 표식**:
 * - `openStart` — dueDate만 있는 경우: 좌측 둥근 모서리 없음.
 * - `openEnd` — startDate만 있는 경우: 우측 둥근 모서리 없음.
 *
 * - 막대 클릭 → `onSelectIssue(item.key)` 호출.
 * - `milestoneX !== null` → ◆ 마일스톤 표식을 해당 x 위치에 렌더한다 (FR4).
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
          className="absolute -translate-x-1/2 top-1/2 -translate-y-1/2 text-warning font-bold pointer-events-none select-none"
          style={{ left: geo.milestoneX }}
          aria-label={timelineLabels.row.milestoneAriaLabel(item.key)}
        >
          ◆
        </span>
      )}
    </div>
  )
}
