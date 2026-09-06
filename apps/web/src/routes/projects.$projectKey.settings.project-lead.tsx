// 프로젝트 리드 설정 페이지 — RouteAdapter + props 기반 Page (FR-CM-04 D6)
import type { JSX } from 'react'
import { useState, useEffect } from 'react'
import { useParams } from '@tanstack/react-router'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { useProjectLead, useChangeProjectLead } from '@/hooks/use-project-lead'
import { useUsers, useUsersByIds } from '@/hooks/use-users'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { ProjectLeadSelect } from '@/components/project/ProjectLeadSelect'
import { projectLeadLabels } from '@/i18n/project-lead-labels'
import { ProjectNotFoundScreen } from '@/routes/projects.$projectKey.settings.members'

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을 추출하여 ProjectLeadSettingsPage에 전달한다.
 */
export function ProjectLeadSettingsRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <ProjectLeadSettingsPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectLeadSettingsPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

/**
 * 프로젝트 리드 설정 페이지.
 *
 * - useProjectLead(projectKey): 현재 리드 조회. 로딩 → "로딩 중", 404 에러 → ProjectNotFoundScreen.
 * - leadUserId가 있으면 useUsersByIds로 표시명 조회 → ProjectLeadSelect currentLead prop.
 * - 검색: useState(searchQuery) + 300ms debounce → useUsers(debouncedQuery).
 * - 변경: useChangeProjectLead.mutate(userId | null). 토스트는 훅이 처리.
 * - 권한 fail-closed: useProjectPermissions canManage=false면 disabled=true (isomorphic-clone-permission-guard-gap 교훈).
 *
 * 라우터 의존 없이 props로 projectKey를 받아 단위 테스트가 가능하다.
 */
export function ProjectLeadSettingsPage({
  projectKey,
}: ProjectLeadSettingsPageProps): JSX.Element {
  // ── 데이터 조회 ──
  const { data: projectLead, isLoading, isError } = useProjectLead(projectKey)
  const changeLead = useChangeProjectLead(projectKey)

  // ── 권한 (fail-closed) ──
  const {
    data: permissionsData,
    isLoading: isPermissionsLoading,
    isError: isPermissionsError,
  } = useProjectPermissions(projectKey)
  const canManage =
    !isPermissionsLoading &&
    !isPermissionsError &&
    permissionsData?.permissions.MANAGE_COMPONENTS === true

  // ── 검색 debounce ──
  const [searchQuery, setSearchQuery] = useState('')
  const [debouncedQuery, setDebouncedQuery] = useState('')

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedQuery(searchQuery)
    }, 300)
    return () => {
      clearTimeout(timer)
    }
  }, [searchQuery])

  // ── 사용자 데이터 ──
  const { data: searchUsers = [] } = useUsers(debouncedQuery)
  const leadIds = projectLead?.leadUserId != null ? [projectLead.leadUserId] : []
  const { data: leadUsers = [] } = useUsersByIds(leadIds)
  const currentLead = leadUsers.find((u) => u.id === projectLead?.leadUserId) ?? null

  /**
   * leadUserId는 있지만 useUsersByIds 결과에 없는 경우 — 삭제/비활성 사용자.
   * "알 수 없는 사용자" 상태로 표시해 관리자가 거짓 "미지정"으로 오인하지 않도록 한다.
   */
  const unknownLeadId =
    projectLead?.leadUserId != null && currentLead === null ? projectLead.leadUserId : null

  // ── 분기 처리 ──
  if (isLoading) {
    return (
      <div className="flex items-center justify-center p-8 text-muted-foreground">로딩 중...</div>
    )
  }

  if (isError) {
    return <ProjectNotFoundScreen />
  }

  return (
    <div className="p-8 space-y-6 max-w-2xl">
      <header className="space-y-1">
        <h2 className="text-xl font-semibold">{projectLeadLabels.page.heading}</h2>
        <p className="text-muted-foreground text-sm">{projectLeadLabels.page.description}</p>
      </header>

      <Card>
        <CardHeader>
          <CardTitle>{projectLeadLabels.form.currentLeadTitle}</CardTitle>
          <CardDescription>{projectLeadLabels.page.description}</CardDescription>
        </CardHeader>
        <CardContent>
          <ProjectLeadSelect
            users={searchUsers}
            currentLead={currentLead}
            unknownLeadId={unknownLeadId}
            onSearch={setSearchQuery}
            onChange={(userId) => changeLead.mutate(userId)}
            disabled={!canManage || changeLead.isPending}
          />
        </CardContent>
      </Card>
    </div>
  )
}
