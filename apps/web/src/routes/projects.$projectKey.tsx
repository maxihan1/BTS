// 프로젝트 요약 라우트 페이지 — 프로젝트 기본 착지 화면 (Jira 패리티 J4 · 캠페인 PR ④)
import type { JSX } from 'react'
import { Link, useParams } from '@tanstack/react-router'
import { ApiError } from '@/api/client'
import { isProjectSummaryEmpty } from '@/api/project-summary'
import { useProjectSummary } from '@/hooks/use-project-summary'
import { SummaryCards } from '@/components/project/summary/SummaryCards'
import { DistributionWidget } from '@/components/project/summary/DistributionWidget'
import { distributionRowsOf } from '@/components/project/summary/summary-view-model'
import { ProjectActivityFeed } from '@/components/project/summary/ProjectActivityFeed'
import { projectSummaryLabels as labels } from '@/i18n/project-summary-labels'

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts 에 등록되는 라우트 어댑터 컴포넌트.
 * useParams 로 URL 의 $projectKey param 을 추출해 ProjectSummaryPage 에 전달한다.
 */
export function ProjectSummaryRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <ProjectSummaryPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// 집계 영역 — 카드 + 분포 4종
// ─────────────────────────────────────────────────────────────────────────────

interface SummarySectionProps {
  /** 조회할 프로젝트 키 */
  readonly projectKey: string
}

/**
 * 요약 집계 영역.
 *
 * 활동 피드와 **분리된 쿼리**를 쓴다. 요약이 실패해도 활동은 그대로 보인다.
 *
 * @param projectKey 조회할 프로젝트 키
 */
function SummarySection({ projectKey }: SummarySectionProps): JSX.Element {
  const { data, isPending, isError, error } = useProjectSummary(projectKey)

  if (isPending) {
    return (
      <p role="status" className="text-muted-foreground text-sm">
        {labels.status.loading}
      </p>
    )
  }

  if (isError) {
    const message =
      error instanceof ApiError && error.status === 403
        ? labels.status.forbidden
        : labels.status.loadFailed
    return <p className="text-muted-foreground text-sm">{message}</p>
  }

  const rows = distributionRowsOf(data)

  return (
    <div className="space-y-6">
      <SummaryCards recent={data.recent} upcoming={data.upcoming} />
      {isProjectSummaryEmpty(data) ? (
        <p className="text-muted-foreground text-sm">{labels.status.empty}</p>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          <DistributionWidget
            title={labels.distribution.statusOverview}
            note={labels.distribution.statusOverviewNote}
            rows={rows.status}
          />
          <DistributionWidget title={labels.distribution.priority} rows={rows.priority} />
          <DistributionWidget title={labels.distribution.typesOfWork} rows={rows.types} />
          <DistributionWidget title={labels.distribution.teamWorkload} rows={rows.assignees} />
        </div>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectSummaryPageProps {
  /** URL params 에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

/**
 * 프로젝트 요약 페이지 — `/projects/{key}` 의 착지 화면.
 *
 * 두 영역이 각자 자기 상태를 처리한다. 집계는 `useProjectSummary`, 활동은
 * `useProjectActivity` 로 **서로 다른 쿼리**를 쓰므로 하나가 죽어도 나머지가 남는다.
 * 프로젝트 이름은 셸의 `ProjectViewHeader` 가 소유하므로 이 화면은 조회하지 않는다(J5-11).
 *
 * @param projectKey 프로젝트 키
 */
export function ProjectSummaryPage({ projectKey }: ProjectSummaryPageProps): JSX.Element {
  return (
    <div className="space-y-6 p-8">
      {/* 🛑 프로젝트 이름 h1 은 여기 없다 (Jira 패리티 J5-11 · 2026-09-07) — 셸의
          `ProjectViewHeader` 가 **같은 문자열**을 탭바 위에서 이미 h1 으로 그린다.
          되살리면 같은 이름이 두 줄로 겹치고 문서에 h1 이 2개가 된다. */}
      <header className="space-y-1">
        <p className="text-muted-foreground text-sm">{labels.page.description}</p>
        <div className="flex gap-3 pt-2 text-sm">
          <Link
            to="/projects/$projectKey/board"
            params={{ projectKey }}
            className="text-primary hover:underline"
          >
            {labels.page.goToBoard}
          </Link>
          <Link
            to="/projects/$projectKey/backlog"
            params={{ projectKey }}
            className="text-primary hover:underline"
          >
            {labels.page.goToBacklog}
          </Link>
        </div>
      </header>

      <SummarySection projectKey={projectKey} />
      <ProjectActivityFeed projectKey={projectKey} />
    </div>
  )
}
