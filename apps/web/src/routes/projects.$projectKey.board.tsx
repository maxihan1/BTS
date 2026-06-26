// 칸반 보드 라우트 — BoardRouteAdapter + BoardPage (FR-BD-01 Task 7 + FR-BD-02 Task 6 + FR-BD-03 Task 6)
import type { JSX } from 'react'
import { useMemo } from 'react'
import { useParams, useSearch, useNavigate, Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { fetchUsers } from '@/api/users'
import type { BoardSummary, BoardDetail, BoardCardFilterParams, SwimlaneField } from '@/api/boards'
import { useBoards, useBoard } from '@/hooks/use-boards'
import { useUpdateSwimlane } from '@/hooks/use-update-swimlane'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { KanbanBoard } from '@/components/board/KanbanBoard'
import type { CardAssigneeDisplay } from '@/components/board/BoardCard'
import { CreateBoardForm } from '@/components/board/CreateBoardForm'
import { BoardFilterBar } from '@/components/board/BoardFilterBar'
import { SwimlaneSelector } from '@/components/board/SwimlaneSelector'
import { boardFilterLabels } from '@/i18n/board-filter-labels'
import { boardLabels } from '@/i18n/board-labels'
import { searchToFilter, filterToSearch, isEmptyFilter } from '@/lib/board-filter'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Button } from '@/components/ui/button'
import { FavoriteButton } from '@/components/favorite/FavoriteButton'

// ─────────────────────────────────────────────────────────────────────────────
// 스켈레톤 헬퍼 — shadcn Skeleton 미설치이므로 인라인 구현
// ─────────────────────────────────────────────────────────────────────────────

function Skeleton({ className }: { className?: string }): JSX.Element {
  return (
    <div
      className={`animate-pulse rounded-md bg-muted ${className ?? ''}`}
      aria-hidden="true"
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 상수
// ─────────────────────────────────────────────────────────────────────────────

const AGILE_ACCESS_DENIED = 'AGILE_ACCESS_DENIED'

/** ProblemDetail body에서 errorCode를 추출하는 헬퍼 */
function extractErrorCode(body: unknown): string | undefined {
  if (body !== null && typeof body === 'object' && 'errorCode' in body) {
    const code = (body as Record<string, unknown>)['errorCode']
    return typeof code === 'string' ? code : undefined
  }
  return undefined
}

// ─────────────────────────────────────────────────────────────────────────────
// 사용자 배열 스키마 — userSummarySchema 재사용
// ─────────────────────────────────────────────────────────────────────────────

/** userId → displayName|username 맵을 구성하는 최소 Zod 배열 스키마 */
const usersArraySchema = z.array(
  z.object({
    id: z.string(),
    username: z.string(),
    displayName: z.string().nullable(),
  }),
)

// ─────────────────────────────────────────────────────────────────────────────
// 빈 필터 상수 — 참조 안정성 보장 (useMemo 바깥 선언)
// ─────────────────────────────────────────────────────────────────────────────

/** 아무 조건도 없는 기본 필터 상수. 매 렌더마다 새 객체 생성 방지 */
const EMPTY_FILTER: BoardCardFilterParams = {
  assigneeIds: [],
  includeUnassigned: false,
  labels: [],
  componentIds: [],
}

// ─────────────────────────────────────────────────────────────────────────────
// BoardRouteAdapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터.
 * useParams로 $projectKey, useSearch로 board(선택된 boardId)와
 * assignee/label/component 필터 파라미터를 추출해 BoardPage에 전달한다.
 */
export function BoardRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  const search = useSearch({ strict: false }) as {
    board?: string
    assignee?: string | string[]
    label?: string | string[]
    component?: string | string[]
  }

  // searchToFilter는 매 렌더마다 새 객체를 반환하므로 실제 search 값이 바뀔 때만 재계산한다.
  // search.assignee / label / component를 직접 의존성으로 나열해 react-hooks/exhaustive-deps를 만족시킨다.
  const searchAssignee = search.assignee
  const searchLabel = search.label
  const searchComponent = search.component
  const filter = useMemo(
    () => searchToFilter({ assignee: searchAssignee, label: searchLabel, component: searchComponent }),
    [searchAssignee, searchLabel, searchComponent],
  )

  return (
    <BoardPage
      projectKey={projectKey ?? ''}
      selectedBoardId={search.board}
      filter={filter}
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// BoardPage Props
// ─────────────────────────────────────────────────────────────────────────────

/** BoardPage Props */
export interface BoardPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  projectKey: string
  /** URL search params에서 추출한 선택된 보드 UUID. undefined이면 첫 보드 자동 선택 */
  selectedBoardId: string | undefined
  /** URL search params에서 변환된 카드 필터. 없으면 EMPTY_FILTER */
  filter?: BoardCardFilterParams
}

// ─────────────────────────────────────────────────────────────────────────────
// 순수 헬퍼 — 컴포넌트 외부 추출 (테스트 가능, 재렌더 없이 재계산)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * usersRaw를 userId → displayName|username Map으로 변환한다.
 * 파싱 실패 또는 undefined이면 빈 Map을 반환한다.
 */
function buildUserMap(usersRaw: unknown): Map<string, string> {
  const parsed = usersRaw !== undefined ? usersArraySchema.safeParse(usersRaw) : null
  if (parsed === null || !parsed.success) return new Map()
  const map = new Map<string, string>()
  for (const u of parsed.data) {
    map.set(u.id, u.displayName ?? u.username)
  }
  return map
}

/**
 * boardDetail의 카드 목록을 순회해 issueKey → CardAssigneeDisplay Map을 구성한다.
 * 3-상태: assigneeId=null → unassigned / userMap 해석됨 → named / 미해석 → unknown
 */
function buildAssigneeNames(
  boardDetail: BoardDetail | undefined,
  userMap: Map<string, string>,
): Map<string, CardAssigneeDisplay> {
  if (boardDetail === undefined) return new Map()
  const map = new Map<string, CardAssigneeDisplay>()
  for (const col of boardDetail.columns) {
    for (const card of col.cards) {
      if (card.assigneeId === null) {
        map.set(card.issueKey, { state: 'unassigned' })
      } else {
        const resolved = userMap.get(card.assigneeId)
        map.set(card.issueKey, resolved !== undefined ? { state: 'named', name: resolved } : { state: 'unknown' })
      }
    }
  }
  return map
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 서브컴포넌트 — 재사용이 아닌 가독성 분리
// ─────────────────────────────────────────────────────────────────────────────

/** 보드 선택 드롭다운 props */
interface BoardSelectorProps {
  boards: BoardSummary[]
  currentBoardId: string | undefined
  projectKey: string
  onSelect: (id: string) => void
}

/** 보드 2+개일 때 렌더하는 선택 드롭다운 서브컴포넌트 */
function BoardSelectorDropdown({ boards, currentBoardId, onSelect }: BoardSelectorProps): JSX.Element {
  return (
    <div className="flex items-center gap-3">
      <span className="text-sm font-medium">보드</span>
      <Select value={currentBoardId ?? ''} onValueChange={onSelect}>
        <SelectTrigger className="w-64">
          <SelectValue placeholder="보드 선택" />
        </SelectTrigger>
        <SelectContent>
          {boards.map((b: BoardSummary) => (
            <SelectItem key={b.boardId} value={b.boardId}>
              {b.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}

/** 필터 결과 0건 빈 상태 서브컴포넌트 (D2) props */
interface FilteredEmptyStateProps {
  onReset: () => void
}

/** 필터 결과 0건 빈 상태 — 안내 + 초기화 CTA (D2) */
function FilteredEmptyState({ onReset }: FilteredEmptyStateProps): JSX.Element {
  return (
    <div className="flex flex-col items-center justify-center min-h-48 gap-3 text-center">
      <p className="text-sm text-muted-foreground">조건에 맞는 카드가 없습니다</p>
      <Button type="button" variant="outline" size="sm" onClick={onReset}>
        {boardFilterLabels.filter.reset}
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// BoardPage 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 보드 페이지.
 *
 * - useBoards(projectKey)로 보드 목록 조회.
 * - useBoard(currentBoardId, filter)로 보드 상세 조회 (필터 적용).
 * - BoardFilterBar: 보드 상세 있을 때만 (EC7). onChange → navigate로 URL search 갱신.
 * - 필터 결과 0건(모든 컬럼 카드 0) → FilteredEmptyState 빈 상태 + 초기화 CTA (D2).
 * - truncated/unplacedCount 경고 배너.
 *
 * @param projectKey 프로젝트 식별 키
 * @param selectedBoardId URL search에서 추출한 선택 보드 UUID
 * @param filter URL search에서 변환된 카드 필터
 */
export function BoardPage({ projectKey, selectedBoardId, filter }: BoardPageProps): JSX.Element {
  const navigate = useNavigate()

  // ★ stableFilter — filter prop을 메모이즈해 매 렌더 새 객체로 queryKey가 흔들리는 것을 방지한다.
  // BoardRouteAdapter에서 이미 useMemo로 안정화된 filter를 전달하므로, 여기서는 null 폴백만 담당.
  const stableFilter = useMemo<BoardCardFilterParams>(
    () => filter ?? EMPTY_FILTER,
    [filter],
  )

  const {
    data: boards,
    isLoading: boardsLoading,
    error: boardsError,
    isError: boardsIsError,
  } = useBoards(projectKey)

  // 현재 사용자 프로젝트 권한 조회 — CREATE 권한으로 SwimlaneSelector 게이팅
  const { data: projectPermissions } = useProjectPermissions(projectKey)

  // 403 접근 거부 판정 — status 또는 errorCode 기준
  const isAccessDenied =
    boardsIsError &&
    boardsError instanceof ApiError &&
    (boardsError.status === 403 || extractErrorCode(boardsError.body) === AGILE_ACCESS_DENIED)

  // 현재 선택 보드 결정
  const currentBoardId: string | undefined = (() => {
    if (boards === undefined || boards.length === 0) return undefined
    if (selectedBoardId !== undefined) {
      const found = boards.find((b: BoardSummary) => b.boardId === selectedBoardId)
      if (found !== undefined) return found.boardId
    }
    return boards[0]?.boardId
  })()

  const { data: boardDetail, isLoading: boardDetailLoading } = useBoard(currentBoardId, stableFilter)

  // 스윔레인 업데이트 mutation — currentBoardId가 확정된 시점에만 유효
  const { mutate: updateSwimlane } = useUpdateSwimlane(currentBoardId ?? '')

  // CREATE 권한 여부 — undefined이면 false-safe (로딩 중에는 셀렉터 미노출)
  const canCreate: boolean = projectPermissions?.permissions.CREATE === true

  /**
   * SwimlaneSelector onChange 핸들러.
   * PATCH 요청 후 invalidate-only (setQueryData 캐시 덮기 금지 — mutation 부분응답 플리커 방지).
   * onError 시 toast.error + 자동 서버값 수렴 (invalidate).
   */
  function handleSwimlaneChange(field: SwimlaneField): void {
    if (currentBoardId === undefined) return
    updateSwimlane(field, {
      onError: () => {
        toast.error(boardLabels.swimlane.updateError)
      },
    })
  }

  // 전체 사용자 목록 조회 — userId → displayName|username Map 구성용
  const { data: usersRaw } = useQuery({
    queryKey: ['users'],
    queryFn: () => fetchUsers(),
    staleTime: 60_000,
    enabled: currentBoardId !== undefined,
  })

  // userId → displayName|username Map (best-effort, N+1 방지)
  const userMap: Map<string, string> = useMemo(() => buildUserMap(usersRaw), [usersRaw])

  // issueKey → CardAssigneeDisplay Map (3-상태: unassigned / named / unknown)
  const assigneeNames: Map<string, CardAssigneeDisplay> = useMemo(
    () => buildAssigneeNames(boardDetail, userMap),
    [boardDetail, userMap],
  )

  // 필터 결과 0건 여부 — 컬럼이 있고 모든 컬럼의 카드가 0이며, 필터가 비어 있지 않은 경우 (D2)
  // columns가 빈 배열이면 every는 vacuous true → false로 처리 (필터 결과가 아닌 빈 보드)
  const isFilteredEmpty: boolean = useMemo(() => {
    if (boardDetail === undefined) return false
    if (isEmptyFilter(stableFilter)) return false
    if (boardDetail.columns.length === 0) return false
    return boardDetail.columns.every((col) => col.cards.length === 0)
  }, [boardDetail, stableFilter])

  // BoardFilterBar onChange 핸들러 — filterToSearch 결과와 board를 합쳐 navigate
  function handleFilterChange(next: BoardCardFilterParams): void {
    const filterSearch = filterToSearch(next)
    void navigate({
      to: '/projects/$projectKey/board',
      params: { projectKey },
      search: {
        ...(currentBoardId !== undefined ? { board: currentBoardId } : {}),
        ...filterSearch,
      },
    })
  }

  // 빈 상태 초기화 핸들러 — 빈 필터로 navigate (board만 유지)
  function handleFilterReset(): void {
    void navigate({
      to: '/projects/$projectKey/board',
      params: { projectKey },
      search: {
        ...(currentBoardId !== undefined ? { board: currentBoardId } : {}),
      },
    })
  }

  // ── 공통 헤더 — projectKey 기반 즐겨찾기 버튼 (로딩/빈 보드 분기 무관하게 항상 노출)
  const projectFavoriteHeader = (
    <div className="flex items-center justify-between px-6 pt-4 pb-0">
      <FavoriteButton targetType="PROJECT" targetId={projectKey} />
    </div>
  )

  // ── 로딩 ──────────────────────────────────────────────────────────────────

  if (boardsLoading) {
    return (
      <div>
        {projectFavoriteHeader}
        <div className="p-6 space-y-4">
          <Skeleton className="h-8 w-48" />
          <div className="flex gap-4">
            <Skeleton className="h-64 w-64" />
            <Skeleton className="h-64 w-64" />
            <Skeleton className="h-64 w-64" />
          </div>
        </div>
      </div>
    )
  }

  // ── 403 접근 거부 ─────────────────────────────────────────────────────────

  if (isAccessDenied) {
    return (
      <div>
        {projectFavoriteHeader}
        <div className="p-8 flex flex-col items-center justify-center min-h-48 gap-4 text-center">
          <p className="text-lg font-medium">접근 권한이 없습니다</p>
          <p className="text-sm text-muted-foreground">
            해당 프로젝트의 보드에 접근할 권한이 없습니다.
          </p>
        </div>
      </div>
    )
  }

  // ── 보드 0개 — CreateBoardForm ────────────────────────────────────────────

  if (boards !== undefined && boards.length === 0) {
    return (
      <div>
        {projectFavoriteHeader}
        <CreateBoardForm projectKey={projectKey} />
      </div>
    )
  }

  // ── 보드 1+개 ─────────────────────────────────────────────────────────────

  return (
    <div className="p-6 space-y-4">
      {/* 프로젝트 즐겨찾기 버튼 */}
      <div className="flex items-center">
        <FavoriteButton targetType="PROJECT" targetId={projectKey} />
      </div>

      {/* 뷰 전환 nav — 백로그·타임라인 */}
      <nav aria-label="프로젝트 뷰 전환" className="flex items-center gap-3">
        <Link
          to="/projects/$projectKey/backlog"
          params={{ projectKey }}
          className="text-sm text-muted-foreground hover:text-foreground"
        >
          {boardLabels.page.backlogLink}
        </Link>
        <Link
          to="/projects/$projectKey/timeline"
          params={{ projectKey }}
          className="text-sm text-muted-foreground hover:text-foreground"
        >
          {boardLabels.page.timelineLink}
        </Link>
      </nav>

      {/* 헤더 행 — 보드 선택 드롭다운 + 스윔레인 셀렉터 */}
      {(boards !== undefined && boards.length >= 2) || (boardDetail !== undefined && canCreate) ? (
        <div className="flex items-center gap-4 flex-wrap">
          {/* 보드 2+개 선택 드롭다운 */}
          {boards !== undefined && boards.length >= 2 && (
            <BoardSelectorDropdown
              boards={boards}
              currentBoardId={currentBoardId}
              projectKey={projectKey}
              onSelect={(id) => {
                void navigate({
                  to: '/projects/$projectKey/board',
                  params: { projectKey },
                  search: { board: id },
                })
              }}
            />
          )}

          {/* 스윔레인 셀렉터 — 보드 상세 있고 CREATE 권한 있을 때만 */}
          {boardDetail !== undefined && canCreate && (
            <SwimlaneSelector
              value={boardDetail.swimlaneField}
              onChange={handleSwimlaneChange}
            />
          )}
        </div>
      ) : null}

      {/* BoardFilterBar — 보드 상세가 있을 때만 (EC7) */}
      {boardDetail !== undefined && (
        <BoardFilterBar
          projectKey={projectKey}
          value={stableFilter}
          onChange={handleFilterChange}
        />
      )}

      {/* 경고 배너 — truncated */}
      {boardDetail?.truncated === true && (
        <div
          role="alert"
          className="rounded-md bg-amber-50 border border-amber-200 px-4 py-2 text-sm text-amber-800"
        >
          표시되지 않은 이슈가 있습니다. 필터를 사용하거나 이슈 목록에서 전체를 확인하세요.
        </div>
      )}

      {/* 경고 배너 — unplacedCount */}
      {boardDetail !== undefined && boardDetail.unplacedCount > 0 && (
        <div
          role="alert"
          className="rounded-md bg-amber-50 border border-amber-200 px-4 py-2 text-sm text-amber-800"
        >
          {boardDetail.unplacedCount}개 이슈가 컬럼에 매핑되지 않아 표시되지 않았습니다.
          프로젝트 워크플로우 스킴을 확인해 주세요.
        </div>
      )}

      {/* 보드 상세 로딩 중 */}
      {boardDetailLoading && (
        <div className="flex gap-4">
          <Skeleton className="h-64 w-64" />
          <Skeleton className="h-64 w-64" />
        </div>
      )}

      {/* 필터 결과 0건 빈 상태 (D2) */}
      {boardDetail !== undefined && isFilteredEmpty && (
        <FilteredEmptyState onReset={handleFilterReset} />
      )}

      {/* KanbanBoard — 필터 결과 있을 때만 */}
      {boardDetail !== undefined && currentBoardId !== undefined && !isFilteredEmpty && (
        <KanbanBoard
          boardId={currentBoardId}
          board={boardDetail}
          assigneeNames={assigneeNames}
          filter={stableFilter}
          isFilterActive={!isEmptyFilter(stableFilter)}
        />
      )}
    </div>
  )
}
