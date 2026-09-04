// 사이드바 스페이스 트리 — 별표/최근/추가 3그룹 + 프로젝트별 보드 목록 + 스페이스·보드 `⋯` (Jira 패리티 캠페인 PR ⑩ · J2)
import { useId, useMemo, useState, useEffect, type JSX } from 'react'
import { Link, useParams, useSearch } from '@tanstack/react-router'
import { ChevronRight, ChevronDown, MoreHorizontal, Plus, Settings, Star, StarOff } from 'lucide-react'
import { toast } from 'sonner'
import { useProjects } from '@/hooks/use-projects'
import { useSidebarRailCollapsed } from '@/hooks/use-sidebar-drawer'
import { useProjectTreeExpanded } from '@/hooks/use-project-tree-expanded'
import { useProjectBoards, type BoardsByProject } from '@/hooks/use-project-boards'
import { useRecentProjects } from '@/hooks/use-recent-projects'
import { useAuthUser } from '@/auth/authStore'
import { useFavorites, useAddFavorite, useRemoveFavorite } from '@/api/favorites'
import { navLabels } from '@/i18n/nav-labels'
import { favoriteLabels } from '@/i18n/favorite-labels'
import { Skeleton } from '@/components/ui/skeleton'
import type { Project } from '@/api/projects'
import type { BoardSummary } from '@/api/boards'
import { Button } from '@/components/ui/button'
import { BoardActionsMenu } from '@/components/board/BoardActionsMenu'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { partitionProjectsForTree, pickBoardLookupKeys } from './project-tree-order'

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

/**
 * "모든 프로젝트" 진입 링크 스타일 — 트리 상단, `/projects` 목록으로 이동(G2).
 * `TREE_LINK_BASE_CLASS`와 동형(`--sidebar-*` 토큰 재사용)이되 `flex-1` 없이 단독 배치한다.
 */
const ALL_PROJECTS_LINK_CLASS =
  'flex min-w-0 flex-1 items-center gap-2 truncate rounded-md px-2 py-1.5 text-sm font-medium ' +
  'text-sidebar-foreground/80 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground ' +
  '[&.active]:bg-sidebar-accent [&.active]:text-sidebar-accent-foreground [&.active]:font-semibold'

/** 하위 링크(직접링크/리포트/설정 항목) 스타일 — 실제 라우트 활성 시 `[&.active]`로 강조 */
const SUB_LINK_CLASS =
  'block truncate rounded-md px-2 py-1 text-sm text-sidebar-foreground/70 ' +
  'hover:bg-sidebar-accent hover:text-sidebar-accent-foreground ' +
  '[&.active]:bg-sidebar-accent [&.active]:text-sidebar-accent-foreground [&.active]:font-medium'

/** 보드 링크 스타일 — {@link SUB_LINK_CLASS} 와 같되 옆의 `⋯` 와 한 행을 나눠 쓴다 */
const BOARD_LINK_CLASS = `${SUB_LINK_CLASS} min-w-0 flex-1`

/** 디스클로저 토글 버튼 공통 스타일 */
const DISCLOSURE_BUTTON_CLASS =
  'flex shrink-0 items-center justify-center rounded p-1 text-sidebar-foreground/60 ' +
  'hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'

/** 리포트/설정 그룹 디스클로저 버튼 스타일 — 아이콘+라벨 가로 배치 */
const GROUP_DISCLOSURE_BUTTON_CLASS = `${DISCLOSURE_BUTTON_CLASS} w-full justify-start gap-1 px-2 text-sm text-sidebar-foreground/70`

/** 3그룹 헤더(별표/최근/추가) 스타일 — 클릭 대상이 아닌 순수 구분 라벨이다 */
const TREE_GROUP_HEADING_CLASS =
  'px-2 pb-0.5 pt-2 text-xs font-semibold uppercase tracking-wide text-sidebar-foreground/50'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 서브링크 데이터 (router.ts 실측 실 라우트만, S3 죽은 링크 0)
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 보드 라우트 경로 — 보드 목록의 각 보드 링크가 `?board=<id>` 를 실어 이동한다 */
const PROJECT_BOARD_PATH = '/projects/$projectKey/board'

/**
 * 프로젝트 기본 착지 라우트 경로 — 프로젝트명 링크·접힘레일 링크 공통 (Jira 패리티 J4).
 * 하위 보드 링크와 **다른 상수**다. 하나로 묶으면 요약으로 옮기는 순간 보드 링크가 함께
 * 끌려가 하위 목록에서 보드로 갈 방법이 사라진다.
 */
const PROJECT_SUMMARY_PATH = '/projects/$projectKey'

/** 프로젝트 목록 라우트 경로 — "모든 프로젝트" 진입 링크(G2) */
const ALL_PROJECTS_PATH = '/projects'

/** 프로젝트 생성 라우트 경로 — 트리 헤더 `＋` (상단바 「만들기」의 프로젝트 진입과 같은 목적지) */
const NEW_PROJECT_PATH = '/projects/new'

/** 프로젝트 일반 설정 라우트 경로 — 스페이스 `⋯` 의 설정 항목 */
const PROJECT_SETTINGS_PATH = '/projects/$projectKey/settings/details'

/** "모든 프로젝트" 진입 링크 라벨 (G2) */
const ALL_PROJECTS_LABEL = '모든 프로젝트'

/**
 * 트리 헤더 `＋` 의 접근성 이름.
 *
 * 이 파일의 다른 표시 문구(`ALL_PROJECTS_LABEL` · `REPORTS_GROUP_LABEL` · `SETTINGS_GROUP_LABEL`)와
 * 같은 자리에 둔다 — `navLabels` 는 **nav `aria-label`** 의 출처이고 이것은 링크 이름이다.
 *
 * 부수적으로, `navLabels` 에 넣었다면 `nav-labels.test.ts` FR15 substring 판별식이 red 였을 것이다
 * (「새 프로젝트」가 `projectNav`('프로젝트')를 통째로 품는다). 그 판별식이 지키는 위험은
 * **nav 이름끼리의 충돌**이고 링크 이름인 이 값은 그 축에 없다 — 회피가 아니라 자리가 다른 것이다.
 */
const NEW_PROJECT_LABEL = '새 프로젝트'

/**
 * 스페이스 `⋯` 트리거의 접근성 이름.
 *
 * 보드 `⋯`(`boardLabels.actions.triggerAriaLabel` → "보드 관리, {이름}")와 **같은 형태**를
 * 쓰되 앞머리가 다르다. 한 트리 안에 두 종류의 `⋯` 가 나란히 서므로 이름이 겹치면
 * `getByRole` 이 어느 쪽인지 가릴 수 없다.
 *
 * @param projectName 대상 프로젝트 이름
 */
const spaceActionsTriggerLabel = (projectName: string): string => `스페이스 관리, ${projectName}`

/** 스페이스 `⋯` 의 프로젝트 설정 항목 라벨 — 설정 중첩그룹 헤더와 같은 문구를 공유한다 */
const SETTINGS_GROUP_LABEL = '프로젝트 설정'

/** 직접 링크 2종 — 백로그·타임라인. 「보드」는 보드 **목록**으로 갈렸다(J2) */
const DIRECT_LINKS: ReadonlyArray<ProjectSubLink> = [
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

/** 프로젝트 설정 그룹 서브링크 12종 (FR4, GAP-1 전 인증자 표시 — 게이팅 없음). "일반"은 FR-PJ PR-5 FE-4(11→12) */
const SETTINGS_LINKS: ReadonlyArray<ProjectSubLink> = [
  { to: PROJECT_SETTINGS_PATH, label: '일반' },
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
/** 빈 목록 문구 */
const EMPTY_MESSAGE = '접근 가능한 프로젝트가 없습니다'
/** 보드가 하나도 없는 프로젝트의 안내 문구 — **빈 배열일 때만** 낸다(조회 중에는 내지 않는다) */
const NO_BOARDS_MESSAGE = '보드 없음'
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
// 서브 컴포넌트 — 스페이스 `⋯` (별표 토글 · 프로젝트 설정)
// ─────────────────────────────────────────────────────────────────────────────

interface SpaceActionsMenuProps {
  readonly project: Project
  /** 이 프로젝트가 즐겨찾기인지 — 최상위가 이미 조회한 목록에서 파생한다(행마다 재조회하지 않는다) */
  readonly isFavorite: boolean
}

/**
 * 스페이스 행의 `⋯` — 별표 추가/해제 · 프로젝트 설정.
 *
 * 별표는 「별표 표시됨」 그룹의 **유일한 입력**이다({@link partitionProjectsForTree} 의
 * `favoriteKeys`). 토글 자리를 두지 않으면 그 그룹에 무언가를 넣을 방법이 트리 밖에만 있다.
 *
 * 즐겨찾기 상태를 prop 으로 받는 이유 — `FavoriteButton` 은 스스로 `useFavorites` 를 부르는데,
 * 트리는 최상위에서 이미 같은 쿼리를 소비하므로 행마다 훅을 또 부르면 구독만 늘어난다.
 */
function SpaceActionsMenu({ project, isFavorite }: SpaceActionsMenuProps): JSX.Element {
  const addFavorite = useAddFavorite()
  const removeFavorite = useRemoveFavorite()
  const pending = addFavorite.isPending || removeFavorite.isPending

  function handleToggleFavorite(): void {
    const payload = { targetType: 'PROJECT' as const, targetId: project.key }
    if (isFavorite) {
      removeFavorite.mutate(payload, {
        onError: () => { toast.error(favoriteLabels.removeError) },
      })
      return
    }
    addFavorite.mutate(payload, {
      onError: () => { toast.error(favoriteLabels.addError) },
    })
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="icon-xs"
          aria-label={spaceActionsTriggerLabel(project.name)}
          className={DISCLOSURE_BUTTON_CLASS}
        >
          <MoreHorizontal aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        <DropdownMenuItem disabled={pending} onSelect={handleToggleFavorite}>
          {isFavorite ? <StarOff aria-hidden="true" /> : <Star aria-hidden="true" />}
          {isFavorite ? favoriteLabels.removeAriaLabel : favoriteLabels.addAriaLabel}
        </DropdownMenuItem>
        <DropdownMenuItem asChild>
          <Link to={PROJECT_SETTINGS_PATH} params={{ projectKey: project.key }}>
            <Settings aria-hidden="true" />
            {SETTINGS_GROUP_LABEL}
          </Link>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 보드 목록
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectBoardListProps {
  readonly projectKey: string
  /** 이 프로젝트의 보드 목록. **`undefined` 는 「아직 모른다」**(조회 중·실패)이고 빈 배열과 다르다 */
  readonly boards: readonly BoardSummary[] | undefined
}

/**
 * 펼친 프로젝트의 보드 목록 (J2).
 *
 * 한때 여기 있던 것은 「보드」 링크 **하나**였다. Jira 새 네비게이션은 스페이스 아래에 보드를
 * 전부 늘어놓고, 그래야 보드가 여럿인 프로젝트에서 스위처를 열지 않고 옮겨 다닐 수 있다.
 *
 * - `undefined` 면 **아무것도 그리지 않는다**. 「보드 없음」을 함께 쓰면 조회가 끝나기 전에
 *   그 문구가 한 프레임 스쳐 지나간다.
 * - 삭제 뒤 이동이 필요 없어 `onDeleted` 는 비어 있다 — 목록 캐시 무효화로 행이 사라진다.
 *   보고 있던 보드를 지운 경우의 이동은 보드 화면(`projects.$projectKey.board.tsx`)이 맡는다.
 *
 * ### 🛑 사이드바 `⋯` 는 **삭제만** 한다 (Maxi 확정 2026-09-04, PR ⑩ 코드리뷰 CONCERNS-1)
 * `canRename` 과 `canConfigure` 에 `false` 를 **상수로** 넘긴다. 「권한을 몰라서 닫는다」가
 * 아니라 **이 자리에는 그 기능을 두지 않는다**는 뜻이다. 보드 설정(부채 177 · #452)도 같은
 * `IssuePermission.CREATE` 축이라 같은 판단을 받는다.
 *
 * 근거. 사이드바가 가진 유일한 권한 신호는 목록 응답의 `canDelete` 인데 그것은
 * `IssuePermission.SOFT_DELETE` 판정이고(`BoardController.kt:204`), 보드 이름 변경은
 * **`IssuePermission.CREATE`** 다(`BoardController.kt:58`). 두 권한은 독립이며 기본 스킴의
 * MEMBER 는 `CREATE_ISSUE` 만 갖는다(`V008…:67` + `IdentityAccessIssuePermissionResolver.kt:183`).
 * 그래서 `canDelete` 를 이름 변경의 근사로 쓰면 **권한이 있는 사용자에게서 기능을 빼앗는다** —
 * fail-closed 가 아니라 오판이다. 정확한 판정을 하려면 펼친 프로젝트마다
 * `useProjectPermissions` 를 또 조회해야 하는데(요청이 두 배), 이름 변경은 보드 화면 헤더가
 * 이미 **정확한 권한으로** 제공하므로 그 비용을 치를 이유가 없다.
 *
 * 삭제만 남으므로 `canDelete === true` 하나가 트리거 노출을 정한다 — `BoardActionsMenu` 의
 * `if (!canRename && !canDelete && !canConfigure) return null` 이 그것을 그대로 집행한다.
 * `undefined` 는 「못 함」이고, 그것이 `boardSummarySchema` 의 `canDelete` JSDoc 이 적은
 * fail-closed 계약이다.
 */
function ProjectBoardList({ projectKey, boards }: ProjectBoardListProps): JSX.Element | null {
  if (boards === undefined) return null

  if (boards.length === 0) {
    return <li className="px-2 py-1 text-sm text-sidebar-foreground/50">{NO_BOARDS_MESSAGE}</li>
  }

  return (
    <>
      {boards.map((board) => {
        // 목록 응답이 주는 유일한 권한 신호. `undefined` 는 「못 함」이다(fail-closed).
        const deletable = board.canDelete === true
        return (
          <li key={board.boardId} className="flex min-w-0 items-center gap-1">
            <Link
              to={PROJECT_BOARD_PATH}
              params={{ projectKey }}
              search={{ board: board.boardId }}
              className={BOARD_LINK_CLASS}
            >
              {board.name}
            </Link>
            <BoardActionsMenu
              projectKey={projectKey}
              boardId={board.boardId}
              boardName={board.name}
              // 🛑 상수 false 둘 — 이 자리에는 이름 변경도 보드 설정도 두지 않는다(위 JSDoc).
              //    둘 다 `IssuePermission.CREATE` 이고 사이드바에는 그 판정이 없다. `deletable`
              //    (= SOFT_DELETE)을 근사로 넣으면 CREATE 권한만 가진 사용자에게서 기능을 빼앗는다.
              canRename={false}
              canConfigure={false}
              canDelete={deletable}
              onDeleted={() => { /* 목록 캐시 무효화로 이 행이 사라진다 — 이동할 곳이 없다 */ }}
              triggerSize="icon-xs"
            />
          </li>
        )
      })}
    </>
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
      {/* PR22 OUT — P6 전체 클릭 영역: GROUP_DISCLOSURE_BUTTON_CLASS 가 w-full justify-start 로
          좌측 정렬 전체폭 행을 만들며, Button의 justify-center와 충돌한다 */}
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
  readonly isFavorite: boolean
  readonly boards: readonly BoardSummary[] | undefined
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
        to={PROJECT_SUMMARY_PATH}
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
  readonly boards: readonly BoardSummary[] | undefined
  readonly reportsExpanded: boolean
  readonly onToggleReports: () => void
  readonly settingsExpanded: boolean
  readonly onToggleSettings: () => void
}

/** 펼친 프로젝트 행의 하위 콘텐츠 — 보드 목록 + 직접 링크 2 + 리포트/설정 중첩그룹 2 (FR4 · J2) */
function ProjectTreeExpandedContent({
  projectKey,
  boards,
  reportsExpanded,
  onToggleReports,
  settingsExpanded,
  onToggleSettings,
}: ProjectTreeExpandedContentProps): JSX.Element {
  return (
    <ul className="ml-4 flex flex-col gap-0.5 border-l border-sidebar-border pl-2">
      <ProjectBoardList projectKey={projectKey} boards={boards} />
      {/* 🛑 백로그·타임라인·리포트 4·설정 12 는 여기 그대로 둔다 — 그 14개 화면의 **유일한
          진입로**다(2026-09-04 전수 grep, 대체 진입로 0건). 보드만 목록으로 갈렸다. */}
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

/** 프로젝트 행 헤더 — 디스클로저 버튼 + 프로젝트명 링크(→ 요약) + 스페이스 `⋯` */
function ProjectTreeRowHeader({ project, isActive, expanded, isFavorite, onToggle }: ProjectTreeRowProps): JSX.Element {
  return (
    <div className="flex items-center gap-1">
      <Button
        type="button"
        variant="ghost"
        size="icon-xs"
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
      </Button>
      <Link
        to={PROJECT_SUMMARY_PATH}
        params={{ projectKey: project.key }}
        aria-current={isActive ? 'page' : undefined}
        className={projectLinkClassName(isActive)}
      >
        {project.name}
      </Link>
      <SpaceActionsMenu project={project} isFavorite={isFavorite} />
    </div>
  )
}

/** 프로젝트 1건 — 헤더(디스클로저+링크+`⋯`) + 펼침 시 보드 목록·2단 서브그룹, 접힘 레일이면 아이콘 행 (FR3~FR6) */
function ProjectTreeRow(props: ProjectTreeRowProps): JSX.Element {
  const [reportsExpanded, setReportsExpanded] = useState(false)
  const [settingsExpanded, setSettingsExpanded] = useState(false)
  const { project, isActive, expanded, collapsed, boards } = props

  if (collapsed) {
    return <ProjectTreeCollapsedRow project={project} isActive={isActive} />
  }

  return (
    <li>
      <ProjectTreeRowHeader {...props} />
      {expanded && (
        <ProjectTreeExpandedContent
          projectKey={project.key}
          boards={boards}
          reportsExpanded={reportsExpanded}
          onToggleReports={() => { setReportsExpanded((prev) => !prev) }}
          settingsExpanded={settingsExpanded}
          onToggleSettings={() => { setSettingsExpanded((prev) => !prev) }}
        />
      )}
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 3그룹(별표/최근/추가)
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectTreeGroupProps {
  readonly heading: string
  readonly projects: readonly Project[]
  readonly activeProjectKey: string | undefined
  readonly expandedKeys: ReadonlySet<string>
  readonly favoriteKeys: ReadonlySet<string>
  readonly boardsByProject: BoardsByProject
  readonly collapsed: boolean
  readonly onToggle: (projectKey: string) => void
}

/**
 * 3그룹 중 한 구간 — 헤더 + 프로젝트 행 목록.
 *
 * **비면 헤더까지 통째로 사라진다.** 별표를 하나도 안 단 사용자에게 빈 「별표 표시됨」이
 * 남아 있으면 무엇을 넣어야 하는 자리인지 알 수 없는 채로 세로 공간만 먹는다.
 */
function ProjectTreeGroup({
  heading,
  projects,
  activeProjectKey,
  expandedKeys,
  favoriteKeys,
  boardsByProject,
  collapsed,
  onToggle,
}: ProjectTreeGroupProps): JSX.Element | null {
  // 🛑 훅은 조기 반환보다 **위**에 있어야 한다 — 빈 그룹에서 건너뛰면 렌더 간 훅 순서가 어긋난다.
  const headingId = useId()

  if (projects.length === 0) return null

  return (
    <>
      {/* 접힘 레일에서는 헤더 텍스트가 잘려 읽히므로 시각적으로만 숨긴다 — DOM 에는 남아
          구간 경계가 스크린리더에 계속 들린다(레일의 `sr-only` 관례와 같다).
          🛑 `aria-labelledby` 로 아래 목록과 **묶어야** 그 약속이 실제로 지켜진다. 연결이 없으면
             목록 앞을 지나가는 텍스트 한 줄일 뿐이라 목록에 들어선 사용자는 구간을 알 수 없다. */}
      <p id={headingId} className={collapsed ? 'sr-only' : TREE_GROUP_HEADING_CLASS}>
        {heading}
      </p>
      <ul aria-labelledby={headingId} className="flex flex-col gap-1">
        {projects.map((project) => (
          <ProjectTreeRow
            key={project.id}
            project={project}
            isActive={project.key === activeProjectKey}
            expanded={expandedKeys.has(project.key)}
            collapsed={collapsed}
            isFavorite={favoriteKeys.has(project.key)}
            boards={boardsByProject.get(project.key)}
            onToggle={() => { onToggle(project.key) }}
          />
        ))}
      </ul>
    </>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사이드바 스페이스 트리 — `GET /api/v1/projects` 첫 소비자(useProjects), 3그룹 × 2단 아코디언.
 *
 * - `<nav aria-label={navLabels.projectNav}>`("프로젝트", 🔒 e2e 계약. '프로젝트 뷰 전환'의
 *   substring이므로 Playwright role 조회는 항상 `exact: true` 사용 — 자세한 내용은
 *   {@link navLabels}.projectNav JSDoc 참고).
 * - nav 최상단 헤더 행 = "모든 프로젝트" 링크(→ `/projects`, G2) + `＋`(→ `/projects/new`).
 *   `＋` 는 `whoami.canCreateProject` 가 참일 때만 렌더한다 — `/projects/new` 는 권한이 없어도
 *   열리지만 그 화면이 폼 대신 거부 안내를 내므로, 못 하는 일로 가는 버튼을 두지 않는다.
 * - **프로젝트는 세 구간으로 나뉜다**(J2) — `별표 표시됨`(`useFavorites('PROJECT')`) ·
 *   `최근 방문`(`useRecentProjects`) · `추가 스페이스`(나머지). 분할 규칙의 정본은
 *   {@link partitionProjectsForTree} 이고 이 컴포넌트는 그것을 소비만 한다.
 * - 각 프로젝트 행 = 디스클로저 버튼(`aria-expanded`) + 프로젝트명 링크(→ `/projects/{key}` 요약)
 *   + 스페이스 `⋯`(별표 토글 · 프로젝트 설정).
 * - 펼침 시 **보드 목록**(J2) + 직접 링크 2(백로그·타임라인) + `리포트` 중첩그룹(4) +
 *   `프로젝트 설정` 중첩그룹(12). 보드 목록은 펼쳐진 프로젝트에 대해서만 조회한다
 *   ({@link useProjectBoards} — 최상위 `useQueries` 하나).
 *   ★한때 여기 있던 「보드」 **링크 한 줄**이 보드 목록으로 갈린 것이고, 나머지 14개 링크는
 *   그 화면들의 유일한 진입로라 그대로 남는다.
 * - **자동 펼침의 근거는 URL이 담은 프로젝트 키다** — 경로 파라미터(`/projects/$projectKey/*`)
 *   우선, 없으면 검색 파라미터(`/issues?projectKey=` · `/search?projectKey=`). 둘 다 없으면
 *   (프로젝트 컨텍스트 밖) 자동 펼침이 일어나지 않는다.
 * - **`aria-current="page"`는 경로 파라미터 일치일 때만** 부여한다. 검색 파라미터는 "이 링크가
 *   현재 페이지"를 뜻하지 않으므로(사용자는 `/issues`에 있다) 붙이면 거짓말이 된다.
 * - **★ 자동 펼침은 더하기만 한다 (FR-UX-06 PR12 FR5 정정, ADR 2026-07-30 §D2).**
 *   {@link useProjectTreeExpanded}의 `expand`가 키를 **추가만** 하고 다른 키를 제거하지 않는다.
 * - **수동 펼침(디스클로저 클릭)은 localStorage에 영속한다** — `bts.project-tree.expanded`.
 *   영속 범위는 **프로젝트 레벨**이고, 중첩그룹("리포트"·"프로젝트 설정")은 여전히
 *   {@link ProjectTreeRow} 로컬 `useState`라 영속되지 않는다(스펙 L6).
 * - 사이드바 접힘(64px 레일)이면 각 프로젝트는 아이콘(이니셜)만 노출하고 텍스트는 `sr-only`로
 *   감추며, 그룹 펼침은 비활성화된다(FR6). 펼침이 없으므로 **보드 조회도 나가지 않는다**.
 * - GAP-1(게이트1 확정) — 설정 그룹 12링크는 모든 인증 사용자에게 표시한다.
 * - 로딩=스켈레톤, 빈 배열=빈 상태 문구, 에러=`null` 반환으로 트리 자체를 렌더하지 않는다
 *   (조용한 fail-safe — 사이드바 전체를 차단하지 않는다).
 */
export function ProjectTree(): JSX.Element | null {
  // 경로 파라미터 — `aria-current="page"` 의 유일한 근거다. 사용자가 **그 페이지에 있을 때만** 참이다.
  const { projectKey: pathProjectKey } = useParams({ strict: false })
  // 검색 파라미터 — `/issues?projectKey=` · `/search?projectKey=` (FR-UX-07 이 승격한 공유 링크 형태)
  const { projectKey: searchProjectKey } = useSearch({ strict: false }) as {
    projectKey?: string
  }
  const { data: projects, isLoading, isError } = useProjects()
  // 🛑 `collapsed` 직접 읽기 금지 — 모바일 드로어는 264px 로 열리므로 레일이 아니다.
  const collapsed = useSidebarRailCollapsed()
  const { expandedKeys, toggle, expand } = useProjectTreeExpanded()
  const canCreateProject = useAuthUser()?.canCreateProject === true
  const { data: favorites } = useFavorites('PROJECT')
  const recentProjectKeys = useRecentProjects((state) => state.recentProjectKeys)

  /** 즐겨찾기 프로젝트 키 — 3그룹 분할과 스페이스 `⋯` 의 별표 상태가 같은 출처를 본다 */
  const favoriteKeys = useMemo(
    () => new Set((favorites ?? []).map((favorite) => favorite.targetId)),
    [favorites],
  )

  const partition = useMemo(
    () => partitionProjectsForTree(projects ?? [], [...favoriteKeys], recentProjectKeys),
    [projects, favoriteKeys, recentProjectKeys],
  )

  /**
   * 자동 펼침의 근거 — **URL 이 담은** 프로젝트 키(경로 우선, 없으면 검색 파라미터).
   *
   * 저장값·첫 프로젝트 폴백까지 해소하는 `useResolvedActiveProject` 를 쓰지 않는 이유 —
   * 그러면 `/dashboards` 처럼 URL 이 프로젝트를 전혀 안 담는 페이지에서도 항상 키가 나와
   * "프로젝트 컨텍스트 밖이면 전부 접힘" 계약이 깨진다 (스펙 FR7 정정단락).
   */
  const urlProjectKey = pathProjectKey ?? searchProjectKey

  /**
   * 보드를 조회할 프로젝트 — **펼쳐진 것만**이고, 거기서 다시 **상한까지만**이다.
   *
   * 접힘 레일에서는 하위 목록 자체가 없으므로 빈 배열을 넘겨 조회를 통째로 끈다. 그러지 않으면
   * 아이콘만 보이는 상태에서 보이지도 않는 보드 목록을 계속 받아 온다.
   *
   * 상한이 필요한 이유는 펼침 집합이 **영속되고 더하기만 한다**는 데 있다 — 상한 근거와
   * 활성 프로젝트 우선 규칙의 정본은 {@link pickBoardLookupKeys} 다.
   */
  const expandedProjectKeys = useMemo(() => {
    if (collapsed) return []
    const expanded = (projects ?? [])
      .filter((project) => expandedKeys.has(project.key))
      .map((project) => project.key)
    return pickBoardLookupKeys(expanded, urlProjectKey)
  }, [projects, expandedKeys, collapsed, urlProjectKey])
  const boardsByProject = useProjectBoards(expandedProjectKeys)

  // 활성 프로젝트가 바뀌면 그 키를 펼침 집합에 **더한다**. 다른 키를 제거하지 않는다.
  // FR-UX-06 PR12 의 FR5(덮어쓰기)를 정정한 것이다 — ADR 2026-07-30 §D2.
  useEffect(() => {
    if (urlProjectKey === undefined) return
    expand(urlProjectKey)
  }, [urlProjectKey, expand])

  if (isError) {
    return null
  }

  const groups: ReadonlyArray<readonly [string, readonly Project[]]> = [
    [navLabels.treeStarredGroup, partition.starred],
    [navLabels.treeRecentGroup, partition.recent],
    [navLabels.treeMoreGroup, partition.more],
  ]

  return (
    <nav aria-label={navLabels.projectNav} className="flex flex-col gap-1 p-2">
      <div className="flex items-center gap-1">
        <Link to={ALL_PROJECTS_PATH} className={ALL_PROJECTS_LINK_CLASS}>
          {ALL_PROJECTS_LABEL}
        </Link>
        {canCreateProject && (
          <Button
            asChild
            variant="ghost"
            size="icon-xs"
            aria-label={NEW_PROJECT_LABEL}
            className={DISCLOSURE_BUTTON_CLASS}
          >
            <Link to={NEW_PROJECT_PATH}>
              <Plus aria-hidden="true" className={TREE_ICON_CLASS} />
            </Link>
          </Button>
        )}
      </div>
      {isLoading ? (
        <ProjectTreeSkeleton />
      ) : projects === undefined || projects.length === 0 ? (
        <p className="px-2 py-3 text-center text-sm text-sidebar-foreground/60">{EMPTY_MESSAGE}</p>
      ) : (
        groups.map(([heading, groupProjects]) => (
          <ProjectTreeGroup
            key={heading}
            heading={heading}
            projects={groupProjects}
            activeProjectKey={pathProjectKey}
            expandedKeys={expandedKeys}
            favoriteKeys={favoriteKeys}
            boardsByProject={boardsByProject}
            collapsed={collapsed}
            onToggle={toggle}
          />
        ))
      )}
    </nav>
  )
}
