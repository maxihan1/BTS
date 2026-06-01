// 프로젝트 멤버 설정 라우트 페이지 — RouteAdapter + props 기반 Page (라우터 비의존 단위 테스트 가능)
import type { JSX } from 'react'
import { useParams } from '@tanstack/react-router'
import { MemberList } from '@/components/admin/MemberList'
import { useProjectMembers } from '@/hooks/use-project-members'
import { ProjectMemberApiError } from '@/api/project-members'

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을 추출하여 ProjectMembersSettingsPage에 전달한다.
 */
export function ProjectMembersSettingsRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <ProjectMembersSettingsPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectMembersSettingsPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

/**
 * 프로젝트 멤버 설정 페이지.
 *
 * - useProjectMembers(projectKey)로 에러 종류를 판별한다.
 *   TanStack Query 캐시 공유로 MemberList 내부와 별도 네트워크 요청이 발생하지 않는다.
 * - project_not_found(404) — 페이지 레벨에서 "접근 권한이 없습니다" 안내 화면을 렌더한다.
 *   비멤버 또는 미존재 프로젝트 모두 이 에러코드를 반환하므로 동일하게 처리한다. (S6)
 * - 그 외 에러 — MemberList 내부 에러 분기에 위임한다.
 * - 정상 — 헤더 + MemberList 렌더.
 *
 * 라우터 의존 없이 props로 projectKey를 받아 단위 테스트가 가능하다.
 */
export function ProjectMembersSettingsPage({
  projectKey,
}: ProjectMembersSettingsPageProps): JSX.Element {
  const { error, isError } = useProjectMembers(projectKey)

  // S6 — project_not_found(404): 비멤버 또는 미존재 프로젝트 → 접근 불가 안내
  if (
    isError &&
    error instanceof ProjectMemberApiError &&
    error.errorCode === 'project_not_found'
  ) {
    return <ProjectNotFoundScreen />
  }

  return (
    <div className="p-8 space-y-6 max-w-2xl">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold">멤버 설정</h1>
        <p className="text-muted-foreground text-sm">
          프로젝트 멤버를 관리합니다. 관리자만 멤버 추가·역할 변경·제거를 할 수 있습니다.
        </p>
      </header>
      <MemberList projectKey={projectKey} />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ProjectNotFoundScreen — S6 접근 불가 안내 (named export, 재사용 가능)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * S6 — 비멤버 또는 미존재 프로젝트 접근 시 표시되는 안내 화면.
 *
 * project_not_found(404) 에러코드를 수신한 경우 페이지 레벨에서 렌더된다.
 * 비멤버 접근과 프로젝트 미존재를 동일하게 처리해 프로젝트 존재 여부를 노출하지 않는다.
 * named export로 다른 설정 페이지에서도 재사용 가능하다.
 */
export function ProjectNotFoundScreen(): JSX.Element {
  return (
    <div className="p-8 flex flex-col items-center justify-center min-h-48 gap-4 text-center">
      <p className="text-lg font-medium">접근 권한이 없습니다</p>
      <p className="text-sm text-muted-foreground">
        해당 프로젝트가 존재하지 않거나 접근 권한이 없습니다.
      </p>
    </div>
  )
}
