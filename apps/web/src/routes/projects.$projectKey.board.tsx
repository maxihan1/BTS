// 칸반 보드 라우트 — BoardRouteAdapter + BoardPage (FR-BD-01 Task 7)
import type { JSX } from 'react'
import { useMemo } from 'react'
import { useParams, useSearch, useNavigate } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { ApiError } from '@/api/client'
import { fetchUsers } from '@/api/users'
import type { BoardSummary } from '@/api/boards'
import { useBoards, useBoard } from '@/hooks/use-boards'
import { KanbanBoard } from '@/components/board/KanbanBoard'
import type { CardAssigneeDisplay } from '@/components/board/BoardCard'
import { CreateBoardForm } from '@/components/board/CreateBoardForm'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

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
// BoardRouteAdapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터.
 * useParams로 $projectKey, useSearch로 board(선택된 boardId)를 추출해 BoardPage에 전달한다.
 */
export function BoardRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  const search = useSearch({ strict: false }) as { board?: string }
  return (
    <BoardPage
      projectKey={projectKey ?? ''}
      selectedBoardId={search.board}
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
}

// ─────────────────────────────────────────────────────────────────────────────
// BoardPage 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 보드 페이지.
 *
 * - useBoards(projectKey)로 보드 목록 조회.
 *   - 로딩 → 스켈레톤.
 *   - 403 (status===403 또는 errorCode AGILE_ACCESS_DENIED) → 접근 불가 안내.
 *   - 보드 0개 → CreateBoardForm (빈 상태 주 CTA).
 *   - 보드 1개 → KanbanBoard.
 *   - 보드 2+개 → 선택 드롭다운 + KanbanBoard.
 * - selectedBoardId ?? 첫 보드를 현재 보드로 결정.
 * - useBoard(currentBoardId)로 보드 상세 조회.
 * - fetchUsers()로 전체 사용자 목록 조회 → issueKey→displayName Map 구성 (N+1 방지, best-effort).
 * - truncated/unplacedCount 경고 배너.
 *
 * @param projectKey 프로젝트 식별 키
 * @param selectedBoardId URL search에서 추출한 선택 보드 UUID
 */
export function BoardPage({ projectKey, selectedBoardId }: BoardPageProps): JSX.Element {
  const navigate = useNavigate()

  const {
    data: boards,
    isLoading: boardsLoading,
    error: boardsError,
    isError: boardsIsError,
  } = useBoards(projectKey)

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

  const { data: boardDetail, isLoading: boardDetailLoading } = useBoard(currentBoardId)

  // 전체 사용자 목록 조회 — userId → displayName|username Map 구성용
  const { data: usersRaw } = useQuery({
    queryKey: ['users'],
    queryFn: () => fetchUsers(),
    staleTime: 60_000,
    enabled: currentBoardId !== undefined,
  })

  // userId → displayName|username Map — useMemo로 usersRaw 변경 시에만 재생성
  const userMap: Map<string, string> = useMemo(() => {
    const parsed = usersRaw !== undefined ? usersArraySchema.safeParse(usersRaw) : null
    if (parsed === null || !parsed.success) return new Map()
    const map = new Map<string, string>()
    for (const u of parsed.data) {
      map.set(u.id, u.displayName ?? u.username)
    }
    return map
  }, [usersRaw])

  // issueKey → CardAssigneeDisplay Map (카드 순회, best-effort FR-7)
  // 3-상태: assigneeId=null → unassigned / userMap 해석됨 → named / 미해석 → unknown
  const assigneeNames: Map<string, CardAssigneeDisplay> = useMemo(() => {
    if (boardDetail === undefined) return new Map()
    const map = new Map<string, CardAssigneeDisplay>()
    for (const col of boardDetail.columns) {
      for (const card of col.cards) {
        if (card.assigneeId === null) {
          map.set(card.issueKey, { state: 'unassigned' })
        } else {
          const resolved = userMap.get(card.assigneeId)
          if (resolved !== undefined) {
            map.set(card.issueKey, { state: 'named', name: resolved })
          } else {
            // assigneeId는 있으나 fetchUsers 상한 초과 등으로 이름 미해석 → unknown
            map.set(card.issueKey, { state: 'unknown' })
          }
        }
      }
    }
    return map
  }, [boardDetail, userMap])

  // ── 로딩 ──────────────────────────────────────────────────────────────────

  if (boardsLoading) {
    return (
      <div className="p-6 space-y-4">
        <Skeleton className="h-8 w-48" />
        <div className="flex gap-4">
          <Skeleton className="h-64 w-64" />
          <Skeleton className="h-64 w-64" />
          <Skeleton className="h-64 w-64" />
        </div>
      </div>
    )
  }

  // ── 403 접근 거부 ─────────────────────────────────────────────────────────

  if (isAccessDenied) {
    return (
      <div className="p-8 flex flex-col items-center justify-center min-h-48 gap-4 text-center">
        <p className="text-lg font-medium">접근 권한이 없습니다</p>
        <p className="text-sm text-muted-foreground">
          해당 프로젝트의 보드에 접근할 권한이 없습니다.
        </p>
      </div>
    )
  }

  // ── 보드 0개 — CreateBoardForm ────────────────────────────────────────────

  if (boards !== undefined && boards.length === 0) {
    return <CreateBoardForm projectKey={projectKey} />
  }

  // ── 보드 1+개 ─────────────────────────────────────────────────────────────

  return (
    <div className="p-6 space-y-4">
      {/* 보드 2+개 선택 드롭다운 */}
      {boards !== undefined && boards.length >= 2 && (
        <div className="flex items-center gap-3">
          <span className="text-sm font-medium">보드</span>
          <Select
            value={currentBoardId ?? ''}
            onValueChange={(id: string) => {
              void navigate({
                to: '/projects/$projectKey/board',
                params: { projectKey },
                search: { board: id },
              })
            }}
          >
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

      {/* KanbanBoard */}
      {boardDetail !== undefined && currentBoardId !== undefined && (
        <KanbanBoard
          boardId={currentBoardId}
          board={boardDetail}
          assigneeNames={assigneeNames}
        />
      )}
    </div>
  )
}
