// 프로젝트 벨로시티 보고 메인 컴포넌트 — useQuery 상태 분기(로딩/에러/빈/성공) (FR-RP-02 D6/D7 Task-3)
import type { JSX } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchProjectVelocity } from '@/api/velocity'
import { ApiError } from '@/api/client'
import { VelocityChart } from './VelocityChart'
import { velocityLabels } from '@/i18n/velocity-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상태 안내 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface VelocityStatusMessageProps {
  /** 표시할 안내 문구 */
  message: string
  /** 로딩 상태에서만 role="status"로 스크린리더에 진행중임을 알린다 */
  role?: 'status'
}

/** 로딩/에러/빈 상태 공용 안내 화면 */
function VelocityStatusMessage({ message, role }: VelocityStatusMessageProps): JSX.Element {
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
    return velocityLabels.status.forbidden
  }
  return velocityLabels.status.loadFailed
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** VelocityReport props */
interface VelocityReportProps {
  /** 조회할 프로젝트 키 */
  projectKey: string
}

/**
 * 프로젝트 벨로시티 보고 메인 컴포넌트.
 *
 * 상태 분기 순서: 로딩 → 에러(403/기타) → 빈 데이터(sprints=0) → 차트.
 * 403 등 에러 상태에서는 응답 데이터를 화면에 노출하지 않는다.
 *
 * @param projectKey 조회할 프로젝트 키
 */
export function VelocityReport({ projectKey }: VelocityReportProps): JSX.Element {
  const { data, isPending, isError, error } = useQuery({
    queryKey: ['velocity', projectKey],
    queryFn: () => fetchProjectVelocity(projectKey),
  })

  if (isPending) {
    return <VelocityStatusMessage message={velocityLabels.status.loading} role="status" />
  }

  if (isError) {
    return <VelocityStatusMessage message={resolveErrorMessage(error)} />
  }

  if (data.sprints.length === 0) {
    return <VelocityStatusMessage message={velocityLabels.status.empty} />
  }

  return <VelocityChart response={data} />
}
