/* eslint-disable react-refresh/only-export-components -- toVelocitySeries 는 테스트용 named export (spec FR-RP-02 D6/D7 task-2 명시, BurndownChart 선례) */
// 프로젝트 벨로시티 막대 차트 컴포넌트 — recharts BarChart, 스프린트별 계획/완료 + 평균 참조선
import type { JSX } from 'react'
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  Legend,
  CartesianGrid,
  ReferenceLine,
} from 'recharts'
import type { VelocityResponse } from '@/api/velocity'
import { formatSeconds } from '@/lib/duration'
import { velocityLabels } from '@/i18n/velocity-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** recharts ResponsiveContainer / wrapper div 의 고정 높이 (px). */
const CHART_HEIGHT = 360

/** BarChart 내부 여백. */
const CHART_MARGIN = { top: 8, right: 24, bottom: 8, left: 8 }

/** 계획(commitment) 막대 색상. */
const COLOR_COMMITMENT = '#94a3b8'
/** 완료(completed) 막대 색상 — 계획 막대와 충분한 대비(WCAG, 색-단독 구분 회피는 Legend/Tooltip 병행). */
const COLOR_COMPLETED = '#6366f1'
/** 평균 계획 참조선 색상. */
const COLOR_AVG_COMMITMENT = '#475569'
/** 평균 완료 참조선 색상. */
const COLOR_AVG_COMPLETED = '#4338ca'

/** Y축 제목. */
const Y_AXIS_LABEL_PROPS = { value: velocityLabels.chart.yAxisTitle, angle: -90, position: 'insideLeft' } as const

/** 축 tick 공통 스타일. */
const AXIS_TICK_STYLE = { fontSize: 12 }

/** 참조선 라벨 공통 스타일. */
const REFERENCE_LABEL_FONT_SIZE = 11

// ─────────────────────────────────────────────────────────────────────────────
// 데이터 변환 — 순수 함수 (단위 테스트 대상)
// ─────────────────────────────────────────────────────────────────────────────

/** 벨로시티 차트 데이터 단건(스프린트) */
export interface VelocitySeriesPoint {
  name: string
  commitment: number
  completed: number
}

/**
 * 벨로시티 응답을 recharts 용 데이터 배열로 변환한다.
 *
 * - { name(=스프린트명), commitment(=commitmentSeconds), completed(=completedSeconds) }[]
 * - startDate/endDate 는 name 기준 매핑이라 null 이어도 무관하다(방어 확인 대상).
 *
 * @param response 프로젝트 벨로시티 응답
 * @returns recharts BarChart 용 데이터 배열
 */
export function toVelocitySeries(response: VelocityResponse): VelocitySeriesPoint[] {
  return response.sprints.map((sprint) => ({
    name: sprint.name,
    commitment: sprint.commitmentSeconds,
    completed: sprint.completedSeconds,
  }))
}

// ─────────────────────────────────────────────────────────────────────────────
// 차트 헬퍼 — tick/tooltip 포맷터
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 초(seconds) 값 포맷터. recharts 콜백은 값을 `unknown`으로 넘기므로 방어적으로 타입을 좁힌 뒤
 * `formatSeconds`(lib/duration.ts)로 "Xh Ym" 문자열로 변환한다.
 * Y축 tickFormatter 와 Tooltip formatter 양쪽에서 공유한다.
 */
function formatSecondsValue(value: unknown): string {
  if (typeof value !== 'number') return ''
  return formatSeconds(value)
}

/**
 * 평균 참조선(ReferenceLine)의 `label` prop 을 만든다.
 * 평균 계획/완료 참조선 두 곳이 값(value)/색상(fill)/위치(position)만 다르고
 * 나머지 스타일(fontSize)은 공유하므로 중복 제거를 위해 추출했다.
 */
function buildAverageLineLabel(
  value: string,
  fill: string,
  position: 'insideTopLeft' | 'insideBottomLeft',
): { value: string; position: 'insideTopLeft' | 'insideBottomLeft'; fill: string; fontSize: number } {
  return { value, position, fill, fontSize: REFERENCE_LABEL_FONT_SIZE }
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** VelocityChart props */
interface VelocityChartProps {
  /** 프로젝트 벨로시티 응답 */
  response: VelocityResponse
}

/**
 * 프로젝트 벨로시티 막대 차트.
 *
 * - 스프린트별 계획(commitment) / 완료(completed) 막대를 그룹으로 표시
 * - 평균 계획 / 평균 완료 참조선(ReferenceLine)을 점선으로 겹쳐 표시 — 평균이 핵심 지표라 label 필수 부착
 * - 색-단독 구분 금지(WCAG) → 막대 색상 대비 + Legend/Tooltip 텍스트 병행
 * - 접근성: 컨테이너 `role="img"` + aria-label 로 차트 목적·시리즈 서술
 * - Y축은 초→시간(formatSeconds) 표시
 *
 * 실 렌더(시각 검증)는 e2e/project-velocity.spec.ts 에 위임한다.
 */
export function VelocityChart({ response }: VelocityChartProps): JSX.Element {
  return (
    // w-full: recharts ResponsiveContainer 가 부모 너비를 참조하므로 필수.
    // h-[360px]: 높이는 Tailwind 임의값으로 지정 (CHART_HEIGHT 와 동기화).
    <div role="img" aria-label={velocityLabels.chart.ariaLabel} className="w-full h-[360px]">
      <ResponsiveContainer width="100%" height={CHART_HEIGHT}>
        <BarChart data={toVelocitySeries(response)} margin={CHART_MARGIN}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="name" tick={AXIS_TICK_STYLE} />
          <YAxis tickFormatter={formatSecondsValue} tick={AXIS_TICK_STYLE} label={Y_AXIS_LABEL_PROPS} />
          <Tooltip formatter={formatSecondsValue} />
          <Legend />
          <Bar dataKey="commitment" name={velocityLabels.series.commitment} fill={COLOR_COMMITMENT} />
          <Bar dataKey="completed" name={velocityLabels.series.completed} fill={COLOR_COMPLETED} />
          <ReferenceLine
            y={response.averageCommitmentSeconds}
            stroke={COLOR_AVG_COMMITMENT}
            strokeDasharray="6 4"
            label={buildAverageLineLabel(
              `${velocityLabels.series.avgCommitment} ${formatSeconds(response.averageCommitmentSeconds)}`,
              COLOR_AVG_COMMITMENT,
              'insideTopLeft',
            )}
          />
          <ReferenceLine
            y={response.averageCompletedSeconds}
            stroke={COLOR_AVG_COMPLETED}
            strokeDasharray="6 4"
            label={buildAverageLineLabel(
              `${velocityLabels.series.avgCompleted} ${formatSeconds(response.averageCompletedSeconds)}`,
              COLOR_AVG_COMPLETED,
              'insideBottomLeft',
            )}
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
