// 필드 권한 규칙 설정 라우트 페이지 — RouteAdapter + props 기반 Page (라우터 비의존 단위 테스트 가능)
import type { JSX } from 'react'
import { useParams } from '@tanstack/react-router'
import { FieldPermissionList } from '@/components/field-permissions/FieldPermissionList'
import { ProjectNotFoundScreen } from '@/routes/projects.$projectKey.settings.members'

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을 추출하여 ProjectFieldPermissionsSettingsPage에 전달한다.
 */
export function ProjectFieldPermissionsSettingsRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <ProjectFieldPermissionsSettingsPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectFieldPermissionsSettingsPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

/**
 * 프로젝트 필드 권한 규칙 설정 페이지.
 *
 * - 헤더 + FieldPermissionList 렌더.
 * - projectKey가 없거나 빈 문자열이면 ProjectNotFoundScreen을 렌더한다.
 *
 * 라우터 의존 없이 props로 projectKey를 받아 단위 테스트가 가능하다.
 */
export function ProjectFieldPermissionsSettingsPage({
  projectKey,
}: ProjectFieldPermissionsSettingsPageProps): JSX.Element {
  if (!projectKey) {
    return <ProjectNotFoundScreen />
  }

  return (
    <div className="p-8 space-y-6 max-w-2xl">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold">필드 권한 규칙 관리</h1>
        <p className="text-muted-foreground text-sm">
          프로젝트 필드에 대한 그룹별 접근 권한을 설정합니다.
        </p>
      </header>
      <FieldPermissionList projectKey={projectKey} />
    </div>
  )
}
