// 칸반 보드 라우트 — BoardRouteAdapter + BoardPage (FR-BD-01 Task 7 + FR-BD-02 Task 6 + FR-BD-03 Task 6 + FR-UX-01 Task 9)
import type { FormEvent, JSX } from 'react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { MoreHorizontal, Pencil, Trash2 } from 'lucide-react'
import { useParams, useSearch, useNavigate } from '@tanstack/react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { fetchUsers } from '@/api/users'
import { buildBoardFilterQuery } from '@/api/boards'
import type { BoardSummary, BoardDetail, BoardCardFilterParams, SwimlaneField } from '@/api/boards'
import type { QuickFilter } from '@/api/board-quick-filters'
import { useBoards, useBoard, useUpdateBoardName, useDeleteBoard, boardKeys } from '@/hooks/use-boards'
import { useUpdateSwimlane } from '@/hooks/use-update-swimlane'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { useIssueTypes } from '@/hooks/use-issue-types'
import { KanbanBoard } from '@/components/board/KanbanBoard'
import type { CardAssigneeDisplay } from '@/components/board/BoardCard'
import { CreateBoardForm } from '@/components/board/CreateBoardForm'
import { BoardSelectorDropdown } from '@/components/board/BoardSelectorDropdown'
import { ActiveSprintSummary } from '@/components/board/ActiveSprintSummary'
import {
  ScrumSprintEmptyState,
  resolveScrumEmptyVariant,
} from '@/components/board/ScrumSprintEmptyState'
import { BoardFilterBar } from '@/components/board/BoardFilterBar'
import { QuickFilterChips } from '@/components/board/QuickFilterChips'
import { SwimlaneSelector } from '@/components/board/SwimlaneSelector'
import { boardFilterLabels } from '@/i18n/board-filter-labels'
import { boardLabels, boardManageErrorMessage } from '@/i18n/board-labels'
import { searchToFilter, filterToSearch, isEmptyFilter, queryStringToSearch } from '@/lib/board-filter'
import type { BoardFilterSearch } from '@/lib/board-filter'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { FavoriteButton } from '@/components/favorite/FavoriteButton'
import { CreateIssueEntryButton } from '@/components/issue/CreateIssueEntryButton'
import { CreateIssueDialog } from '@/components/issue/CreateIssueDialog'
import { issueCreateStrings } from '@/i18n/ko'
import { Skeleton } from '@/components/ui/skeleton'
import { FilteredEmptyState } from '@/components/filters/FilteredEmptyState'

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

/**
 * navigate({ search }) 호출용 검색 파라미터 객체를 조립한다.
 * boardId가 있을 때만 `board` 키를 포함하고, filterSearch(assignee/label/component)를 덧붙인다.
 * handleFilterChange/handleFilterReset/handleQuickFilterApply가 공유하는 조립 규칙 (FR-UX-01 리팩터).
 */
function buildBoardSearch(
  boardId: string | undefined,
  filterSearch: BoardFilterSearch = {},
): Record<string, unknown> {
  return {
    ...(boardId !== undefined ? { board: boardId } : {}),
    ...filterSearch,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 보드 관리 `⋯` — 이름 변경 · 삭제 (FR-BD-01-2a/2b · Jira 근거 J3·J4·J5)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드 관리 mutation 실패를 확인 창 안에 실을 문구로 옮긴다.
 *
 * 상태 코드조차 없는 실패(연결 끊김 · `useDeleteBoard` 타임아웃)는 errorCode 가 없어
 * 「응답 없음」으로 간다 — 그 경우 사용자가 할 다음 행동은 재시도이지 값 수정이 아니다
 * (`lib/move-error-message.ts` 가 세운 세 갈래와 같은 규칙).
 *
 * 코드→문구 표 자체는 `i18n/board-labels.ts` 의 공유 util 하나뿐이다. 화면마다 인라인으로
 * 만들면 키가 갈려 raw 코드가 노출된다 (PR #106).
 */
function resolveBoardActionError(err: unknown, fallback: string): string {
  if (!(err instanceof ApiError)) return boardLabels.actions.noResponse
  return boardManageErrorMessage(extractErrorCode(err.body) ?? null, fallback)
}

/**
 * 지운 보드를 뺀 나머지 중 첫 보드 (E2).
 *
 * 남는 보드가 없으면 undefined 다 — 호출부는 `board` 없는 URL 로 이동하고 화면은 기존 빈 상태
 * (E1)로 떨어진다. 그 경로에 새 코드가 없다는 것이 이 헬퍼의 요점이다.
 */
function pickNextBoardId(boards: readonly BoardSummary[], deletedBoardId: string): string | undefined {
  return boards.find((b: BoardSummary) => b.boardId !== deletedBoardId)?.boardId
}

/** 보드 이름 변경 다이얼로그 props */
interface RenameBoardDialogProps {
  /** 열림 상태 — 소비자가 쥔다 */
  open: boolean
  /** 열림 상태 변경 요청 */
  onOpenChange: (open: boolean) => void
  /** 보드가 속한 프로젝트 키 — 목록 캐시 무효화 대상 */
  projectKey: string
  /** 이름을 바꿀 보드 UUID */
  boardId: string
  /** 현재 이름 — 입력 초기값 */
  currentName: string
}

/**
 * 보드 이름 변경 다이얼로그 (J3).
 *
 * Jira 는 보드 **설정 화면**의 연필로 이름을 바꾸지만 BTS 에는 그 화면이 없어 `⋯` 메뉴에서
 * 연다 (plan 의 의도적 편차 X2).
 *
 * 실패는 창 **안**에 남기고 성공했을 때만 닫는다 — 모달 오버레이가 화면 배너를 가리므로
 * `ConfirmDialog` 가 세운 것과 같은 규칙을 쓴다. 입력 초기값을 props 로 잡으므로 부모가
 * `key` 로 재마운트해 지난 값이 남지 않게 한다.
 */
function RenameBoardDialog({
  open,
  onOpenChange,
  projectKey,
  boardId,
  currentName,
}: RenameBoardDialogProps): JSX.Element {
  const [name, setName] = useState(currentName)
  const [error, setError] = useState<string | undefined>(undefined)
  const { mutate, isPending } = useUpdateBoardName(projectKey, boardId)

  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault()
    const trimmed = name.trim()
    // 공백 이름은 백엔드가 400 으로 막는다 — 왕복하지 않고 여기서 멈춘다.
    if (trimmed === '') return

    setError(undefined)
    mutate(
      { name: trimmed },
      {
        onSuccess: () => { onOpenChange(false) },
        onError: (err: unknown) => {
          setError(resolveBoardActionError(err, boardLabels.actions.renameFailed))
        },
      },
    )
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent aria-describedby={undefined} className="max-w-md">
        <DialogHeader>
          <DialogTitle>{boardLabels.actions.renameDialogTitle}</DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-3">
          <div className="space-y-1">
            <Label htmlFor="board-rename-name">{boardLabels.actions.renameNameLabel}</Label>
            <Input
              id="board-rename-name"
              value={name}
              onChange={(e) => {
                setName(e.target.value)
                setError(undefined)
              }}
              disabled={isPending}
              aria-describedby={error !== undefined ? 'board-rename-error' : undefined}
            />
          </div>
          {error !== undefined && (
            <p
              id="board-rename-error"
              role="alert"
              className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive ring-1 ring-foreground/10"
            >
              {error}
            </p>
          )}
          <DialogFooter>
            <Button
              type="button"
              variant="ghost"
              disabled={isPending}
              onClick={() => { onOpenChange(false) }}
            >
              {boardLabels.actions.renameCancel}
            </Button>
            <Button type="submit" disabled={isPending || name.trim() === ''}>
              {boardLabels.actions.renameSubmit}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** 보드 관리 메뉴 props */
interface BoardActionsMenuProps {
  /** 보드가 속한 프로젝트 키 */
  projectKey: string
  /** 현재 보고 있는 보드 UUID */
  boardId: string
  /** 현재 보드 이름 — 트리거 이름과 삭제 확인 문구에 쓴다 */
  boardName: string
  /** 이름 변경 항목 노출 여부 — 프로젝트 CREATE 권한 */
  canRename: boolean
  /** 삭제 항목 노출 여부 — 보드 응답의 `canDelete` 가 참일 때만 */
  canDelete: boolean
  /** 삭제가 성공한 뒤 호출 — 부모가 남은 보드로 이동한다 (E2) */
  onDeleted: () => void
}

/**
 * 보드 헤더의 `⋯` 관리 메뉴 — 이름 변경 · 삭제.
 *
 * - 권한이 없는 항목은 **렌더하지 않는다**. 비활성으로 두면 「눌러도 되는 것처럼 보이는 것」이
 *   남고, 그건 J5 가 적은 Jira 동작이 아니다 (FR-BD-01-2d).
 * - 삭제 확인은 `components/ui/confirm-dialog.tsx` 를 쓴다. 그 프리미티브는 확인 뒤 **스스로
 *   닫지 않으므로** 성공했을 때만 여기서 닫고, 실패는 `error` prop 으로 창 안에 남긴다(S7).
 * - `confirming` 은 mutation 의 `isPending` 이다 — 그 동안 취소·Esc·오버레이·X 가 전부 잠긴다.
 *   갇히지 않는 근거는 `useDeleteBoard` 의 타임아웃이다.
 */
function BoardActionsMenu({
  projectKey,
  boardId,
  boardName,
  canRename,
  canDelete,
  onDeleted,
}: BoardActionsMenuProps): JSX.Element | null {
  const [renameOpen, setRenameOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleteError, setDeleteError] = useState<string | undefined>(undefined)
  const deleteMutation = useDeleteBoard(projectKey)

  function handleDeleteConfirm(): void {
    setDeleteError(undefined)
    deleteMutation.mutate(
      { boardId },
      {
        onSuccess: () => {
          setDeleteOpen(false)
          onDeleted()
        },
        onError: (err: unknown) => {
          setDeleteError(resolveBoardActionError(err, boardLabels.actions.deleteFailed))
        },
      },
    )
  }

  // 항목이 하나도 없으면 트리거 자체를 내린다 — 열면 비는 메뉴는 「권한이 없다」가 아니라
  // 「고장났다」로 읽힌다.
  if (!canRename && !canDelete) return null

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label={boardLabels.actions.triggerAriaLabel(boardName)}
          >
            <MoreHorizontal aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          {canRename && (
            <DropdownMenuItem onSelect={() => { setRenameOpen(true) }}>
              <Pencil aria-hidden="true" />
              {boardLabels.actions.renameItem}
            </DropdownMenuItem>
          )}
          {canDelete && (
            <DropdownMenuItem
              variant="destructive"
              onSelect={() => {
                // 여는 시점에 지난 실패를 지운다 — 다른 보드를 지우려고 연 창에 앞 실패가
                // 되살아나는 자리를 없앤다.
                setDeleteError(undefined)
                setDeleteOpen(true)
              }}
            >
              <Trash2 aria-hidden="true" />
              {boardLabels.actions.deleteItem}
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>

      {/* 이름이 바뀌면 입력 초기값도 새로 잡아야 한다 — props 로 state 를 초기화하는
          컴포넌트는 key 로 재마운트해야 stale 값이 남지 않는다. */}
      <RenameBoardDialog
        key={`${boardId}:${boardName}`}
        open={renameOpen}
        onOpenChange={setRenameOpen}
        projectKey={projectKey}
        boardId={boardId}
        currentName={boardName}
      />

      <ConfirmDialog
        open={deleteOpen}
        onOpenChange={(next: boolean) => {
          setDeleteOpen(next)
          if (!next) setDeleteError(undefined)
        }}
        title={boardLabels.actions.deleteDialogTitle}
        description={boardLabels.actions.deleteDialogDescription(boardName)}
        confirmLabel={boardLabels.actions.deleteConfirm}
        cancelLabel={boardLabels.actions.deleteCancel}
        onConfirm={handleDeleteConfirm}
        confirming={deleteMutation.isPending}
        error={deleteError}
        destructive
      />
    </>
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

  // ── FR-UX-01 — 활성 퀵필터 id 추적 (FR6). navigate와 co-locate (리뷰 BLOCKER-B).
  const queryClient = useQueryClient()
  const [createIssueOpen, setCreateIssueOpen] = useState(false)
  const [activeQuickFilterId, setActiveQuickFilterId] = useState<string | null>(null)

  // C3-d: boardId가 바뀌면(보드 전환) 활성 퀵필터 표시를 초기화한다 — 다른 보드의 퀵필터이므로 무효.
  const previousBoardIdRef = useRef<string | undefined>(currentBoardId)
  useEffect(() => {
    if (previousBoardIdRef.current !== currentBoardId) {
      setActiveQuickFilterId(null)
      previousBoardIdRef.current = currentBoardId
    }
  }, [currentBoardId])

  // 현재 적용된 보드 필터를 퀵필터 저장 payload용 쿼리스트링(접두 `?` 없음)으로 변환한다.
  const currentQueryString = useMemo(() => {
    const qs = buildBoardFilterQuery(stableFilter)
    return qs.startsWith('?') ? qs.slice(1) : qs
  }, [stableFilter])

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

  // 이슈 타입 목록 — 라우트에서 1회만 조회(NFR2). typeKey → IssueTypeResponse 맵으로
  // 변환해 KanbanBoard(→ BoardColumn → BoardCard)에 주입한다. 조회 실패·로딩 중이면
  // 빈 맵을 넘겨 전 카드가 typeKey 원문 fallback 경로로 렌더된다(FR6, E7·E8).
  // ★구조분해 기본값(`= []`)을 쓰지 않는다 — 사유는 BacklogBoard.tsx 의 같은 블록 주석 참조.
  // 요약. `undefined` 일 때 `= []` 가 매 렌더 새 배열이 돼 useMemo 가 무력화되고
  // BoardColumn 의 memo 가 깨진다.
  const { data: issueTypes } = useIssueTypes()
  const issueTypesByKey = useMemo(
    () => new Map((issueTypes ?? []).map((t) => [t.key, t])),
    [issueTypes],
  )

  // 필터 결과 0건 여부 — 컬럼이 있고 모든 컬럼의 카드가 0이며, 필터가 비어 있지 않은 경우 (D2)
  // columns가 빈 배열이면 every는 vacuous true → false로 처리 (필터 결과가 아닌 빈 보드)
  const isFilteredEmpty: boolean = useMemo(() => {
    if (boardDetail === undefined) return false
    if (isEmptyFilter(stableFilter)) return false
    if (boardDetail.columns.length === 0) return false
    return boardDetail.columns.every((col) => col.cards.length === 0)
  }, [boardDetail, stableFilter])

  // 스크럼 빈 상태 종류 — 칸반이면 항상 null 이라 칸반 화면에는 이 축이 없다 (E3).
  // 판정을 여기서 한 번만 하고 아래 세 분기(FilteredEmptyState · 빈 상태 · KanbanBoard)가
  // 같은 값을 본다 — 조건을 각자 다시 쓰면 두 빈 상태가 겹쳐 뜨는 자리가 생긴다.
  const scrumEmptyVariant = useMemo(
    () => resolveScrumEmptyVariant(boardDetail, isFilteredEmpty),
    [boardDetail, isFilteredEmpty],
  )

  // 활성 스프린트 — 칸반은 항상 null 이므로 `boardType` 을 다시 보지 않는다 (`activeSprintSchema` KDoc).
  const activeSprint = boardDetail?.activeSprint ?? null

  // BoardFilterBar onChange 핸들러 — filterToSearch 결과와 board를 합쳐 navigate
  // C3-b: 수동 필터 변경은 활성 퀵필터 표시를 해제한다 (더 이상 그 퀵필터의 조건과 일치한다는 보장이 없음).
  function handleFilterChange(next: BoardCardFilterParams): void {
    setActiveQuickFilterId(null)
    void navigate({
      to: '/projects/$projectKey/board',
      params: { projectKey },
      search: buildBoardSearch(currentBoardId, filterToSearch(next)),
    })
  }

  // 빈 상태 초기화 핸들러 — 빈 필터로 navigate (board만 유지). 활성 퀵필터 표시도 해제한다.
  function handleFilterReset(): void {
    setActiveQuickFilterId(null)
    void navigate({
      to: '/projects/$projectKey/board',
      params: { projectKey },
      search: buildBoardSearch(currentBoardId),
    })
  }

  /**
   * 퀵필터 칩 클릭 핸들러 (FR6).
   * QuickFilterChips가 토글 여부(활성 칩 재클릭 → null)를 판정해 전달한다.
   * null이면 필터 해제(handleFilterReset), 아니면 저장된 query를 URL search로 반영해 적용한다.
   */
  function handleQuickFilterApply(filter: QuickFilter | null): void {
    if (filter === null) {
      handleFilterReset()
      return
    }
    setActiveQuickFilterId(filter.filterId)
    void navigate({
      to: '/projects/$projectKey/board',
      params: { projectKey },
      search: buildBoardSearch(currentBoardId, queryStringToSearch(filter.query)),
    })
  }

  /**
   * 퀵필터 삭제 완료 핸들러 (C3-c).
   * 삭제된 필터가 현재 활성 상태였다면 activeQuickFilterId를 초기화한다.
   * (URL에 이미 반영된 필터 조건 자체는 건드리지 않는다 — 칩만 비활성 표시로 돌아간다.)
   */
  function handleQuickFilterDeleted(_filterId: string, wasActive: boolean): void {
    if (wasActive) {
      setActiveQuickFilterId(null)
    }
  }

  // ── 공통 헤더 — projectKey 기반 즐겨찾기 버튼 + 이슈 생성 진입점
  //    (로딩/빈 보드 분기 무관하게 항상 노출)
  //
  // 🛑 진입점을 아래 「헤더 행」(보드 2+개이거나 보드 상세가 있을 때만 렌더)에 두면
  //    보드가 없거나 로딩 중일 때 사라진다. 이 변수는 **모든 분기가 공유**한다.
  const projectFavoriteHeader = (
    <div className="flex items-center justify-between px-6 pt-4 pb-0">
      {/* 🛑 제목 없이 별 아이콘만 두지 마라 — 형제 뷰(백로그·타임라인)는 같은 자리에 h1 이 있어
          보드만 헤더가 비어 보이고, 문서당 h1 이 0개가 된다(랜드마크·스크린리더 계약). */}
      <div className="flex items-center gap-2">
        <h1 className="text-2xl font-semibold">{boardLabels.page.title}</h1>
        <FavoriteButton targetType="PROJECT" targetId={projectKey} />
      </div>
      {/* 🛑 컬럼별이 아니라 보드 1곳이다 — 생성 계약에 상태(stateKey)가 없어
          컬럼별 버튼은 「여기서 만들면 여기에 생긴다」는 지키지 못할 약속이 된다.
          근거 = ADR docs/decisions/2026-08-03-fr-ux-09-f3-create-issue-entry-points.md D-3.
          `BoardColumn.tsx` 는 이 PR 에서 한 줄도 바뀌지 않는다. */}
      <CreateIssueEntryButton
        label={boardLabels.page.createIssue}
        variant="text"
        canCreate={canCreate}
        onClick={() => { setCreateIssueOpen(true) }}
      />
      {/* 이슈 생성 모달 — 화면당 1개. 성공 후 이동은 진입 경로가 정한다(F2 D-8) —
          보드는 상단바와 같이 **제자리에 머물고 토스트로 갈 길을 남긴다**. */}
      <CreateIssueDialog
        open={createIssueOpen}
        onOpenChange={setCreateIssueOpen}
        initialProjectKey={projectKey}
        onCreated={(key) => {
          // ★목록 갱신 (FR-9). 없으면 만든 이슈가 보드에 나타나지 않는다 —
          //   백로그는 무효화를 걸고 보드는 안 거는 비대칭이 되고, 사용자는
          //   보고 있던 화면이 그대로인 채 토스트만 본다.
          //   `boardKeys.detail(id)` 는 filter 를 포함한 3요소 키의 **접두**라 필터 변형까지 함께 무효화된다.
          //   현재 필터에 걸려 안 보이는 경우는 정상이다 (스펙 E-4) — 그건 「갱신 안 함」과 다르다.
          void queryClient.invalidateQueries({ queryKey: boardKeys.detail(currentBoardId) })
          toast(`${key} ${issueCreateStrings.createdToast}`, {
            action: {
              label: issueCreateStrings.createdToastAction,
              onClick: () => { void navigate({ to: '/issues/$key', params: { key } }) },
            },
          })
        }}
      />
    </div>
  )

  // ── 로딩 ──────────────────────────────────────────────────────────────────

  if (boardsLoading) {
    return (
      <div>
        {projectFavoriteHeader}
        <div className="p-6 space-y-4">
          <Skeleton className="h-8 w-48" aria-hidden="true" />
          <div className="flex gap-4">
            <Skeleton className="h-64 w-64" aria-hidden="true" />
            <Skeleton className="h-64 w-64" aria-hidden="true" />
            <Skeleton className="h-64 w-64" aria-hidden="true" />
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
      {/* 프로젝트 즐겨찾기 + 이슈 생성 진입점 (분기 공통 헤더) */}
      {projectFavoriteHeader}

      {/* 헤더 행 — 보드 스위처 + 스윔레인 셀렉터
          ★`data-testid="board-header"` 는 e2e 스코프 전용이다. 「보드 관리, {이름}」·「보드 선택,
          현재 {이름}」의 문구 정본이 `board-labels.ts` 하나뿐이라, 사이드바 하위 목록이 보드
          목록으로 바뀌면(`ProjectTree.tsx` 가 예고한 캠페인 PR ⑨) **현재 열린 보드**에 대해
          헤더와 사이드바의 접근성 이름이 바이트 단위로 같아진다 — 앵커로는 못 가르므로 e2e 가
          이 컨테이너로 좁힌다(`e2e/fixtures/board-helpers.ts`). 접근성 트리·렌더 영향 0. */}
      {(boards !== undefined && boards.length >= 1) || (boardDetail !== undefined && canCreate) ? (
        <div data-testid="board-header" className="flex items-center gap-4 flex-wrap">
          {/* 보드 스위처 — 1개여도 상시 노출한다. N개 모델임을 드러내는 자리다 (J1) */}
          {boards !== undefined && boards.length >= 1 && (
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

          {/* 보드 `⋯` 관리 메뉴 — 이름 옆에 붙는다. 항목은 권한별로 렌더 여부가 갈린다 */}
          {boardDetail !== undefined && currentBoardId !== undefined && (
            <BoardActionsMenu
              key={currentBoardId}
              projectKey={projectKey}
              boardId={currentBoardId}
              boardName={boardDetail.name}
              canRename={canCreate}
              // ★`canDelete` 는 Zod `.optional()` 이라 undefined 가 온다. `=== true` 로만 연다 —
              //   `!== false` 로 무르면 필드가 없는 응답에서 삭제가 열린다 (fail-closed).
              canDelete={boardDetail.canDelete === true}
              onDeleted={() => {
                void navigate({
                  to: '/projects/$projectKey/board',
                  params: { projectKey },
                  search: buildBoardSearch(pickNextBoardId(boards ?? [], currentBoardId)),
                })
              }}
            />
          )}

          {/* 활성 스프린트 표기 — 칸반은 `activeSprint` 가 null 이라 아무것도 안 그린다 (FR-2 · J5) */}
          <ActiveSprintSummary activeSprint={activeSprint} />

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

      {/* QuickFilterChips — BoardFilterBar 인접 배치 (리뷰 BLOCKER-B, FR-UX-01) */}
      {boardDetail !== undefined && currentBoardId !== undefined && (
        <QuickFilterChips
          boardId={currentBoardId}
          quickFilters={boardDetail.quickFilters}
          activeQuickFilterId={activeQuickFilterId}
          canManage={canCreate}
          currentQuery={currentQueryString}
          onApply={handleQuickFilterApply}
          onFilterDeleted={handleQuickFilterDeleted}
        />
      )}

      {/* 경고 배너 — truncated */}
      {boardDetail?.truncated === true && (
        <div
          role="alert"
          className="rounded-md bg-warning/10 border border-warning px-4 py-2 text-sm text-warning-text"
        >
          표시되지 않은 이슈가 있습니다. 필터를 사용하거나 이슈 목록에서 전체를 확인하세요.
        </div>
      )}

      {/* 경고 배너 — unplacedCount */}
      {boardDetail !== undefined && boardDetail.unplacedCount > 0 && (
        <div
          role="alert"
          className="rounded-md bg-warning/10 border border-warning px-4 py-2 text-sm text-warning-text"
        >
          {boardDetail.unplacedCount}개 이슈가 컬럼에 매핑되지 않아 표시되지 않았습니다.
          프로젝트 워크플로우 스킴을 확인해 주세요.
        </div>
      )}

      {/* 보드 상세 로딩 중 */}
      {boardDetailLoading && (
        <div className="flex gap-4">
          <Skeleton className="h-64 w-64" aria-hidden="true" />
          <Skeleton className="h-64 w-64" aria-hidden="true" />
        </div>
      )}

      {/* 필터 결과 0건 빈 상태 (D2).
          ★ 스크럼 빈 상태가 잡은 자리는 비운다 — 활성 스프린트가 없으면 필터를 초기화해도
          카드가 생기지 않아 「필터 초기화」 CTA 가 거짓 안내가 된다. */}
      {boardDetail !== undefined && isFilteredEmpty && scrumEmptyVariant === null && (
        <FilteredEmptyState
          title="조건에 맞는 카드가 없습니다"
          resetLabel={boardFilterLabels.filter.reset}
          className="min-h-48"
          onReset={handleFilterReset}
        />
      )}

      {/* 스크럼 빈 상태 (FR-BD-04 FR-1 · E1·E2).
          🛑 **early-return 이 아니라 인라인 대체**다 — 위의 헤더·`⋯` 관리 메뉴·필터바를 유지한 채
          KanbanBoard 자리만 바꾼다. early-return 으로 만들면 활성 스프린트가 없는 스크럼 보드에서
          `⋯` 메뉴가 사라져 그 보드를 지울 수도 이름을 바꿀 수도 없다
          (`e2e/board-manage.spec.ts` S4·S5 가 정확히 그 경로를 밟는다). */}
      {scrumEmptyVariant !== null && currentBoardId !== undefined && (
        <ScrumSprintEmptyState
          variant={scrumEmptyVariant}
          projectKey={projectKey}
          boardId={currentBoardId}
          className="min-h-48"
        />
      )}

      {/* KanbanBoard — 필터 결과 있을 때만 */}
      {boardDetail !== undefined && currentBoardId !== undefined && !isFilteredEmpty && scrumEmptyVariant === null && (
        <KanbanBoard
          boardId={currentBoardId}
          board={boardDetail}
          assigneeNames={assigneeNames}
          filter={stableFilter}
          isFilterActive={!isEmptyFilter(stableFilter)}
          issueTypesByKey={issueTypesByKey}
        />
      )}
    </div>
  )
}
