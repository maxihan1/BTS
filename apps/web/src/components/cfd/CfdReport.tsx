// 프로젝트 누적 흐름도(CFD) 보고 메인 컴포넌트 — useQuery 상태 분기(로딩/에러/빈/성공) (FR-RP-03 D6/D7 Task-4)
import type { JSX } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchProjectCfd, isCfdEmpty } from '@/api/cfd'
import { ApiError } from '@/api/client'
import { CfdChart } from './CfdChart'
import { cfdLabels } from '@/i18n/cfd-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상태 안내 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface CfdStatusMessageProps {
  /** 표시할 안내 문구 */
  readonly message: string
  /** 로딩 상태에서만 role="status"로 스크린리더에 진행중임을 알린다 */
  readonly role?: 'status'
}

/** 로딩/에러/빈 상태 공용 안내 화면 */
function CfdStatusMessage({ message, role }: CfdStatusMessageProps): JSX.Element {
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
    return cfdLabels.status.forbidden
  }
  return cfdLabels.status.loadFailed
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** CfdReport props */
interface CfdReportProps {
  /** 조회할 프로젝트 키 */
  readonly projectKey: string
}

/**
 * 프로젝트 누적 흐름도(CFD) 보고 메인 컴포넌트.
 *
 * 상태 분기 순서: 로딩 → 에러(403/기타) → 빈 데이터(isCfdEmpty) → 차트.
 * 403 등 에러 상태에서는 응답 데이터를 화면에 노출하지 않는다.
 *
 * @param projectKey 조회할 프로젝트 키
 */
export function CfdReport({ projectKey }: CfdReportProps): JSX.Element {
  const { data, isPending, isError, error } = useQuery({
    queryKey: ['cfd', projectKey],
    queryFn: () => fetchProjectCfd(projectKey),
  })

  if (isPending) {
    return <CfdStatusMessage message={cfdLabels.status.loading} role="status" />
  }

  if (isError) {
    return <CfdStatusMessage message={resolveErrorMessage(error)} />
  }

  if (isCfdEmpty(data)) {
    return <CfdStatusMessage message={cfdLabels.status.empty} />
  }

  return <CfdChart response={data} />
}
