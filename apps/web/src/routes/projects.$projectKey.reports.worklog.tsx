// 워크로그 집계 보고 라우트 페이지 — RouteAdapter(useParams) + props 기반 Page (라우터 비의존 단위 테스트 가능)
import type { JSX } from 'react'
import { useParams } from '@tanstack/react-router'
import { WorklogAggregateReport } from '@/components/worklog/WorklogAggregateReport'
import { worklogAggregateLabels } from '@/i18n/worklog-aggregate-labels'

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을 추출하여 ProjectWorklogReportPage에 전달한다.
 */
export function ProjectWorklogReportRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <ProjectWorklogReportPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectWorklogReportPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

/**
 * 워크로그 집계 보고 페이지.
 *
 * - 페이지 헤더(h1 + 설명)와 WorklogAggregateReport를 렌더한다.
 * - 403/404/빈 버킷/로딩/에러 상태는 WorklogAggregateReport 내부에서 처리한다.
 * - 라우터 의존 없이 props로 projectKey를 받아 단위 테스트가 가능하다.
 *
 * @param projectKey 프로젝트 키
 */
export function ProjectWorklogReportPage({
  projectKey,
}: ProjectWorklogReportPageProps): JSX.Element {
  return (
    <div className="p-8 space-y-6">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold">{worklogAggregateLabels.page.title}</h1>
        <p className="text-muted-foreground text-sm">{worklogAggregateLabels.page.description}</p>
      </header>
      <WorklogAggregateReport projectKey={projectKey} />
    </div>
  )
}
