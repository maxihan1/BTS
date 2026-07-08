// 캘린더 주 뷰(7일) 그리드 + 이벤트 분류/프레젠테이션 공용 커널 (FR-CA-01 Task 7)
import type { JSX, KeyboardEvent } from 'react'
import { Circle, CircleDot, CheckCircle2, Clock } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import type { CalendarIssueEvent, CalendarWorklogEvent } from '@/api/calendar'
import { formatSeconds } from '@/lib/duration'
import { calendarLabels } from '@/i18n/calendar-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 날짜 유틸 (CalendarView/MonthGrid 공용)
//
// MonthGrid.tsx가 이 파일을 import하므로(순환 참조 회피), 그리드 날짜 배열 생성(월 단위)은
// CalendarView.tsx가 소유하지만 개별 셀 키 변환(toDateKey)·오늘 판정(isSameDay)·표시용
// 한국어 날짜 라벨(formatDayLabel)은 MonthGrid도 필요하므로 이 커널 파일에 둔다.
// ─────────────────────────────────────────────────────────────────────────────

/** 월요일 시작 요일 헤더(T5 §3.3 결정 — 업무 주 단위) */
export const WEEKDAYS = ['월', '화', '수', '목', '금', '토', '일'] as const

/**
 * 로컬 날짜를 `"YYYY-MM-DD"` 문자열로 변환한다.
 *
 * `toISOString()`(UTC 기준)이 아닌 로컬 `getFullYear`/`getMonth`/`getDate`만 사용한다 —
 * 그리드 Date 객체는 로컬 컴포넌트 산술로만 생성되므로(§CalendarView getMonthGridDates 등),
 * 이 변환도 로컬 값을 그대로 사용해야 자체 정합이 유지된다. 백엔드가 내려주는 날짜 문자열
 * (startDate/dueDate/date)은 이미 사용자 로컬 날짜이므로 별도 재변환 없이 이 키와 문자열
 * 비교만으로 매칭한다(설계 스펙 §3.5 — 이중 tz 변환 금지).
 */
export function toDateKey(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/** 두 Date가 같은 로컬 날짜인지 판정한다(오늘 표시용) */
export function isSameDay(a: Date, b: Date): boolean {
  return toDateKey(a) === toDateKey(b)
}

/** 사람이 읽는 한국어 날짜 라벨("2026년 7월 10일") — gridcell aria-label용(설계 스펙 §9) */
export function formatDayLabel(date: Date): string {
  return `${date.getFullYear()}년 ${date.getMonth() + 1}월 ${date.getDate()}일`
}

// ─────────────────────────────────────────────────────────────────────────────
// 상태 카테고리 매핑
// ─────────────────────────────────────────────────────────────────────────────

/** 워크플로우 상태 카테고리 — backend project-workflow YAML의 category 3종과 대응 */
export type StateCategory = 'TODO' | 'IN_PROGRESS' | 'DONE'

/** 4종 기본 워크플로우(software-default/bug-tracking/kanban-basic/simple) 중 DONE 카테고리 상태 키 */
const DONE_KEYS = new Set(['done', 'closed', 'resolved'])
/** 4종 기본 워크플로우 중 IN_PROGRESS 카테고리 상태 키 */
const IN_PROGRESS_KEYS = new Set(['in_progress', 'in_review', 'doing'])

/**
 * 워크플로우 상태 키(`currentStateKey`)를 카테고리로 근사 변환한다.
 *
 * **주의(백엔드 계약 한계, Maxi 확인 권장)** — `CalendarIssueEvent`(identity-access
 * `CalendarResponse.kt`)는 워크플로우별 원시 상태 키만 내려주고 `category` 필드가 없다.
 * 상태 키는 프로젝트가 선택한 워크플로우 스킴마다 다르다(project-workflow 모듈의 4종 기본
 * 워크플로우 YAML 확인 — 예: bug-tracking은 `reported`/`triaged`/`resolved`처럼 카테고리와
 * 무관한 이름을 쓴다). 이 함수는 4종 기본 워크플로우의 상태 키만 정확히 매핑하는 근사치이며,
 * 커스텀 워크플로우가 추가한 미지의 상태 키는 안전한 기본값인 TODO로 폴백한다. 백엔드가
 * `category` 필드를 직접 내려주는 후속 개선이 이상적이다.
 */
export function deriveStateCategory(currentStateKey: string): StateCategory {
  if (DONE_KEYS.has(currentStateKey)) return 'DONE'
  if (IN_PROGRESS_KEYS.has(currentStateKey)) return 'IN_PROGRESS'
  return 'TODO'
}

interface CategoryStyle {
  /** solid fill 배경 클래스 (T5 §4.1 — 옅은 배지 대비 미달로 배제, 중간톤 solid fill 채택) */
  bgClass: string
  /** 색맹 대응 아이콘(T5 §4.1 — 색만으로 구분 금지) */
  Icon: LucideIcon
  /** 사람이 읽는 한국어 라벨(aria-label용 — currentStateKey 원문 노출 금지) */
  label: string
}

/** 카테고리별 색/아이콘/라벨 맵(T5 §4.1, §12 DESIGN.md 패치안) */
export const STATE_CATEGORY_STYLE: Record<StateCategory, CategoryStyle> = {
  TODO: { bgClass: 'bg-slate-600', Icon: Circle, label: calendarLabels.category.TODO },
  IN_PROGRESS: { bgClass: 'bg-blue-800', Icon: CircleDot, label: calendarLabels.category.IN_PROGRESS },
  DONE: { bgClass: 'bg-emerald-800', Icon: CheckCircle2, label: calendarLabels.category.DONE },
}

// ─────────────────────────────────────────────────────────────────────────────
// 이벤트 분류/셀 배치 (T5 §4.2/§4.3 — 셀별 세그먼트 렌더 모델, 픽셀 좌표 계산 없음)
// ─────────────────────────────────────────────────────────────────────────────

/** 하루치 셀에 배치할 이벤트 묶음 */
export interface DayEventBucket {
  /** 이 날짜를 지나는 기간 막대 세그먼트 — isStart/isEnd로 좌우 라운드 조건부 적용(T5 §4.2) */
  bars: Array<{ event: CalendarIssueEvent; isStart: boolean; isEnd: boolean }>
  /** 마감일만 있는 이슈의 마감일 칩(T5 §4.3) */
  dueChips: CalendarIssueEvent[]
  /** 이 날짜의 Worklog 칩(T5 §4.4) */
  worklogs: CalendarWorklogEvent[]
}

/**
 * 이슈 이벤트의 기간(span)을 계산한다 — `startDate`가 있으면 기간 막대 대상(마감일 없으면
 * 하루짜리로 축약, T5 §4.2 "openEnd"), 없고 `dueDate`만 있으면 막대가 아닌 마감일 칩(§4.3).
 */
function classifySpan(event: CalendarIssueEvent): { spanStart: string; spanEnd: string } | null {
  if (event.startDate !== null) {
    return { spanStart: event.startDate, spanEnd: event.dueDate ?? event.startDate }
  }
  return null
}

/**
 * 주어진 날짜 키(`dateKey`)에 배치할 이벤트를 이슈/Worklog 목록에서 골라 분류한다.
 *
 * 모든 비교는 ISO `"YYYY-MM-DD"` 문자열의 사전식 비교(chronological 순서와 동일)로만
 * 수행한다 — API 날짜 문자열을 Date로 재파싱하지 않으므로 타임존 재변환 버그가 없다.
 */
export function buildDayEvents(
  dateKey: string,
  issueEvents: CalendarIssueEvent[],
  worklogEvents: CalendarWorklogEvent[],
): DayEventBucket {
  const bars: DayEventBucket['bars'] = []
  const dueChips: CalendarIssueEvent[] = []

  for (const event of issueEvents) {
    const span = classifySpan(event)
    if (span !== null) {
      if (span.spanStart <= dateKey && dateKey <= span.spanEnd) {
        bars.push({ event, isStart: dateKey === span.spanStart, isEnd: dateKey === span.spanEnd })
      }
    } else if (event.dueDate === dateKey) {
      dueChips.push(event)
    }
  }

  const worklogs = worklogEvents.filter((w) => w.date === dateKey)
  return { bars, dueChips, worklogs }
}

// ─────────────────────────────────────────────────────────────────────────────
// 이벤트 프레젠테이션 컴포넌트 3종 (T5 §4.2~§4.4, MonthGrid/WeekGrid 공용)
// ─────────────────────────────────────────────────────────────────────────────

/** Enter/Space 키 입력 시 콜백을 호출하는 공용 키보드 핸들러(TimelineRow 패턴 재사용) */
function handleActivationKeyDown(e: KeyboardEvent<HTMLElement>, onActivate: () => void): void {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    onActivate()
  }
}

/** 이슈 기간 막대 세그먼트 Props */
export interface IssueBarSegmentProps {
  event: CalendarIssueEvent
  /** 이 셀이 span의 시작일인지 여부 — 좌측 라운드 조건(T5 §4.2) */
  isStart: boolean
  /** 이 셀이 span의 종료일인지 여부 — 우측 라운드 조건(T5 §4.2) */
  isEnd: boolean
  onNavigate: (key: string) => void
}

/** 이슈 기간 막대 세그먼트 — 각 날짜 셀이 독립적으로 렌더하는 조각(T5 결정 2, 좌표 계산 없음) */
export function IssueBarSegment({ event, isStart, isEnd, onNavigate }: IssueBarSegmentProps): JSX.Element {
  const category = deriveStateCategory(event.currentStateKey)
  const style = STATE_CATEGORY_STYLE[category]
  const Icon = style.Icon
  const rounded = `${isStart ? 'rounded-l-sm' : ''} ${isEnd ? 'rounded-r-sm' : ''}`.trim()
  const mid = !isStart && !isEnd ? '-mx-1' : ''
  const ariaLabel = calendarLabels.a11y.eventAriaLabel(event.key, event.summary, style.label, event.startDate, event.dueDate)
  const activate = () => onNavigate(event.key)

  return (
    <div
      role="button"
      tabIndex={0}
      title={`${event.key} ${event.summary}`}
      aria-label={ariaLabel}
      onClick={activate}
      onKeyDown={(e) => handleActivationKeyDown(e, activate)}
      className={`flex h-5 items-center gap-1 overflow-hidden px-1 text-xs font-medium text-white ${style.bgClass} ${rounded} ${mid} hover:brightness-95 active:brightness-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-1 focus-visible:ring-ring`}
    >
      <Icon className="size-3 shrink-0" aria-hidden="true" />
      <span className={`truncate ${category === 'DONE' ? 'line-through' : ''}`}>
        {event.key} {event.summary}
      </span>
    </div>
  )
}

/** 마감일 전용 칩 Props */
export interface DueDateChipProps {
  event: CalendarIssueEvent
  onNavigate: (key: string) => void
}

/** 마감일 전용 알약형 칩(T5 §4.3) — 기간 막대의 "셀 전체 폭 사각형"과 형태로 구분 */
export function DueDateChip({ event, onNavigate }: DueDateChipProps): JSX.Element {
  const category = deriveStateCategory(event.currentStateKey)
  const style = STATE_CATEGORY_STYLE[category]
  const Icon = style.Icon
  const ariaLabel = calendarLabels.a11y.eventAriaLabel(event.key, event.summary, style.label, event.startDate, event.dueDate)
  const activate = () => onNavigate(event.key)

  return (
    <div
      role="button"
      tabIndex={0}
      title={`${event.key} ${event.summary}`}
      aria-label={ariaLabel}
      onClick={activate}
      onKeyDown={(e) => handleActivationKeyDown(e, activate)}
      className={`flex w-fit items-center gap-1 overflow-hidden rounded-full px-1.5 text-xs font-medium text-white ${style.bgClass} hover:brightness-95 active:brightness-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-1 focus-visible:ring-ring`}
    >
      <Icon className="size-3 shrink-0" aria-hidden="true" />
      <span className="truncate">{event.key}</span>
    </div>
  )
}

/** Worklog 칩 Props */
export interface WorklogChipProps {
  worklog: CalendarWorklogEvent
  onNavigate: (key: string) => void
}

/**
 * Worklog 칩(T5 §4.4) — `issueSummary === null`(비가시 이슈 마스킹)이면 disabled 표현으로
 * 렌더한다: role/tabIndex 없는 일반 span, 클릭 무동작, opacity-70(§4.4 — 마스킹된 이슈는
 * 조회자가 볼 권한이 없으므로 이동시키면 403으로 이어지는 막다른 경로가 된다).
 */
export function WorklogChip({ worklog, onNavigate }: WorklogChipProps): JSX.Element {
  const label = `${formatSeconds(worklog.timeSpentSeconds)} ${worklog.issueKey}`

  if (worklog.issueSummary === null) {
    return (
      <span
        aria-label={calendarLabels.worklog.maskedAriaLabel(worklog.issueKey)}
        className="flex w-fit cursor-default items-center gap-1 overflow-hidden rounded-full bg-violet-800 px-1.5 text-xs font-medium text-white opacity-70"
      >
        <Clock className="size-3 shrink-0" aria-hidden="true" />
        <span className="truncate">{label}</span>
      </span>
    )
  }

  const activate = () => onNavigate(worklog.issueKey)

  return (
    <div
      role="button"
      tabIndex={0}
      title={label}
      onClick={activate}
      onKeyDown={(e) => handleActivationKeyDown(e, activate)}
      className="flex w-fit items-center gap-1 overflow-hidden rounded-full bg-violet-800 px-1.5 text-xs font-medium text-white hover:brightness-95 active:brightness-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-1 focus-visible:ring-ring"
    >
      <Clock className="size-3 shrink-0" aria-hidden="true" />
      <span className="truncate">{label}</span>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// WeekGrid 본체 (T5 §3.4)
// ─────────────────────────────────────────────────────────────────────────────

/** WeekGrid Props */
export interface WeekGridProps {
  /** 그리드에 표시할 7일(월~일) */
  days: Date[]
  /** 오늘 날짜(오늘 표시/aria-current용) */
  today: Date
  issueEvents: CalendarIssueEvent[]
  worklogEvents: CalendarWorklogEvent[]
  /** 그리드 aria-label에 쓸 주 라벨 */
  weekLabel: string
  onNavigateIssue: (key: string) => void
}

interface WeekDayColumnProps {
  day: Date
  isToday: boolean
  issueEvents: CalendarIssueEvent[]
  worklogEvents: CalendarWorklogEvent[]
  onNavigateIssue: (key: string) => void
}

/** 주 뷰 단일 날짜 컬럼 — 오버플로 버튼 대신 컬럼 내부 스크롤(T5 §4.5, 세로 공간이 넉넉하므로) */
function WeekDayColumn({ day, isToday, issueEvents, worklogEvents, onNavigateIssue }: WeekDayColumnProps): JSX.Element {
  const dateKey = toDateKey(day)
  const { bars, dueChips, worklogs } = buildDayEvents(dateKey, issueEvents, worklogEvents)
  const totalCount = bars.length + dueChips.length + worklogs.length
  // day.getDay(): 0=일..6=토 → WEEKDAYS(월..일) 인덱스로 변환
  const weekdayIndex = day.getDay() === 0 ? 6 : day.getDay() - 1
  const weekdayShort = WEEKDAYS[weekdayIndex]

  return (
    <div
      role="gridcell"
      data-date={dateKey}
      aria-label={calendarLabels.a11y.cellAriaLabel(formatDayLabel(day), totalCount)}
      aria-current={isToday ? 'date' : undefined}
      className="flex flex-col rounded-lg border border-border p-2"
    >
      <div className="flex items-baseline gap-1">
        <span className="text-xs text-muted-foreground">{weekdayShort}</span>
        <span
          className={
            isToday
              ? 'flex size-6 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground'
              : 'text-sm font-medium'
          }
        >
          {day.getDate()}
        </span>
      </div>
      <div className="mt-2 flex max-h-96 flex-col gap-1 overflow-y-auto">
        {bars.map(({ event, isStart, isEnd }) => (
          <IssueBarSegment key={event.key} event={event} isStart={isStart} isEnd={isEnd} onNavigate={onNavigateIssue} />
        ))}
        {dueChips.map((event) => (
          <DueDateChip key={event.key} event={event} onNavigate={onNavigateIssue} />
        ))}
        {worklogs.map((w) => (
          <WorklogChip key={w.id} worklog={w} onNavigate={onNavigateIssue} />
        ))}
      </div>
    </div>
  )
}

/** 주 뷰 7일 그리드(T5 §3.4) — 세로 시간축 없음(이벤트가 날짜 단위 정보만 가짐) */
export function WeekGrid({ days, today, issueEvents, worklogEvents, weekLabel, onNavigateIssue }: WeekGridProps): JSX.Element {
  return (
    <div role="grid" aria-label={calendarLabels.a11y.gridAriaLabel(weekLabel)} className="grid grid-cols-7 gap-2">
      {days.map((day) => (
        <WeekDayColumn
          key={toDateKey(day)}
          day={day}
          isToday={isSameDay(day, today)}
          issueEvents={issueEvents}
          worklogEvents={worklogEvents}
          onNavigateIssue={onNavigateIssue}
        />
      ))}
    </div>
  )
}
