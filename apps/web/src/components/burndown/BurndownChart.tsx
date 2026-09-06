/* eslint-disable react-refresh/only-export-components -- toBurndownSeries/BurndownSeriesPoint/BurnupSeriesPoint 는 테스트용 named export (spec FR-RP-01 D6/D7 task-3 명시) */
// 스프린트 번다운/번업 라인 차트 컴포넌트 — recharts LineChart, 잔여/이상선/범위 또는 완료/범위 라인
import type { JSX } from 'react'
import {
  ResponsiveContainer,
  LineChart,
  Line,
  XAxis,
  YAxis,
  Tooltip,
  Legend,
  CartesianGrid,
} from 'recharts'
import type { BurndownResponse, BurndownUnit, BurndownView } from '@/api/burndown'
import { formatSeconds } from '@/lib/duration'
import { burndownLabels } from '@/i18n/burndown-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** recharts ResponsiveContainer / wrapper div 의 고정 높이 (px). */
const CHART_HEIGHT = 360

/** LineChart 내부 여백. */
const CHART_MARGIN = { top: 8, right: 24, bottom: 8, left: 8 }

/** 실측 라인(잔여/완료) 색상 — 주 시리즈(FR-UX-06 PR22 토큰화). */
const COLOR_ACTUAL = 'var(--chart-1)'
/** 이상선(ideal) 색상 — 보조 시리즈. 실측 라인과 구분 가능한 별도 색 + 점선(WCAG 색-단독 구분 금지). */
const COLOR_IDEAL = 'var(--chart-4)'
/** 범위(scope) 라인 색상 — 중립 계열 얇은 선. */
const COLOR_SCOPE = 'var(--chart-3)'

/**
 * Y축 제목 — **단위별**(부채 177 task-38).
 *
 * ★`Record<BurndownUnit, string>` 으로 못 박는 것이 방어선이다. 백엔드 `BurndownUnit` 에 값이
 * 늘면 여기가 **컴파일 에러**가 되어 「축이 새 단위인데 제목은 옛것」이 조용히 생기지 않는다.
 */
const Y_AXIS_TITLE: Record<BurndownUnit, string> = burndownLabels.chart.yAxisTitle

/** Y축 label prop — 단위에 맞는 제목을 싣는다. */
function yAxisLabelProps(unit: BurndownUnit): { value: string; angle: number; position: 'insideLeft' } {
  return { value: Y_AXIS_TITLE[unit], angle: -90, position: 'insideLeft' }
}

/** 축 tick 공통 스타일. */
const AXIS_TICK_STYLE = { fontSize: 12 }

// ─────────────────────────────────────────────────────────────────────────────
// 데이터 변환 — 순수 함수 (단위 테스트 대상)
// ─────────────────────────────────────────────────────────────────────────────

/** 번다운 뷰 차트 데이터 단건 */
export interface BurndownSeriesPoint {
  date: string
  remaining: number | null
  ideal: number
  scope: number
}

/** 번업 뷰 차트 데이터 단건 */
export interface BurnupSeriesPoint {
  date: string
  completed: number | null
  scope: number
}

/**
 * 번다운 응답을 뷰(view)에 맞는 recharts 용 데이터 배열로 변환한다.
 *
 * - view='burndown' → { date, remaining(=remainingSeconds, null 보존), ideal, scope }
 * - view='burnup' → { date, completed(=completedSeconds, null 보존), scope }
 * - remaining/completed 는 백엔드 nullable(미래일) 필드라 null 을 그대로 보존한다
 *   (undefined 도 null 로 정규화 — Zod `.nullish()` 대응).
 * - 오버로드로 view 리터럴에 맞는 구체 배열 타입을 반환한다 (recharts LineChart 의
 *   `data` 제네릭 추론이 union 타입을 거부하므로, 호출부에서 뷰별로 분기해 구체 타입을 얻는다).
 *
 * @param response 스프린트 번다운/번업 응답
 * @param view 차트 뷰 종류
 * @returns 뷰에 맞는 recharts 차트 데이터 배열
 */
export function toBurndownSeries(response: BurndownResponse, view: 'burndown'): BurndownSeriesPoint[]
export function toBurndownSeries(response: BurndownResponse, view: 'burnup'): BurnupSeriesPoint[]
export function toBurndownSeries(
  response: BurndownResponse,
  view: BurndownView,
): BurndownSeriesPoint[] | BurnupSeriesPoint[] {
  if (view === 'burndown') {
    return response.points.map((p) => ({
      date: p.date,
      remaining: p.remainingSeconds ?? null,
      ideal: p.idealSeconds,
      scope: p.scopeSeconds,
    }))
  }
  return response.points.map((p) => ({
    date: p.date,
    completed: p.completedSeconds ?? null,
    scope: p.scopeSeconds,
  }))
}

// ─────────────────────────────────────────────────────────────────────────────
// 차트 헬퍼 — tick/tooltip 포맷터
// ─────────────────────────────────────────────────────────────────────────────

/** X축 날짜 tick 포맷터. "YYYY-MM-DD" → "MM/DD" (틱 밀도 축약). */
function formatDateTick(value: unknown): string {
  if (typeof value !== 'string') return ''
  const parts = value.split('-')
  const month = parts[1]
  const day = parts[2]
  if (month === undefined || day === undefined) return value
  return `${month}/${day}`
}

/**
 * 초(seconds) 값 포맷터. recharts 콜백은 값을 `unknown`으로 넘기므로 방어적으로 타입을 좁힌 뒤
 * `formatSeconds`(Task-1 `lib/duration.ts`)로 "Xh Ym" 문자열로 변환한다.
 * Y축 tickFormatter 와 Tooltip formatter 양쪽에서 공유한다.
 */
function formatSecondsValue(value: unknown): string {
  if (typeof value !== 'number') return ''
  return formatSeconds(value)
}

/**
 * 이슈 **개수** 포맷터 — 정수만 낸다.
 *
 * ★`2.5개` 는 존재하지 않는 값이다. recharts 는 도메인이 좁으면 소수 tick 을 만들므로
 * 포맷에서 접고, 생성 자체는 `allowDecimals={false}` 가 막는다(둘 다 필요하다 — 포맷만 접으면
 * 서로 다른 두 tick 이 같은 글자로 겹쳐 보인다).
 */
function formatIssueCountValue(value: unknown): string {
  if (typeof value !== 'number') return ''
  return String(Math.round(value))
}

/**
 * 단위별 값 포맷터 (부채 177 task-38).
 *
 * ★★**축 tick 과 툴팁이 이 표 하나를 함께 읽는다.** 두 벌로 적으면 한쪽만 고친 날 축은
 * 「12」인데 툴팁은 「0m」이라고 말한다 — 한 화면이 두 단위를 주장하는 상태다.
 * ★`Record<BurndownUnit, …>` 이라 백엔드 열거형이 늘면 컴파일이 깨진다.
 */
const VALUE_FORMATTER: Record<BurndownUnit, (value: unknown) => string> = {
  SECONDS: formatSecondsValue,
  ISSUE_COUNT: formatIssueCountValue,
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** BurndownChart props */
interface BurndownChartProps {
  /** 스프린트 번다운/번업 응답 */
  response: BurndownResponse
  /** 차트 뷰 종류 — 번다운(잔여) / 번업(완료) */
  view: BurndownView
}

/**
 * 두 뷰가 공유하는 축/툴팁/범례/범위(scope) 라인을 반환한다.
 * (Line/Axis 컴포넌트는 recharts 제네릭 기본값이 `any`라 뷰별 데이터 타입과 무관하게 재사용 가능하다.)
 */
function renderCommonElements(unit: BurndownUnit): JSX.Element[] {
  // 축과 툴팁이 **같은 함수 참조**를 쓴다 — 두 번 꺼내 쓰지 않는다(위 [VALUE_FORMATTER] ★★).
  const formatValue = VALUE_FORMATTER[unit]
  return [
    <CartesianGrid key="grid" strokeDasharray="3 3" />,
    <XAxis
      key="xaxis"
      dataKey="date"
      tickFormatter={formatDateTick}
      interval="preserveStartEnd"
      tick={AXIS_TICK_STYLE}
    />,
    <YAxis
      key="yaxis"
      tickFormatter={formatValue}
      allowDecimals={unit !== 'ISSUE_COUNT'}
      tick={AXIS_TICK_STYLE}
      label={yAxisLabelProps(unit)}
    />,
    <Tooltip key="tooltip" labelFormatter={formatDateTick} formatter={formatValue} />,
    <Legend key="legend" />,
    <Line
      key="scope"
      type="monotone"
      dataKey="scope"
      name={burndownLabels.series.scope}
      stroke={COLOR_SCOPE}
      strokeWidth={1}
      dot={false}
    />,
  ]
}

/**
 * 뷰별로 렌더할 실측/이상선 라인을 반환한다.
 * - burndown: 이상선(ideal, 점선) + 잔여(remaining) 라인
 * - burnup: 완료(completed) 라인만
 */
function renderPrimaryLines(view: BurndownView): JSX.Element[] {
  const lines: JSX.Element[] = []
  if (view === 'burndown') {
    lines.push(
      <Line
        key="ideal"
        type="monotone"
        dataKey="ideal"
        name={burndownLabels.series.ideal}
        stroke={COLOR_IDEAL}
        strokeDasharray="6 4"
        strokeWidth={2}
        dot={false}
      />,
    )
  }
  lines.push(
    <Line
      key="primary"
      type="monotone"
      dataKey={view === 'burndown' ? 'remaining' : 'completed'}
      name={view === 'burndown' ? burndownLabels.series.remaining : burndownLabels.series.completed}
      stroke={COLOR_ACTUAL}
      strokeWidth={2}
      dot={false}
      connectNulls={false}
    />,
  )
  return lines
}

/**
 * 스프린트 번다운/번업 라인 차트.
 *
 * - view=burndown → 범위(scope) + 이상선(ideal, 점선) + 잔여(remaining, 미래 null 끊김) 3개 라인
 * - view=burnup → 범위(scope) + 완료(completed, 미래 null 끊김) 2개 라인
 * - 다중 라인 가독성을 위해 `<Legend>` 표시, 색-단독 구분 금지(WCAG) → 색 + 선 스타일 이중 구분
 * - 접근성: 컨테이너 `role="img"` + aria-label 로 차트 목적·시리즈 서술
 * - Y축 포맷과 제목은 **응답 `unit` 이 가른다**(부채 177 task-38) — `SECONDS` 면 초→시간
 *   (`formatSeconds`)이고 `ISSUE_COUNT` 면 정수 개수다. 보드 「추정」 탭이 `NONE` 이면 백엔드가
 *   개수로 계산해 보내므로, 안 가르면 「12개」가 「12초」로 그려진다.
 * - X축은 날짜 틱 밀도 축약(MM/DD) + `preserveStartEnd`
 * - view 별로 `toBurndownSeries` 오버로드를 리터럴 인자로 호출해 LineChart 의 `data` 제네릭이
 *   구체 타입(BurndownSeriesPoint[] 또는 BurnupSeriesPoint[])으로 추론되도록 분기 렌더한다
 *   (union 배열을 그대로 넘기면 recharts 제네릭 추론이 실패한다).
 *
 * 실 렌더(시각 검증)는 e2e/sprint-burndown.spec.ts 에 위임한다.
 */
export function BurndownChart({ response, view }: BurndownChartProps): JSX.Element {
  return (
    // w-full: recharts ResponsiveContainer 가 부모 너비를 참조하므로 필수.
    // h-[360px]: 높이는 Tailwind 임의값으로 지정 (CHART_HEIGHT 와 동기화).
    <div role="img" aria-label={burndownLabels.chart.ariaLabel} className="w-full h-[360px]">
      <ResponsiveContainer width="100%" height={CHART_HEIGHT}>
        {view === 'burndown' ? (
          <LineChart data={toBurndownSeries(response, 'burndown')} margin={CHART_MARGIN}>
            {renderCommonElements(response.unit)}
            {renderPrimaryLines('burndown')}
          </LineChart>
        ) : (
          <LineChart data={toBurndownSeries(response, 'burnup')} margin={CHART_MARGIN}>
            {renderCommonElements(response.unit)}
            {renderPrimaryLines('burnup')}
          </LineChart>
        )}
      </ResponsiveContainer>
    </div>
  )
}
