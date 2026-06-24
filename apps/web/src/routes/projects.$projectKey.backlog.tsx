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
 * - CREATE 권한이 있으면 BacklogBoard에 canManage=true를 전달한다.
 *
 * 권한 게이팅 주의.
 *   BacklogBoard의 canManage는 이슈 재정렬(UPDATE) + 스프린트 관리(CREATE/UPDATE/DELETE) 모두를
 *   통합 게이팅한다. 현재 MyProjectPermission 요약 API에 노출된 권한 코드 중 UPDATE/MANAGE_SPRINTS는
 *   없고 CREATE만 있으므로, CREATE 권한을 보수적 게이팅 기준으로 사용한다.
 *   백엔드에서 UPDATE/MANAGE_SPRINTS 권한 코드가 노출되면 조건을 확장한다.
 *
 * @param projectKey 프로젝트 식별 키
 */
export function BacklogPage({ projectKey }: BacklogPageProps): JSX.Element {
  const { data: projectPermissions } = useProjectPermissions(projectKey)

  // CREATE 권한 여부 — undefined이면 false-safe (로딩 중에는 관리 기능 비활성)
  const canManage: boolean = projectPermissions?.permissions.CREATE === true

  return (
    <div className="p-6 space-y-4">
      {/* 헤더 행 — 제목 + 보드 링크 */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">{backlogLabels.page.title}</h1>
        <nav aria-label="프로젝트 뷰 전환">
          <Link
            to="/projects/$projectKey/board"
            params={{ projectKey }}
            className="text-sm text-muted-foreground hover:text-foreground"
          >
            {backlogLabels.page.boardLink}
          </Link>
        </nav>
      </div>

      <BacklogBoard projectKey={projectKey} canManage={canManage} />
    </div>
  )
}
