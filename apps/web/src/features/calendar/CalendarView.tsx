// 개인 캘린더 월/주 뷰 컨테이너 — 툴바 + 뷰 스위칭 + 데이터 페칭 (FR-CA-01 Task 7)
import type { JSX } from 'react'
import { useMemo, useState } from 'react'
import { useIssueDetailModalStore } from '@/components/issue/issueDetailModalStore'
import { ChevronLeft, ChevronRight, Loader2 } from 'lucide-react'
import { useCalendar } from '@/api/useCalendar'
import { Button } from '@/components/ui/button'
import { calendarLabels } from '@/i18n/calendar-labels'
import { MonthGrid } from './MonthGrid'
import { WeekGrid, toDateKey, formatDayLabel } from './WeekGrid'
import { Skeleton } from '@/components/ui/skeleton'

/** 현재 활성 뷰 모드 */
type CalendarViewMode = 'month' | 'week'

// ─────────────────────────────────────────────────────────────────────────────
// 날짜 유틸(CalendarView 전용 — 그리드 날짜 배열/라벨 계산, 네이티브 Date만 사용)
// ─────────────────────────────────────────────────────────────────────────────

/** 로컬 컴포넌트 단위 덧셈(월 경계는 Date 생성자가 자동 정규화 — DST 안전) */
function addDays(date: Date, days: number): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days)
}

/** 로컬 월 단위 덧셈(±1개월 이동) */
function addMonths(date: Date, months: number): Date {
  return new Date(date.getFullYear(), date.getMonth() + months, date.getDate())
}

/** 해당 날짜가 속한 주의 월요일을 반환한다(주 시작 = 월요일, T5 §3.3 결정) */
function getMondayOfWeek(date: Date): Date {
  const dow = date.getDay() // 0=일..6=토
  const diffToMonday = dow === 0 ? -6 : 1 - dow
  return addDays(date, diffToMonday)
}

/** 월 뷰 6주×7일(42일) 그리드 날짜 — 화면에 렌더되는 42일 전체가 조회창(T5 핵심결정 3) */
function getMonthGridDates(anchor: Date): Date[] {
  const firstOfMonth = new Date(anchor.getFullYear(), anchor.getMonth(), 1)
  const gridStart = getMondayOfWeek(firstOfMonth)
  return Array.from({ length: 42 }, (_, i) => addDays(gridStart, i))
}

/** 주 뷰 7일(월~일) 그리드 날짜 */
function getWeekDates(anchor: Date): Date[] {
  const gridStart = getMondayOfWeek(anchor)
  return Array.from({ length: 7 }, (_, i) => addDays(gridStart, i))
}

/** 월 라벨("2026년 7월") */
function formatMonthLabel(date: Date): string {
  return `${date.getFullYear()}년 ${date.getMonth() + 1}월`
}

/** 주 라벨 — 같은 달이면 "2026년 7월 6일 – 12일", 달이 걸치면 양쪽 모두 연월 표기(T5 §3.2) */
function formatWeekLabel(weekStart: Date, weekEnd: Date): string {
  const sameMonth = weekStart.getFullYear() === weekEnd.getFullYear() && weekStart.getMonth() === weekEnd.getMonth()
  if (sameMonth) {
    return `${weekStart.getFullYear()}년 ${weekStart.getMonth() + 1}월 ${weekStart.getDate()}일 – ${weekEnd.getDate()}일`
  }
  return `${formatDayLabel(weekStart)} – ${formatDayLabel(weekEnd)}`
}

/** 모바일(<640px) 최초 진입 시 주 뷰를 기본값으로(T5 §8 — 이후 사용자가 명시 선택하면 유지) */
function getInitialView(): CalendarViewMode {
  if (typeof window !== 'undefined' && window.innerWidth < 640) return 'week'
  return 'month'
}

// ─────────────────────────────────────────────────────────────────────────────
// 로딩 스켈레톤(shadcn Skeleton 미설치 — Board 페이지 인라인 패턴 재사용, T5 §2)
// ─────────────────────────────────────────────────────────────────────────────

function CalendarSkeleton(): JSX.Element {
  return (
    <div data-testid="calendar-skeleton" aria-hidden="true" className="grid grid-cols-7 gap-1">
      {Array.from({ length: 42 }, (_, i) => (
        <Skeleton key={i} className="h-24" />
      ))}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 툴바(T5 §3.2)
// ─────────────────────────────────────────────────────────────────────────────

interface CalendarToolbarProps {
  view: CalendarViewMode
  label: string
  isFetching: boolean
  onPrev: () => void
  onNext: () => void
  onToday: () => void
  onChangeView: (view: CalendarViewMode) => void
}

function CalendarToolbar({ view, label, isFetching, onPrev, onNext, onToday, onChangeView }: CalendarToolbarProps): JSX.Element {
  return (
    <div className="flex flex-wrap items-center gap-4">
      <div className="flex items-center gap-2" role="group" aria-label={calendarLabels.toolbar.periodGroupAriaLabel}>
        <Button variant="outline" size="icon" aria-label={calendarLabels.toolbar.prev} onClick={onPrev} className="h-11 sm:h-8">
          <ChevronLeft />
        </Button>
        <Button variant="outline" size="sm" onClick={onToday} className="h-11 sm:h-7">
          {calendarLabels.toolbar.today}
        </Button>
        <Button variant="outline" size="icon" aria-label={calendarLabels.toolbar.next} onClick={onNext} className="h-11 sm:h-8">
          <ChevronRight />
        </Button>
      </div>

      <span className="text-lg font-semibold" aria-live="polite">
        {label}
        {isFetching && (
          <Loader2
            className="ml-1.5 inline size-3.5 animate-spin text-muted-foreground"
            aria-label={calendarLabels.toolbar.loadingAriaLabel}
          />
        )}
      </span>

      <div className="ml-auto flex items-center gap-1" role="group" aria-label={calendarLabels.toolbar.viewToggleGroupAriaLabel}>
        <Button
          variant={view === 'month' ? 'secondary' : 'ghost'}
          size="sm"
          aria-pressed={view === 'month'}
          onClick={() => onChangeView('month')}
          className="h-11 sm:h-7"
        >
          {calendarLabels.toolbar.monthView}
        </Button>
        <Button
          variant={view === 'week' ? 'secondary' : 'ghost'}
          size="sm"
          aria-pressed={view === 'week'}
          onClick={() => onChangeView('week')}
          className="h-11 sm:h-7"
        >
          {calendarLabels.toolbar.weekView}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// CalendarView 본체
// ─────────────────────────────────────────────────────────────────────────────

/** CalendarView Props */
export interface CalendarViewProps {
  /**
   * 테스트용 기준 날짜 주입 — 미지정 시 실제 오늘(`new Date()`)을 사용한다.
   * 프로덕션 라우트 어댑터(`routes/calendar.tsx`)는 이 prop 없이 렌더한다.
   */
  initialDate?: Date
}

/**
 * 개인 캘린더 페이지 컴포넌트(T5).
 *
 * 상단 툴바(이전/다음/오늘, 월↔주 토글) + 월/주 그리드 스위칭을 렌더한다. 현재 뷰의 조회창을
 * 계산해 `useCalendar(from, to)`를 호출한다 — 월 뷰의 조회창은 화면에 렌더되는 42일 그리드
 * 전체(핵심결정 3, 앞뒤 달 패딩 셀 이벤트 누락 방지). 로딩(스켈레톤)·에러(배너+재시도)·빈
 * 상태("일정 없음", 그리드는 계속 렌더)를 각각 분기한다.
 */
export function CalendarView({ initialDate }: CalendarViewProps = {}): JSX.Element {
  const openIssueDetailModal = useIssueDetailModalStore((s) => s.open)
  const [view, setView] = useState<CalendarViewMode>(getInitialView)
  const [focusDate, setFocusDate] = useState<Date>(() => initialDate ?? new Date())
  const today = useMemo(() => initialDate ?? new Date(), [initialDate])

  const days = useMemo(
    () => (view === 'month' ? getMonthGridDates(focusDate) : getWeekDates(focusDate)),
    [view, focusDate],
  )
  const firstDay = days[0] ?? focusDate
  const lastDay = days[days.length - 1] ?? focusDate
  const from = toDateKey(firstDay)
  const to = toDateKey(lastDay)

  const { data, isLoading, isFetching, error, refetch } = useCalendar(from, to)

  const label = useMemo(() => {
    if (view === 'month') return formatMonthLabel(focusDate)
    const weekDates = getWeekDates(focusDate)
    return formatWeekLabel(weekDates[0] ?? focusDate, weekDates[6] ?? focusDate)
  }, [view, focusDate])

  function handlePrev(): void {
    setFocusDate((prev) => (view === 'month' ? addMonths(prev, -1) : addDays(prev, -7)))
  }
  function handleNext(): void {
    setFocusDate((prev) => (view === 'month' ? addMonths(prev, 1) : addDays(prev, 7)))
  }
  function handleToday(): void {
    setFocusDate(initialDate ?? new Date())
  }
  function handleNavigateIssue(key: string): void {
    // 캘린더 칩 클릭은 상세 모달로 — 달력 맥락을 잃지 않는다(J1).
    openIssueDetailModal(key)
  }
  function handleShowMore(date: Date): void {
    setView('week')
    setFocusDate(date)
  }

  const issueEvents = data?.issueEvents ?? []
  const worklogEvents = data?.worklogEvents ?? []
  const isEmpty = data !== undefined && issueEvents.length === 0 && worklogEvents.length === 0

  return (
    <div className="flex flex-col gap-4 p-4 sm:p-6">
      <h1 className="text-2xl font-semibold">{calendarLabels.page.title}</h1>

      <CalendarToolbar
        view={view}
        label={label}
        isFetching={isFetching}
        onPrev={handlePrev}
        onNext={handleNext}
        onToday={handleToday}
        onChangeView={setView}
      />

      {isLoading && <CalendarSkeleton />}

      {!isLoading && error !== null && (
        <div role="alert" className="rounded-md border border-destructive/30 bg-destructive/10 p-4 text-sm text-destructive">
          <p>{calendarLabels.error.message}</p>
          <Button variant="outline" size="sm" className="mt-2" onClick={() => { void refetch() }}>
            {calendarLabels.error.retry}
          </Button>
        </div>
      )}

      {!isLoading && error === null && (
        <>
          {isEmpty && (
            <p role="status" className="text-sm text-muted-foreground">
              {calendarLabels.empty.message}
            </p>
          )}
          {view === 'month' ? (
            <MonthGrid
              days={days}
              currentMonth={focusDate.getMonth()}
              today={today}
              issueEvents={issueEvents}
              worklogEvents={worklogEvents}
              monthLabel={label}
              onNavigateIssue={handleNavigateIssue}
              onShowMore={handleShowMore}
            />
          ) : (
            <WeekGrid
              days={days}
              today={today}
              issueEvents={issueEvents}
              worklogEvents={worklogEvents}
              weekLabel={label}
              onNavigateIssue={handleNavigateIssue}
            />
          )}
        </>
      )}
    </div>
  )
}
