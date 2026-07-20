// 프로젝트 일반 설정(details) 라우트 페이지 — RouteAdapter + props 기반 Page, name 편집 + 아카이브 danger zone (FR-PJ PR-5 Task 6)
import type { JSX, FormEvent } from 'react'
import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { PageLayout } from '@/components/layout/PageLayout'
import { PageHeader } from '@/components/layout/PageHeader'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { useProject } from '@/hooks/use-project'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { useUpdateProjectName } from '@/hooks/use-project-mutations'
import { extractProjectErrorCode, ProjectErrorCodes } from '@/api/projects'
import type { Project } from '@/api/projects'
import { ProjectNotFoundScreen } from '@/routes/projects.$projectKey.settings.members'
import { ProjectDangerZone } from '@/components/project/ProjectDangerZone'

// ─────────────────────────────────────────────────────────────────────────────
// 라벨 상수 (로컬)
// ─────────────────────────────────────────────────────────────────────────────

const detailsLabels = {
  page: {
    fallbackHeading: '프로젝트 설정',
    description: '프로젝트 이름을 변경하고 아카이브 상태를 관리합니다.',
    loading: '로딩 중...',
    loadError: '프로젝트를 불러오지 못했습니다.',
  },
  form: {
    nameLabel: '프로젝트 이름',
    saveButton: '저장',
    savingButton: '저장 중...',
    saveSuccess: '이름이 변경되었습니다.',
    saveError: '이름을 변경하지 못했습니다. 다시 시도해주세요.',
    archivedNotice: '아카이브된 프로젝트는 설정을 변경할 수 없습니다',
    readOnlyNotice: '이 프로젝트의 설정을 변경할 권한이 없습니다.',
  },
} as const

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을 추출하여 ProjectDetailsSettingsPage에 전달한다.
 */
export function ProjectDetailsSettingsRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <ProjectDetailsSettingsPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectDetailsSettingsPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

/**
 * 프로젝트 일반 설정(details) 페이지 — name 편집 + 아카이브/해제 danger zone.
 *
 * - useProject(projectKey)로 단건(archived 포함) 조회.
 * - 404(ISSUE_PROJECT_NOT_FOUND) 또는 403(ISSUE_PROJECT_FORBIDDEN, 비멤버) 에러코드는
 *   동일하게 {@link ProjectNotFoundScreen}으로 처리해 프로젝트 존재 여부를 노출하지 않는다(S6 동형).
 * - MANAGE_COMPONENTS 권한(useProjectPermissions)이 true일 때만 이름 편집·아카이브 액션이
 *   활성화된다(=PROJECT_ADMIN, fail-closed — isomorphic-clone-permission-guard-gap 재발 방지).
 * - h1은 프로젝트명(로드 완료 시) 또는 "프로젝트 설정"(로딩/폴백) — PageHeader가 단독 소유.
 *
 * 라우터 의존 없이 props로 projectKey를 받아 단위 테스트가 가능하다.
 */
export function ProjectDetailsSettingsPage({
  projectKey,
}: ProjectDetailsSettingsPageProps): JSX.Element {
  const { data: project, isLoading, isError, error } = useProject(projectKey)
  const {
    data: permissionsData,
    isLoading: isPermissionsLoading,
    isError: isPermissionsError,
  } = useProjectPermissions(projectKey)

  const canManage =
    !isPermissionsLoading &&
    !isPermissionsError &&
    permissionsData?.permissions.MANAGE_COMPONENTS === true

  // 404/비멤버(403) — 프로젝트 존재 여부를 노출하지 않기 위해 동일 화면으로 처리한다.
  const errorCode = extractProjectErrorCode(error)
  const isNotFoundLike =
    isError &&
    (errorCode === ProjectErrorCodes.NOT_FOUND || errorCode === ProjectErrorCodes.FORBIDDEN)

  if (isNotFoundLike) {
    return <ProjectNotFoundScreen />
  }

  // 404/403 외 에러(500·네트워크·세션만료·스키마 드리프트 등) — 조기 return하지 않으면
  // project가 계속 undefined라 아래 isLoading 삼항이 영구 로딩으로 낙하한다.
  if (isError) {
    return (
      <PageLayout maxWidth="2xl">
        <div role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">
          {detailsLabels.page.loadError}
        </div>
      </PageLayout>
    )
  }

  return (
    <PageLayout maxWidth="2xl">
      <PageHeader
        title={project?.name ?? detailsLabels.page.fallbackHeading}
        description={detailsLabels.page.description}
      />
      {isLoading || project === undefined ? (
        <p className="text-sm text-muted-foreground">{detailsLabels.page.loading}</p>
      ) : (
        <ProjectDetailsSettingsContent
          key={projectKey}
          projectKey={projectKey}
          project={project}
          canManage={canManage}
        />
      )}
    </PageLayout>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Content — 조회 성공 후에만 마운트 (React useState stale key prop 재발 방지)
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectDetailsSettingsContentProps {
  readonly projectKey: string
  readonly project: Project
  readonly canManage: boolean
}

/**
 * name 편집 폼 + danger zone. `key={projectKey}`로 부모에서 마운트되어 프로젝트가
 * 바뀌면 항상 재마운트된다(로컬 `name` state가 stale하게 남는 것을 방지).
 */
function ProjectDetailsSettingsContent({
  projectKey,
  project,
  canManage,
}: ProjectDetailsSettingsContentProps): JSX.Element {
  const updateName = useUpdateProjectName()
  const [name, setName] = useState(project.name)
  const [saveSuccess, setSaveSuccess] = useState(false)

  // EC-3 — 아카이브된 프로젝트는 백엔드가 409를 반환하므로 폼 자체를 비활성화해 방어한다.
  const isArchived = project.archived === true
  const isFormDisabled = isArchived || !canManage || updateName.isPending
  const isSaveDisabled = isFormDisabled || name.trim().length === 0

  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault()
    setSaveSuccess(false)
    updateName.reset()

    const trimmed = name.trim()
    if (trimmed.length === 0) return

    updateName.mutate(
      { idOrKey: projectKey, name: trimmed },
      { onSuccess: () => { setSaveSuccess(true) } },
    )
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>{detailsLabels.form.nameLabel}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {isArchived && (
            <p role="alert" className="text-sm text-muted-foreground">
              {detailsLabels.form.archivedNotice}
            </p>
          )}
          {!isArchived && !canManage && (
            <p className="text-sm text-muted-foreground">{detailsLabels.form.readOnlyNotice}</p>
          )}
          {saveSuccess && (
            <div role="status" className="rounded-lg bg-primary/10 p-3 text-sm text-primary">
              {detailsLabels.form.saveSuccess}
            </div>
          )}
          {updateName.isError && (
            <div
              role="alert"
              aria-live="polite"
              className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive"
            >
              {detailsLabels.form.saveError}
            </div>
          )}
          <form onSubmit={handleSubmit} noValidate className="space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="project-details-name">{detailsLabels.form.nameLabel}</Label>
              <Input
                id="project-details-name"
                value={name}
                disabled={isFormDisabled}
                onChange={(e) => { setName(e.target.value) }}
              />
            </div>
            <Button type="submit" disabled={isSaveDisabled}>
              {updateName.isPending ? detailsLabels.form.savingButton : detailsLabels.form.saveButton}
            </Button>
          </form>
        </CardContent>
      </Card>

      <ProjectDangerZone projectKey={projectKey} archived={isArchived} canManage={canManage} />
    </div>
  )
}
