// 프로젝트 목록 라우트 — ProjectListRouteAdapter + ProjectListPage(props 기반, 라우터 비의존) (FR-PJ PR-5 Task 4)
import type { JSX } from 'react'
import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import type { Project } from '@/api/projects'
import { useProjects } from '@/hooks/use-projects'
import { useAuthUser } from '@/auth/authStore'
import { PageLayout } from '@/components/layout/PageLayout'
import { PageHeader } from '@/components/layout/PageHeader'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/switch'
import { Label } from '@/components/ui/label'
import { ProjectListTable, resolveProjectPath } from '@/components/project/ProjectListTable'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 로컬 라벨 (공유 i18n 파일 미변경, 병렬 충돌 방어)
// ─────────────────────────────────────────────────────────────────────────────

const LABELS = {
  pageTitle: '프로젝트',
  newProjectButton: '새 프로젝트',
  archivedToggle: '아카이브된 프로젝트 표시',
} as const

/** 아카이브 토글 Switch↔Label 연결용 id */
const ARCHIVED_TOGGLE_ID = 'project-list-archived-toggle'

// ─────────────────────────────────────────────────────────────────────────────
// Page — 라우터 비의존(RouteAdapter가 navigate를 콜백으로 주입)
// ─────────────────────────────────────────────────────────────────────────────

export interface ProjectListPageProps {
  /** 행 클릭 시 프로젝트 경로로 이동하는 콜백 — RouteAdapter가 useNavigate로 주입한다 */
  readonly onNavigateToProject: (project: Project) => void
}

/**
 * 프로젝트 목록 페이지.
 *
 * @remarks
 * - `useProjects(archived)`로 목록 조회 — 정렬은 백엔드 신뢰(프론트 재정렬 없음, S1).
 * - `useAuthUser().canCreateProject === true`일 때만 "새 프로젝트" 버튼을 PageHeader
 *   actions에 노출한다(S4). false/undefined(EC-6 하위호환)면 숨김 — 백엔드가 최종
 *   방어(fail-closed)이므로 이 게이팅은 UX 편의일 뿐이다.
 * - "새 프로젝트" 버튼은 TanStack `Link`가 아니라 `<a href="/projects/new">`다
 *   (issues.index `NewIssueButton` 선례 동형) — Page를 라우터 컨텍스트 없이 단위
 *   테스트 가능하게 유지한다.
 * - 아카이브 토글은 `ui/switch`(단순 boolean 필터) — Radix Tabs는 쓰지 않는다
 *   (뷰 전환이 아니라 필터이므로, [[frontend-nav-aria-label-e2e-contract]] nav role
 *   파손 방지와 무관하지만 관례를 따른다).
 * - 실제 행 클릭 경로 분기(활성→board, 아카이브→settings/details, G3)는
 *   {@link resolveProjectPath}(ProjectListTable 소유) + RouteAdapter의 navigate 호출이 담당한다.
 */
export function ProjectListPage({ onNavigateToProject }: ProjectListPageProps): JSX.Element {
  const [archived, setArchived] = useState(false)
  const { data, isLoading, isError } = useProjects(archived)
  const user = useAuthUser()
  const canCreateProject = user?.canCreateProject === true
  const projects = data ?? []

  return (
    <PageLayout maxWidth="7xl">
      <PageHeader
        title={LABELS.pageTitle}
        actions={
          canCreateProject ? (
            <Button asChild>
              <a href="/projects/new">{LABELS.newProjectButton}</a>
            </Button>
          ) : undefined
        }
      />

      <div className="mb-4 flex items-center gap-2">
        <Switch id={ARCHIVED_TOGGLE_ID} checked={archived} onCheckedChange={setArchived} />
        <Label htmlFor={ARCHIVED_TOGGLE_ID}>{LABELS.archivedToggle}</Label>
      </div>

      <ProjectListTable
        projects={projects}
        isLoading={isLoading}
        isError={isError}
        archived={archived}
        canCreateProject={canCreateProject}
        onNavigateToProject={onNavigateToProject}
      />
    </PageLayout>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter — router.ts 등록용 (라우트 등록 자체는 Task 7 담당)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 *
 * `useNavigate`로 행 클릭 시 {@link resolveProjectPath}가 결정한 경로로 이동한다.
 *
 * `/projects/...` 경로는 router.ts 등록 완료 시 타입 추론된다 — 현재는 string
 * cast로 우회한다(`issues.$key.tsx` 선례, Task 7에서 등록).
 */
export function ProjectListRouteAdapter(): JSX.Element {
  const navigate = useNavigate()

  function handleNavigateToProject(project: Project): void {
    void navigate({ to: resolveProjectPath(project) as string })
  }

  return <ProjectListPage onNavigateToProject={handleNavigateToProject} />
}
