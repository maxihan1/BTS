// 누적 흐름도(CFD) 보고 라우트 페이지 — RouteAdapter(useParams) + props 기반 Page (라우터 비의존 단위 테스트 가능)
import type { JSX } from 'react'
import { useParams } from '@tanstack/react-router'
import { CfdReport } from '@/components/cfd/CfdReport'
import { cfdLabels } from '@/i18n/cfd-labels'
import { ProjectReportsNav } from '@/components/project/ProjectReportsNav'

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을 추출하여 CfdReportPage에 전달한다.
 */
export function ProjectCfdReportRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <CfdReportPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

interface CfdReportPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

/**
 * 누적 흐름도(CFD) 보고 페이지.
 *
 * - 페이지 헤더(h1 + 설명)와 CfdReport를 렌더한다.
 * - 403/빈 데이터/로딩/에러 상태는 CfdReport 내부에서 처리한다.
 * - 라우터 의존 없이 props로 projectKey를 받아 단위 테스트가 가능하다.
 *
 * @param projectKey 프로젝트 키
 */
export function CfdReportPage({ projectKey }: CfdReportPageProps): JSX.Element {
  return (
    <div className="p-8 space-y-6">
      <header className="space-y-1">
        <h2 className="text-xl font-semibold">{cfdLabels.page.title}</h2>
        <p className="text-muted-foreground text-sm">{cfdLabels.page.description}</p>
      </header>
      {/* 리포트 4종 서브내비 — 자기 자신 포함 4개 (Jira 패리티 JR-2) */}
      <ProjectReportsNav projectKey={projectKey} />
      <CfdReport projectKey={projectKey} />
    </div>
  )
}
