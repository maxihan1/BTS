// 백로그·스프린트 라우트 — BacklogRouteAdapter + BacklogPage (FR-BL-01/02 D6/D7 Task 9)
import type { JSX } from 'react'
import { useParams, Link } from '@tanstack/react-router'
import { BacklogBoard } from '@/components/backlog/BacklogBoard'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { backlogLabels } from '@/i18n/backlog-labels'

// ─────────────────────────────────────────────────────────────────────────────
// BacklogRouteAdapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을 추출하여 BacklogPage에 전달한다.
 */
export function BacklogRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <BacklogPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// BacklogPage Props
// ─────────────────────────────────────────────────────────────────────────────

/** BacklogPage Props */
export interface BacklogPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// BacklogPage
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그·스프린트 보드 페이지.
 *
 * - useProjectPermissions로 현재 사용자 권한을 조회한다.
 * - CREATE 권한 → canManageSprint(스프린트 생성·시작·완료 버튼).
 * - UPDATE 권한 → canReorderIssue(이슈 드래그 재정렬·할당·해제).
 *   두 권한을 분리해 MEMBER(CREATE=true, UPDATE=true)와 비멤버(둘 다 false)를 정확히 게이팅한다.
 *   undefined이면 false-safe — 로딩 중에는 모든 관리 기능 비활성.
 *
 * @param projectKey 프로젝트 식별 키
 */
export function BacklogPage({ projectKey }: BacklogPageProps): JSX.Element {
  const { data: projectPermissions } = useProjectPermissions(projectKey)

  // CREATE 권한 — 스프린트 생성·시작·완료 게이팅
  const canManageSprint: boolean = projectPermissions?.permissions.CREATE === true
  // UPDATE 권한 — 이슈 재정렬·할당·해제 드래그 게이팅
  const canReorderIssue: boolean = projectPermissions?.permissions.UPDATE === true

  return (
    <div className="p-6 space-y-4">
      {/* 헤더 행 — 제목 + 보드 링크 */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">{backlogLabels.page.title}</h1>
        <nav aria-label="프로젝트 뷰 전환" className="flex items-center gap-3">
          <Link
            to="/projects/$projectKey/board"
            params={{ projectKey }}
            className="text-sm text-muted-foreground hover:text-foreground"
          >
            {backlogLabels.page.boardLink}
          </Link>
          <Link
            to="/projects/$projectKey/timeline"
            params={{ projectKey }}
            className="text-sm text-muted-foreground hover:text-foreground"
          >
            {backlogLabels.page.timelineLink}
          </Link>
          <Link
            to="/projects/$projectKey/reports/velocity"
            params={{ projectKey }}
            className="text-sm text-muted-foreground hover:text-foreground"
          >
            {backlogLabels.page.velocityLink}
          </Link>
          <Link
            to="/projects/$projectKey/reports/cfd"
            params={{ projectKey }}
            className="text-sm text-muted-foreground hover:text-foreground"
          >
            {backlogLabels.page.cfdLink}
          </Link>
          <Link
            to="/projects/$projectKey/reports/cycle-time"
            params={{ projectKey }}
            className="text-sm text-muted-foreground hover:text-foreground"
          >
            {backlogLabels.page.cycleTimeLink}
          </Link>
        </nav>
      </div>

      <BacklogBoard
        projectKey={projectKey}
        canManageSprint={canManageSprint}
        canReorderIssue={canReorderIssue}
      />
    </div>
  )
}
