// 프로젝트 Cycle Time / Lead Time 분포 보고 메인 컴포넌트 — useQuery 상태 분기(로딩/에러/빈/성공) (FR-RP-04 D6/D7 Task-7)
import type { JSX } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchProjectCycleTime, isCycleTimeEmpty } from '@/api/cycle-time'
import type { CycleTimeResponse } from '@/api/cycle-time'
import { ApiError } from '@/api/client'
import { CycleTimeMetricSection } from './CycleTimeMetricSection'
import { cycleTimeLabels } from '@/i18n/cycle-time-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상태 안내 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface CycleTimeStatusMessageProps {
  /** 표시할 안내 문구 */
  readonly message: string
  /** 로딩 상태에서만 role="status"로 스크린리더에 진행중임을 알린다 */
  readonly role?: 'status'
}

/** 로딩/에러/빈 상태 공용 안내 화면 */
function CycleTimeStatusMessage({ message, role }: CycleTimeStatusMessageProps): JSX.Element {
  return (
    <div role={role} className="p-8 text-center text-sm text-muted-foreground">
      {message}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 메시지 결정 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * useQuery의 error로부터 표시할 안내 문구를 결정한다.
 * 403(권한없음)은 forbidden 문구, 그 외(5xx·네트워크 등)는 loadFailed로 폴백한다.
 *
 * @param error useQuery가 반환한 error(unknown)
 */
function resolveErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 403) {
    return cycleTimeLabels.status.forbidden
  }
  return cycleTimeLabels.status.loadFailed
}

// ─────────────────────────────────────────────────────────────────────────────
// 성공 상태 뷰
// ─────────────────────────────────────────────────────────────────────────────

interface CycleTimeSuccessViewProps {
  /** 조회 성공한 Cycle/Lead Time 응답 */
  readonly data: CycleTimeResponse
}

/**
 * 조회 창 안내 + Cycle Time 섹션(위) → Lead Time 섹션(아래) 순서로 세로 스택 렌더한다.
 *
 * @param data 조회 성공한 Cycle/Lead Time 응답
 */
function CycleTimeSuccessView({ data }: CycleTimeSuccessViewProps): JSX.Element {
  return (
    <div className="space-y-8">
      <p className="text-sm text-muted-foreground">
        {cycleTimeLabels.window.prefix} {data.from} ~ {data.to}
      </p>
      <CycleTimeMetricSection
        metric={data.cycleTime}
        title={cycleTimeLabels.metric.cycleTitle}
        description={cycleTimeLabels.metric.cycleDesc}
        emptyMessage={cycleTimeLabels.metricEmpty.cycle}
      />
      <CycleTimeMetricSection
        metric={data.leadTime}
        title={cycleTimeLabels.metric.leadTitle}
        description={cycleTimeLabels.metric.leadDesc}
        emptyMessage={cycleTimeLabels.status.empty}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** CycleTimeReport props */
interface CycleTimeReportProps {
  /** 조회할 프로젝트 키 */
  readonly projectKey: string
}

/**
 * 프로젝트 Cycle Time / Lead Time 분포 보고 메인 컴포넌트.
 *
 * 상태 분기 순서: 로딩 → 에러(403/기타) → 빈 데이터(isCycleTimeEmpty) → 성공.
 * 403 등 에러 상태에서는 응답 데이터를 화면에 노출하지 않는다.
 *
 * @param projectKey 조회할 프로젝트 키
 */
export function CycleTimeReport({ projectKey }: CycleTimeReportProps): JSX.Element {
  const { data, isPending, isError, error } = useQuery({
    queryKey: ['cycle-time', projectKey],
    queryFn: () => fetchProjectCycleTime(projectKey),
  })

  if (isPending) {
    return <CycleTimeStatusMessage message={cycleTimeLabels.status.loading} role="status" />
  }

  if (isError) {
    return <CycleTimeStatusMessage message={resolveErrorMessage(error)} />
  }

  if (isCycleTimeEmpty(data)) {
    return <CycleTimeStatusMessage message={cycleTimeLabels.status.empty} />
  }

  return <CycleTimeSuccessView data={data} />
}
