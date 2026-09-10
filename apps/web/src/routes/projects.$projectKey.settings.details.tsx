// 프로젝트 일반 설정(details) 라우트 페이지 — RouteAdapter + props 기반 Page, name 편집 + 아카이브 danger zone (FR-PJ PR-5 Task 6)
import type { JSX, FormEvent } from 'react'
import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
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

/**
 * 프로젝트 설정 탭 공통 컨테이너 클래스.
 *
 * 🛑 `PageLayout`(=`mx-auto`) 으로 되돌리지 마라. 형제 설정 탭 11개(automation·components·
 * custom-fields·field-permissions·import·issue-templates·members·project-lead·slack-channels·
 * versions·workflow-scheme)가 전부 이 좌측 정렬 컨테이너를 쓴다. 이 탭만 중앙 정렬이면
 * 탭을 오갈 때 콘텐츠가 좌우로 236px 점프한다(실측). 전 라우트를 `PageLayout` 으로 모으는
 * 정리는 로드맵 F21 의 별건이며, 그때는 **12개를 함께** 옮겨야 한다.
 */
const SETTINGS_PAGE_CLASS = 'p-8 space-y-6 max-w-2xl'

const detailsLabels = {
  page: {
    /**
     * 본문 헤딩 — **화면 이름**이지 프로젝트명이 아니다 (Jira 패리티 J5-11).
     *
     * 🔒 설정 메뉴의 `settings/details` 항목 라벨(`PROJECT_SETTINGS_NAV`)과 같은 값이어야
     * 한다. 사이드바에서 고른 이름과 본문 제목이 다르면 「내가 누른 게 이 화면이 맞나」가 된다.
     */
    heading: '상세정보',
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
 * - 본문 헤딩은 `<h2>` **화면 이름**(`상세정보`)이다. 문서 `<h1>`(=프로젝트명)은 셸의
 *   `ProjectViewHeader` 가 단독 소유하고, 뷰는 자기 제목을 다시 쓰지 않는다(J5-11).
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
      <div className={SETTINGS_PAGE_CLASS}>
        <div role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">
          {detailsLabels.page.loadError}
        </div>
      </div>
    )
  }

  return (
    <div className={SETTINGS_PAGE_CLASS}>
      {/* 🛑 여기에 프로젝트명을 다시 쓰지 마라 (J5-11 「뷰는 자기 제목을 다시 쓰지 않는다」).
          셸 헤더가 바로 위에서 `<h1>{프로젝트명}</h1>` 을 내고 설정 서브앱에서는 그 아래
          「프로젝트 설정」 부제까지 붙으므로, 본문이 이름을 또 쓰면 화면이 **「이름 / 프로젝트
          설정 / 이름」**으로 읽힌다. `ProjectSummaryPage` 가 2026-09-07 에 같은 이유로 자기
          제목을 지웠고 이 화면만 그 처방을 늦게 받았다. 컨테이너 정렬은 형제 설정 화면에
          맞춘 그대로 둔다(F21 성공 조건). */}
      <header className="space-y-1">
        <h2 className="text-xl font-semibold">{detailsLabels.page.heading}</h2>
        <p className="text-muted-foreground text-sm">{detailsLabels.page.description}</p>
      </header>
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
    </div>
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
