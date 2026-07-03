/* eslint-disable react-refresh/only-export-components -- toCfdSeries 는 테스트용 named export (VelocityChart 선례) */
// 누적 흐름도(CFD) 차트 컴포넌트 — recharts AreaChart 누적 영역, 날짜별 상태 카테고리 3띠
import type { JSX } from 'react'
import {
  ResponsiveContainer,
  AreaChart,
  Area,
  XAxis,
  YAxis,
  Tooltip,
  Legend,
  CartesianGrid,
} from 'recharts'
import type { CfdResponse } from '@/api/cfd'
import { cfdLabels } from '@/i18n/cfd-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** recharts ResponsiveContainer / wrapper div 의 고정 높이 (px). */
const CHART_HEIGHT = 360

/** 누적 영역(Area)이 공유하는 stack id — 세 시리즈를 하나의 스택으로 쌓는다. */
const STACK_ID = 'cfd'

/** Area 채움 투명도. */
const AREA_FILL_OPACITY = 0.85

/** 완료(done) 영역 색상 — 스택 맨 아래(바닥). */
const COLOR_DONE = '#6366f1'
/** 진행 중(inProgress) 영역 색상 — 스택 중간. */
const COLOR_IN_PROGRESS = '#f59e0b'
/** 할 일(todo) 영역 색상 — 스택 맨 위. */
const COLOR_TODO = '#cbd5e1'

/** Y축 제목. */
const Y_AXIS_LABEL_PROPS = { value: cfdLabels.chart.yAxisTitle, angle: -90, position: 'insideLeft' } as const

/** 축 tick 공통 스타일. */
const AXIS_TICK_STYLE = { fontSize: 12 }

// ─────────────────────────────────────────────────────────────────────────────
// 데이터 변환 — 순수 함수 (단위 테스트 대상)
// ─────────────────────────────────────────────────────────────────────────────

/** CFD 차트 데이터 단건(일자) */
export interface CfdSeriesPoint {
  date: string
  todo: number
  inProgress: number
  done: number
}

/**
 * CFD 응답을 recharts 용 누적 영역 데이터 배열로 변환한다.
 *
 * - { date, todo(=todoCount), inProgress(=inProgressCount), done(=doneCount) }[]
 *
 * @param response 프로젝트 CFD 응답
 * @returns recharts AreaChart 용 데이터 배열
 */
export function toCfdSeries(response: CfdResponse): CfdSeriesPoint[] {
  return response.points.map((point) => ({
    date: point.date,
    todo: point.todoCount,
    inProgress: point.inProgressCount,
    done: point.doneCount,
  }))
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** CfdChart props */
interface CfdChartProps {
  /** 프로젝트 CFD 응답 */
  response: CfdResponse
}

/**
 * 프로젝트 누적 흐름도(CFD) 누적 영역 차트.
 *
 * - 날짜별 할 일(todo) / 진행 중(inProgress) / 완료(done) 이슈 수를 하나의 스택으로 누적 표시
 * - 스택 순서는 아래(바닥)부터 done → inProgress → todo — recharts는 먼저 선언된 Area가 바닥에 쌓인다
 * - 색-단독 구분 금지(WCAG) → 시리즈 색상 대비 + Legend/Tooltip 텍스트(name) 병행
 * - 접근성: 컨테이너 `role="img"` + aria-label 로 차트 목적·시리즈 서술
 *
 * 실 렌더(시각 검증)는 e2e 스펙에 위임한다.
 */
export function CfdChart({ response }: CfdChartProps): JSX.Element {
  return (
    // w-full: recharts ResponsiveContainer 가 부모 너비를 참조하므로 필수.
    // h-[360px]: 높이는 Tailwind 임의값으로 지정 (CHART_HEIGHT 와 동기화).
    <div role="img" aria-label={cfdLabels.chart.ariaLabel} className="w-full h-[360px]">
      <ResponsiveContainer width="100%" height={CHART_HEIGHT}>
        <AreaChart data={toCfdSeries(response)}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="date" tick={AXIS_TICK_STYLE} />
          <YAxis allowDecimals={false} tick={AXIS_TICK_STYLE} label={Y_AXIS_LABEL_PROPS} />
          <Tooltip />
          <Legend />
          <Area
            type="monotone"
            dataKey="done"
            stackId={STACK_ID}
            name={cfdLabels.series.done}
            stroke={COLOR_DONE}
            fill={COLOR_DONE}
            fillOpacity={AREA_FILL_OPACITY}
          />
          <Area
            type="monotone"
            dataKey="inProgress"
            stackId={STACK_ID}
            name={cfdLabels.series.inProgress}
            stroke={COLOR_IN_PROGRESS}
            fill={COLOR_IN_PROGRESS}
            fillOpacity={AREA_FILL_OPACITY}
          />
          <Area
            type="monotone"
            dataKey="todo"
            stackId={STACK_ID}
            name={cfdLabels.series.todo}
            stroke={COLOR_TODO}
            fill={COLOR_TODO}
            fillOpacity={AREA_FILL_OPACITY}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}
