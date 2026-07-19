// 사이드바 프로젝트 트리 — GET /api/v1/projects 소비, 2단 그룹 아코디언(직접링크 3 + 리포트/설정 중첩그룹) — FR-UX-06 PR12 Task 2 (아직 Sidebar에 미배선, T3이 배선)
import { useState, useEffect, type JSX } from 'react'
import { Link, useParams } from '@tanstack/react-router'
import { ChevronRight, ChevronDown } from 'lucide-react'
import { useProjects } from '@/hooks/use-projects'
import { useSidebarCollapsed } from '@/hooks/use-sidebar-collapsed'
import { navLabels } from '@/i18n/nav-labels'
import { Skeleton } from '@/components/ui/skeleton'
import type { Project } from '@/api/projects'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 서브링크(직접링크/리포트/설정) 1건 — `to`는 `$projectKey` 플레이스홀더 포함 라우트 경로 */
interface ProjectSubLink {
  readonly to: string
  readonly label: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 스타일(PR11 Sidebar.tsx `--sidebar-*` 토큰 클래스 관례 재사용, R1)
// ─────────────────────────────────────────────────────────────────────────────

/** 사이드바 아이콘 공통 스타일 (Sidebar.tsx NAV_ICON_CLASS 관례 재사용) */
const TREE_ICON_CLASS = 'size-4 shrink-0'

/**
 * 프로젝트명 링크 베이스 스타일. `[&.active]`는 TanStack Router `Link`가 활성 라우트에
 * 자동 주입하는 `class="active"`에 반응하지만, 프로젝트명 링크는 항상 `/board`로만
 * 이동하므로(하위 뷰 이동 시 자동 반영 안 됨) 실제 활성 강조는 {@link isActive} prop
 * 기반 {@link TREE_LINK_ACTIVE_CLASS} 조합으로 별도 적용한다.
 */
const TREE_LINK_BASE_CLASS =
  'flex min-w-0 flex-1 items-center gap-2 truncate rounded-md px-2 py-1.5 text-sm font-medium ' +
  'text-sidebar-foreground/80 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'

/** 활성 프로젝트 강조 — `--sidebar-*` 토큰 재사용(색 하드코딩 없음, R1) */
const TREE_LINK_ACTIVE_CLASS = 'bg-sidebar-accent text-sidebar-accent-foreground font-semibold'

/** 하위 링크(직접링크/리포트/설정 항목) 스타일 — 실제 라우트 활성 시 `[&.active]`로 강조 */
const SUB_LINK_CLASS =
  'block truncate rounded-md px-2 py-1 text-sm text-sidebar-foreground/70 ' +
  'hover:bg-sidebar-accent hover:text-sidebar-accent-foreground ' +
  '[&.active]:bg-sidebar-accent [&.active]:text-sidebar-accent-foreground [&.active]:font-medium'

/** 디스클로저 토글 버튼 공통 스타일 */
const DISCLOSURE_BUTTON_CLASS =
  'flex shrink-0 items-center justify-center rounded p-1 text-sidebar-foreground/60 ' +
  'hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'

/** 리포트/설정 그룹 디스클로저 버튼 스타일 — 아이콘+라벨 가로 배치 */
const GROUP_DISCLOSURE_BUTTON_CLASS = `${DISCLOSURE_BUTTON_CLASS} w-full justify-start gap-1 px-2 text-sm text-sidebar-foreground/70`

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 서브링크 데이터 (router.ts 실측 실 라우트만, S3 죽은 링크 0)
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 기본 뷰(보드) 라우트 경로 — 프로젝트명 링크·접힘레일 링크 공통 */
const PROJECT_BOARD_PATH = '/projects/$projectKey/board'

/** 직접 링크 3종 — 보드·백로그·타임라인 (FR4) */
const DIRECT_LINKS: ReadonlyArray<ProjectSubLink> = [
  { to: PROJECT_BOARD_PATH, label: '보드' },
  { to: '/projects/$projectKey/backlog', label: '백로그' },
  { to: '/projects/$projectKey/timeline', label: '타임라인' },
]

/** 리포트 그룹 서브링크 4종 (FR4) */
const REPORT_LINKS: ReadonlyArray<ProjectSubLink> = [
  { to: '/projects/$projectKey/reports/velocity', label: '벨로시티' },
  { to: '/projects/$projectKey/reports/cfd', label: '누적 흐름도(CFD)' },
  { to: '/projects/$projectKey/reports/cycle-time', label: '사이클/리드 타임' },
  { to: '/projects/$projectKey/reports/worklog', label: '작업 로그' },
]

/** 프로젝트 설정 그룹 서브링크 11종 (FR4, GAP-1 전 인증자 표시 — 게이팅 없음) */
const SETTINGS_LINKS: ReadonlyArray<ProjectSubLink> = [
  { to: '/projects/$projectKey/settings/workflow-scheme', label: '워크플로우 스킴' },
  { to: '/projects/$projectKey/settings/members', label: '멤버' },
  { to: '/projects/$projectKey/settings/components', label: '컴포넌트' },
  { to: '/projects/$projectKey/settings/versions', label: '버전' },
  { to: '/projects/$projectKey/settings/custom-fields', label: '커스텀 필드' },
  { to: '/projects/$projectKey/settings/issue-templates', label: '이슈 템플릿' },
  { to: '/projects/$projectKey/settings/field-permissions', label: '필드 권한' },
  { to: '/projects/$projectKey/settings/automation', label: '자동화' },
  { to: '/projects/$projectKey/settings/slack-channels', label: 'Slack 채널' },
  { to: '/projects/$projectKey/settings/project-lead', label: '프로젝트 리드' },
  { to: '/projects/$projectKey/settings/import', label: '가져오기' },
]

/** 리포트 중첩그룹 디스클로저 라벨 */
const REPORTS_GROUP_LABEL = '리포트'
/** 설정 중첩그룹 디스클로저 라벨 */
const SETTINGS_GROUP_LABEL = '프로젝트 설정'
/** 빈 목록 문구 */
const EMPTY_MESSAGE = '접근 가능한 프로젝트가 없습니다'
/** 로딩 스켈레톤 행 수 */
const SKELETON_ROW_COUNT = 3

// ─────────────────────────────────────────────────────────────────────────────
// 순수 함수
// ─────────────────────────────────────────────────────────────────────────────

/** 활성 프로젝트 여부에 따라 프로젝트명 링크 클래스를 조합한다 */
function projectLinkClassName(isActive: boolean): string {
  return isActive ? `${TREE_LINK_BASE_CLASS} ${TREE_LINK_ACTIVE_CLASS}` : TREE_LINK_BASE_CLASS
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 로딩/빈 상태
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 목록 로딩 중 표시하는 스켈레톤 placeholder (R4) */
function ProjectTreeSkeleton(): JSX.Element {
  return (
    <div className="flex flex-col gap-2 px-2 py-1" aria-hidden="true">
      {Array.from({ length: SKELETON_ROW_COUNT }, (_, index) => (
        <Skeleton key={index} className="h-6 w-full" />
      ))}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 리포트/설정 중첩그룹 (2단 디스클로저)
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectSubLinkGroupProps {
  readonly projectKey: string
  readonly label: string
  readonly links: ReadonlyArray<ProjectSubLink>
  readonly expanded: boolean
  readonly onToggle: () => void
}

/** 리포트·설정 그룹 공용 중첩 디스클로저. 그룹 헤더 텍스트 자체가 버튼 접근가능 이름이다 */
function ProjectSubLinkGroup({ projectKey, label, links, expanded, onToggle }: ProjectSubLinkGroupProps): JSX.Element {
  return (
    <li>
      <button
        type="button"
        aria-expanded={expanded}
        onClick={onToggle}
        className={GROUP_DISCLOSURE_BUTTON_CLASS}
      >
        {expanded ? (
          <ChevronDown aria-hidden="true" className={TREE_ICON_CLASS} />
        ) : (
          <ChevronRight aria-hidden="true" className={TREE_ICON_CLASS} />
        )}
        <span>{label}</span>
      </button>
      {expanded && (
        <ul className="ml-4 flex flex-col gap-0.5 border-l border-sidebar-border pl-2">
          {links.map((link) => (
            <li key={link.to}>
              <Link to={link.to} params={{ projectKey }} className={SUB_LINK_CLASS}>
                {link.label}
              </Link>
            </li>
          ))}
        </ul>
      )}
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 프로젝트 행 (접힘 레일 / 펼침 트리)
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectTreeRowProps {
  readonly project: Project
  readonly isActive: boolean
  readonly expanded: boolean
  readonly collapsed: boolean
  readonly onToggle: () => void
}

/** 사이드바 접힘(64px 레일) 시 — 아이콘/이니셜 링크만 노출, 그룹 펼침 비활성 (FR6) */
function ProjectTreeCollapsedRow({
  project,
  isActive,
}: {
  project: Project
  isActive: boolean
}): JSX.Element {
  return (
    <li>
      <Link
        to={PROJECT_BOARD_PATH}
        params={{ projectKey: project.key }}
        aria-current={isActive ? 'page' : undefined}
        className={`${projectLinkClassName(isActive)} justify-center`}
      >
        <span
          aria-hidden="true"
          className="flex size-6 shrink-0 items-center justify-center rounded-full bg-sidebar-accent text-xs font-semibold"
        >
          {project.name.charAt(0)}
        </span>
        <span className="sr-only">{project.name}</span>
      </Link>
    </li>
  )
}

interface ProjectTreeExpandedContentProps {
  readonly projectKey: string
  readonly reportsExpanded: boolean
  readonly onToggleReports: () => void
  readonly settingsExpanded: boolean
  readonly onToggleSettings: () => void
}

/** 펼친 프로젝트 행의 하위 콘텐츠 — 직접 링크 3 + 리포트/설정 중첩그룹 2 (FR4) */
function ProjectTreeExpandedContent({
  projectKey,
  reportsExpanded,
  onToggleReports,
  settingsExpanded,
  onToggleSettings,
}: ProjectTreeExpandedContentProps): JSX.Element {
  return (
    <ul className="ml-4 flex flex-col gap-0.5 border-l border-sidebar-border pl-2">
      {DIRECT_LINKS.map((link) => (
        <li key={link.to}>
          <Link to={link.to} params={{ projectKey }} className={SUB_LINK_CLASS}>
            {link.label}
          </Link>
        </li>
      ))}
      <ProjectSubLinkGroup
        projectKey={projectKey}
        label={REPORTS_GROUP_LABEL}
        links={REPORT_LINKS}
        expanded={reportsExpanded}
        onToggle={onToggleReports}
      />
      <ProjectSubLinkGroup
        projectKey={projectKey}
        label={SETTINGS_GROUP_LABEL}
        links={SETTINGS_LINKS}
        expanded={settingsExpanded}
        onToggle={onToggleSettings}
      />
    </ul>
  )
}

/** 프로젝트 행 헤더 — 디스클로저 버튼 + 프로젝트명 링크(→ board) */
function ProjectTreeRowHeader({ project, isActive, expanded, onToggle }: ProjectTreeRowProps): JSX.Element {
  return (
    <div className="flex items-center gap-1">
      <button
        type="button"
        aria-expanded={expanded}
        aria-label={`${project.name} 하위 메뉴`}
        onClick={onToggle}
        className={DISCLOSURE_BUTTON_CLASS}
      >
        {expanded ? (
          <ChevronDown aria-hidden="true" className={TREE_ICON_CLASS} />
        ) : (
          <ChevronRight aria-hidden="true" className={TREE_ICON_CLASS} />
        )}
      </button>
      <Link
        to={PROJECT_BOARD_PATH}
        params={{ projectKey: project.key }}
        aria-current={isActive ? 'page' : undefined}
        className={projectLinkClassName(isActive)}
      >
        {project.name}
      </Link>
    </div>
  )
}

/** 프로젝트 1건 — 헤더(디스클로저+링크) + 펼침 시 2단 서브그룹, 접힘 레일이면 아이콘 행 (FR3~FR6) */
function ProjectTreeRow(props: ProjectTreeRowProps): JSX.Element {
  const [reportsExpanded, setReportsExpanded] = useState(false)
  const [settingsExpanded, setSettingsExpanded] = useState(false)
  const { project, isActive, expanded, collapsed } = props

  if (collapsed) {
    return <ProjectTreeCollapsedRow project={project} isActive={isActive} />
  }

  return (
    <li>
      <ProjectTreeRowHeader {...props} />
      {expanded && (
        <ProjectTreeExpandedContent
          projectKey={project.key}
          reportsExpanded={reportsExpanded}
          onToggleReports={() => setReportsExpanded((prev) => !prev)}
          settingsExpanded={settingsExpanded}
          onToggleSettings={() => setSettingsExpanded((prev) => !prev)}
        />
      )}
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사이드바 프로젝트 트리 — `GET /api/v1/projects` 첫 소비자(useProjects), 2단 그룹 아코디언.
 *
 * - `<nav aria-label={navLabels.projectNav}>`("프로젝트", 🔒 e2e 계약. '프로젝트 뷰 전환'의
 *   substring이므로 Playwright role 조회는 항상 `exact: true` 사용 — 자세한 내용은
 *   {@link navLabels}.projectNav JSDoc 참고).
 * - 각 프로젝트 행 = 디스클로저 버튼(`aria-expanded`) + 프로젝트명 링크(→ `/projects/{key}/board`).
 * - 펼침 시 직접 링크 3(보드·백로그·타임라인) + `리포트` 중첩그룹(4) + `프로젝트 설정` 중첩그룹(11).
 *   모든 서브링크는 실재 라우트만 사용한다(요약은 라우트 부재로 미포함, S3).
 * - 활성 프로젝트는 `useParams({ strict: false }).projectKey`와 프로젝트 key가 일치할 때
 *   결정되며, 자동 펼침 + `aria-current="page"`가 부여된다. 프로젝트 컨텍스트 밖(파라미터
 *   없음)이면 전부 접힌다. 라우트가 바뀌면 이 자동펼침 집합을 다시 계산해 이전 수동 펼침을
 *   덮어쓴다(FR5) — `expandedKeys`는 `activeProjectKey`가 바뀔 때마다 `useEffect`로 재설정된다.
 * - 수동 펼침(디스클로저 클릭)은 ephemeral `useState`이며 영속하지 않는다.
 * - 사이드바 접힘(64px 레일, {@link useSidebarCollapsed})이면 각 프로젝트는 아이콘(이니셜)만
 *   노출하고 텍스트는 `sr-only`로 감추며, 그룹 펼침은 비활성화된다(FR6).
 * - GAP-1(게이트1 확정) — 설정 그룹 11링크는 모든 인증 사용자에게 표시한다.
 *   `isSystemAdmin` 게이팅을 걸지 않는다(관리 nav와 다른 정책, 백엔드 fail-closed 신뢰).
 * - 로딩=스켈레톤, 빈 배열=빈 상태 문구, 에러=`null` 반환으로 트리 자체를 렌더하지 않는다
 *   (조용한 fail-safe — 사이드바 전체를 차단하지 않는다).
 */
export function ProjectTree(): JSX.Element | null {
  const { projectKey: activeProjectKey } = useParams({ strict: false })
  const { data: projects, isLoading, isError } = useProjects()
  const { collapsed } = useSidebarCollapsed()
  const [expandedKeys, setExpandedKeys] = useState<Set<string>>(
    () => new Set(activeProjectKey !== undefined ? [activeProjectKey] : []),
  )

  // 라우트 이동으로 활성 프로젝트가 바뀔 때마다 펼침 상태를 현재 프로젝트 기준으로 재계산한다.
  // 이전 수동 펼침은 여기서 리셋된다(FR5 "현재 프로젝트 기준 재계산").
  useEffect(() => {
    setExpandedKeys(new Set(activeProjectKey !== undefined ? [activeProjectKey] : []))
  }, [activeProjectKey])

  const toggleProject = (key: string): void => {
    setExpandedKeys((prev) => {
      const next = new Set(prev)
      if (next.has(key)) {
        next.delete(key)
      } else {
        next.add(key)
      }
      return next
    })
  }

  if (isError) {
    return null
  }

  return (
    <nav aria-label={navLabels.projectNav} className="flex flex-col gap-1 p-2">
      {isLoading ? (
        <ProjectTreeSkeleton />
      ) : projects === undefined || projects.length === 0 ? (
        <p className="px-2 py-3 text-center text-sm text-sidebar-foreground/60">{EMPTY_MESSAGE}</p>
      ) : (
        <ul className="flex flex-col gap-1">
          {projects.map((project) => (
            <ProjectTreeRow
              key={project.id}
              project={project}
              isActive={project.key === activeProjectKey}
              expanded={expandedKeys.has(project.key)}
              collapsed={collapsed}
              onToggle={() => toggleProject(project.key)}
            />
          ))}
        </ul>
      )}
    </nav>
  )
}
