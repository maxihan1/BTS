// Cycle Time / Lead Time 분포 보고 라우트 페이지 — RouteAdapter(useParams) + props 기반 Page (라우터 비의존 단위 테스트 가능)
import type { JSX } from 'react'
import { useParams } from '@tanstack/react-router'
import { CycleTimeReport } from '@/components/cycle-time/CycleTimeReport'
import { cycleTimeLabels } from '@/i18n/cycle-time-labels'

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을 추출하여 CycleTimeReportPage에 전달한다.
 */
export function ProjectCycleTimeReportRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <CycleTimeReportPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

interface CycleTimeReportPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

/**
 * Cycle Time / Lead Time 분포 보고 페이지.
 *
 * - 페이지 헤더(h1 + 설명)와 CycleTimeReport를 렌더한다.
 * - 403/빈 데이터/로딩/에러 상태는 CycleTimeReport 내부에서 처리한다.
 * - 라우터 의존 없이 props로 projectKey를 받아 단위 테스트가 가능하다.
 *
 * @param projectKey 프로젝트 키
 */
export function CycleTimeReportPage({ projectKey }: CycleTimeReportPageProps): JSX.Element {
  return (
    <div className="p-8 space-y-6">
      <header className="space-y-1">
        <h2 className="text-xl font-semibold">{cycleTimeLabels.page.title}</h2>
        <p className="text-muted-foreground text-sm">{cycleTimeLabels.page.description}</p>
      </header>
      <CycleTimeReport projectKey={projectKey} />
    </div>
  )
}
