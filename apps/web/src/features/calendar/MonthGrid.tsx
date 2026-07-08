// 캘린더 월 뷰(6주×7일) 그리드 컴포넌트 (FR-CA-01 Task 7)
import type { JSX } from 'react'
import type { CalendarIssueEvent, CalendarWorklogEvent } from '@/api/calendar'
import { calendarLabels } from '@/i18n/calendar-labels'
import {
  WEEKDAYS,
  toDateKey,
  isSameDay,
  formatDayLabel,
  buildDayEvents,
  IssueBarSegment,
  DueDateChip,
  WorklogChip,
} from './WeekGrid'

/**
 * 셀당 최대 노출 이벤트 수(막대+마감일칩+Worklog칩 합산) — 초과분은 "+N개" 버튼으로 접는다.
 *
 * T5 §4.5는 브레이크포인트별로 lg~xl:3개/md:2개를 구분하나, 이 앱은 브레이크포인트를
 * JS(matchMedia)로 감지하는 선례가 없고 신규 훅 도입은 이번 스코프 밖이라 단일 값(3, 가장
 * 여유로운 lg/xl 기준)으로 단순화했다 — 후속 개선 시 별도 hook으로 브레이크포인트별 값 적용 검토.
 */
const MAX_VISIBLE_EVENTS_PER_CELL = 3

/** MonthGrid Props */
export interface MonthGridProps {
  /** 42개(6주×7일) 그리드 날짜 — 화면에 렌더되는 전체가 조회창(T5 핵심결정 3) */
  days: Date[]
  /** 현재 월(0-11) — "이달 외" 패딩 셀 판정용 */
  currentMonth: number
  /** 오늘 날짜(오늘 표시/aria-current용) */
  today: Date
  issueEvents: CalendarIssueEvent[]
  worklogEvents: CalendarWorklogEvent[]
  /** 그리드 aria-label에 쓸 월 라벨 */
  monthLabel: string
  onNavigateIssue: (key: string) => void
  /** "+N개" 클릭 시 호출 — 해당 날짜가 포함된 주의 주 뷰로 전환(T5 §4.5) */
  onShowMore: (date: Date) => void
}

interface DayCellProps {
  day: Date
  isCurrentMonth: boolean
  isToday: boolean
  issueEvents: CalendarIssueEvent[]
  worklogEvents: CalendarWorklogEvent[]
  onNavigateIssue: (key: string) => void
  onShowMore: (date: Date) => void
}

/** 셀에 노출할 이벤트를 최대 개수로 자르고 나머지 개수를 계산한다(막대→마감일칩→Worklog칩 순, T5 §3.3) */
function sliceVisibleEvents(bucket: ReturnType<typeof buildDayEvents>) {
  const visibleBars = bucket.bars.slice(0, MAX_VISIBLE_EVENTS_PER_CELL)
  const remainingAfterBars = MAX_VISIBLE_EVENTS_PER_CELL - visibleBars.length
  const visibleDueChips = bucket.dueChips.slice(0, Math.max(0, remainingAfterBars))
  const remainingAfterDue = remainingAfterBars - visibleDueChips.length
  const visibleWorklogs = bucket.worklogs.slice(0, Math.max(0, remainingAfterDue))
  const totalCount = bucket.bars.length + bucket.dueChips.length + bucket.worklogs.length
  const visibleCount = visibleBars.length + visibleDueChips.length + visibleWorklogs.length
  return { visibleBars, visibleDueChips, visibleWorklogs, overflowCount: totalCount - visibleCount, totalCount }
}

/**
 * 월 뷰 단일 날짜 셀(T5 §3.3 `DayCell`).
 *
 * 이달 외 패딩 셀(이전/다음 달 날짜)은 `opacity-70` + 날짜 숫자 `text-muted-foreground`로
 * 우선순위를 낮추되, 이벤트는 그대로 렌더한다(핵심결정 3 — 실제 날짜에 이벤트가 있으면
 * 흐리게라도 보여야 한다). 클릭/키보드 동작은 이달 셀과 동일(비활성화하지 않음).
 */
function DayCell({ day, isCurrentMonth, isToday, issueEvents, worklogEvents, onNavigateIssue, onShowMore }: DayCellProps): JSX.Element {
  const dateKey = toDateKey(day)
  const bucket = buildDayEvents(dateKey, issueEvents, worklogEvents)
  const { visibleBars, visibleDueChips, visibleWorklogs, overflowCount, totalCount } = sliceVisibleEvents(bucket)

  return (
    <div
      role="gridcell"
      data-date={dateKey}
      aria-label={calendarLabels.a11y.cellAriaLabel(formatDayLabel(day), totalCount)}
      aria-current={isToday ? 'date' : undefined}
      className={`min-h-28 border-r border-border p-1 last:border-r-0 hover:bg-accent/40 md:min-h-24 ${isCurrentMonth ? '' : 'opacity-70'}`}
    >
      <span
        className={
          isToday
            ? 'flex size-6 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground'
            : `text-sm ${isCurrentMonth ? 'text-foreground' : 'text-muted-foreground'}`
        }
      >
        {day.getDate()}
      </span>
      <div className="mt-1 flex flex-col gap-1">
        {visibleBars.map(({ event, isStart, isEnd }) => (
          <IssueBarSegment key={event.key} event={event} isStart={isStart} isEnd={isEnd} onNavigate={onNavigateIssue} />
        ))}
        {visibleDueChips.map((event) => (
          <DueDateChip key={event.key} event={event} onNavigate={onNavigateIssue} />
        ))}
        {visibleWorklogs.map((w) => (
          <WorklogChip key={w.id} worklog={w} onNavigate={onNavigateIssue} />
        ))}
        {overflowCount > 0 && (
          <button
            type="button"
            onClick={() => onShowMore(day)}
            aria-label={calendarLabels.overflow.moreAriaLabel(formatDayLabel(day), overflowCount)}
            className="text-left text-xs text-muted-foreground hover:text-foreground hover:underline"
          >
            {calendarLabels.overflow.more(overflowCount)}
          </button>
        )}
      </div>
    </div>
  )
}

/** 월 뷰 6주×7일 그리드(T5 §3.3) — 42셀 고정(윤년 2월 등 최대 6주 케이스 커버) */
export function MonthGrid({
  days,
  currentMonth,
  today,
  issueEvents,
  worklogEvents,
  monthLabel,
  onNavigateIssue,
  onShowMore,
}: MonthGridProps): JSX.Element {
  const weeks = Array.from({ length: 6 }, (_, weekIdx) => days.slice(weekIdx * 7, weekIdx * 7 + 7))

  return (
    <div
      role="grid"
      aria-label={calendarLabels.a11y.gridAriaLabel(monthLabel)}
      className="overflow-hidden rounded-lg border border-border"
    >
      <div role="row" className="grid grid-cols-7 border-b border-border bg-muted">
        {WEEKDAYS.map((w) => (
          <div key={w} role="columnheader" className="p-2 text-center text-xs font-medium text-muted-foreground">
            {w}
          </div>
        ))}
      </div>
      {weeks.map((week) => (
        <div key={toDateKey(week[0] ?? days[0] ?? today)} role="row" className="grid grid-cols-7 border-b border-border last:border-b-0">
          {week.map((day) => (
            <DayCell
              key={toDateKey(day)}
              day={day}
              isCurrentMonth={day.getMonth() === currentMonth}
              isToday={isSameDay(day, today)}
              issueEvents={issueEvents}
              worklogEvents={worklogEvents}
              onNavigateIssue={onNavigateIssue}
              onShowMore={onShowMore}
            />
          ))}
        </div>
      ))}
    </div>
  )
}
