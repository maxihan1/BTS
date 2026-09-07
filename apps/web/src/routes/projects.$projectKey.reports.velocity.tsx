// 벨로시티 차트 보고 라우트 페이지 — RouteAdapter(useParams) + props 기반 Page (라우터 비의존 단위 테스트 가능)
import type { JSX } from 'react'
import { useParams } from '@tanstack/react-router'
import { VelocityReport } from '@/components/velocity/VelocityReport'
import { velocityLabels } from '@/i18n/velocity-labels'

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을 추출하여 VelocityReportPage에 전달한다.
 */
export function ProjectVelocityReportRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <VelocityReportPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

interface VelocityReportPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

/**
 * 벨로시티 차트 보고 페이지.
 *
 * - 페이지 헤더(h1 + 설명)와 VelocityReport를 렌더한다.
 * - 403/빈 데이터/로딩/에러 상태는 VelocityReport 내부에서 처리한다.
 * - 라우터 의존 없이 props로 projectKey를 받아 단위 테스트가 가능하다.
 *
 * @param projectKey 프로젝트 키
 */
export function VelocityReportPage({ projectKey }: VelocityReportPageProps): JSX.Element {
  return (
    <div className="p-8 space-y-6">
      <header className="space-y-1">
        <h2 className="text-xl font-semibold">{velocityLabels.page.title}</h2>
        <p className="text-muted-foreground text-sm">{velocityLabels.page.description}</p>
      </header>
      <VelocityReport projectKey={projectKey} />
    </div>
  )
}
