// 칸반 보드 조회·생성 TanStack Query 훅 (FR-BD-01/02)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { fetchBoards, fetchBoard, createBoard, updateBoardName, deleteBoard } from '@/api/boards'
import type { BoardCreated, BoardMeta, BoardCardFilterParams } from '@/api/boards'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — queryKey 정규화
// ─────────────────────────────────────────────────────────────────────────────

/**
 * BoardCardFilterParams를 결정적(안정적)으로 정규화한다.
 *
 * 배열 내 요소 순서가 달라도 동일한 filter를 동일한 queryKey로 취급하도록
 * 각 배열을 정렬해 객체를 재구성한다.
 * useMoveCard 등 낙관적 업데이트에서 같은 캐시를 참조할 때도 동일 키를 보장한다.
 *
 * @param filter 원본 필터 파라미터
 * @returns 정렬 정규화된 필터 객체 (JSON.stringify 안정적)
 */
function normalizeFilter(filter: BoardCardFilterParams): {
  readonly assigneeIds: readonly string[]
  readonly includeUnassigned: boolean
  readonly labels: readonly string[]
  readonly componentIds: readonly string[]
} {
  return {
    assigneeIds: [...filter.assigneeIds].sort(),
    includeUnassigned: filter.includeUnassigned,
    labels: [...filter.labels].sort(),
    componentIds: [...filter.componentIds].sort(),
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/** 보드 BC queryKey 팩토리 */
export const boardKeys = {
  /** 프로젝트별 보드 목록 queryKey */
  list: (projectKey: string) => ['boards', projectKey] as const,
  /**
   * 보드 단건 상세 queryKey.
   *
   * filter 없으면 `['board', boardId]` (2요소).
   * filter 있으면 `['board', boardId, normalizedFilter]` (3요소).
   *
   * 기존 useBoard(id) 호출 호환, filter-aware queryKey는 useMoveCard와 공유돼
   * 드래그 낙관적 업데이트 정합에 활용된다.
   */
  detail: (
    boardId: string | undefined,
    filter?: BoardCardFilterParams,
  ) =>
    filter !== undefined
      ? (['board', boardId, normalizeFilter(filter)] as const)
      : (['board', boardId] as const),
}

// ─────────────────────────────────────────────────────────────────────────────
// useBoards — 프로젝트 보드 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트에 속한 보드 목록을 조회한다.
 *
 * GET /api/v1/boards?projectKey={projectKey} → BoardSummary[]
 * staleTime 30초 — 빈번한 목록 재조회를 방지한다.
 *
 * @param projectKey 프로젝트 식별 키. 빈 문자열이면 쿼리가 비활성화된다.
 */
export function useBoards(projectKey: string) {
  return useQuery({
    queryKey: boardKeys.list(projectKey),
    queryFn: () => fetchBoards(projectKey),
    staleTime: 30_000,
    enabled: projectKey.length > 0,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useBoard — 보드 단건 상세 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드 상세 정보를 조회한다.
 *
 * GET /api/v1/boards/{boardId}[?assignee=...&label=...&component=...] → BoardDetail (컬럼·카드 포함)
 * staleTime 30초.
 *
 * 기존 useBoard(id) 호출과 호환된다 (filter 옵셔널).
 * filter-aware queryKey는 useMoveCard와 공유돼 드래그 낙관적 업데이트 정합에 활용된다.
 *
 * @param boardId 보드 UUID. undefined이면 쿼리가 비활성화된다.
 * @param filter 선택적 카드 필터 파라미터 (FR-BD-02). 없으면 전체 카드 반환.
 */
export function useBoard(boardId: string | undefined, filter?: BoardCardFilterParams) {
  return useQuery({
    queryKey: boardKeys.detail(boardId, filter),
    queryFn: () => {
      if (!boardId) {
        throw new Error('boardId is required')
      }
      return fetchBoard(boardId, filter)
    },
    staleTime: 30_000,
    enabled: boardId !== undefined && boardId.length > 0,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useCreateBoard — 보드 생성
// ─────────────────────────────────────────────────────────────────────────────

/** useCreateBoard mutation 입력 타입 */
export interface CreateBoardInput {
  /** 생성할 보드 이름 */
  name: string
}

/**
 * 새 보드를 생성한다.
 *
 * POST /api/v1/boards → 201 BoardCreated
 *
 * onSuccess → invalidateQueries (invalidate-only, setQueryData 금지)
 *
 * @param projectKey 보드를 추가할 프로젝트 키
 */
export function useCreateBoard(projectKey: string) {
  const queryClient = useQueryClient()

  return useMutation<BoardCreated, unknown, CreateBoardInput>({
    mutationFn: ({ name }) => createBoard(projectKey, name),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: boardKeys.list(projectKey) })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateBoardName — 보드 이름 변경 (FR-BD-01-2a)
// ─────────────────────────────────────────────────────────────────────────────

/** useUpdateBoardName mutation 입력 타입 */
export interface UpdateBoardNameInput {
  /** 새 보드 이름 */
  name: string
}

/**
 * 보드 이름을 변경한다.
 *
 * PATCH /api/v1/boards/{boardId} body `{ name }` → 200 BoardMeta
 *
 * onSuccess → **invalidate-only**. 목록 키와 상세 키를 무효화할 뿐 `setQueryData` 로 캐시를
 * 덮지 않는다. PATCH 응답 `BoardMeta` 에는 columns·cards·quickFilters 가 없어서, 그 부분 응답으로
 * 상세 캐시를 덮으면 보드 본문이 순간 비어 보이는 플리커가 난다 (learnings 2026-05-30).
 *
 * 상세 키는 필터 유무에 따라 2요소/3요소로 갈리는데, `invalidateQueries` 는 접두사 매칭이라
 * `['board', boardId]` 하나로 필터가 걸린 변종까지 함께 무효화된다.
 *
 * @param projectKey 보드가 속한 프로젝트 키 — 목록 캐시 무효화 대상
 * @param boardId 이름을 바꿀 보드 UUID
 */
export function useUpdateBoardName(projectKey: string, boardId: string) {
  const queryClient = useQueryClient()

  return useMutation<BoardMeta, unknown, UpdateBoardNameInput>({
    mutationFn: ({ name }) => updateBoardName(boardId, name),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: boardKeys.list(projectKey) })
      await queryClient.invalidateQueries({ queryKey: boardKeys.detail(boardId) })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteBoard — 보드 소프트 삭제 (FR-BD-01-2b)
// ─────────────────────────────────────────────────────────────────────────────

/** useDeleteBoard mutation 입력 타입 */
export interface DeleteBoardInput {
  /** 삭제할 보드 UUID */
  boardId: string
}

/**
 * 보드를 소프트 삭제한다. 보드에 있던 이슈는 남는다.
 *
 * DELETE /api/v1/boards/{boardId} → 204 No Content
 *
 * onSuccess → **목록 키만 invalidate**. 삭제된 보드의 상세 키는 건드리지 않는다 —
 * 무효화하거나 제거하면 아직 마운트돼 있는 `useBoard(boardId)` 관찰자가 곧바로 재조회를 일으켜
 * 404 를 받는다. 호출부는 삭제 성공 직후 남은 보드로 이동하므로(E2) 그 캐시는 관찰자가 사라진 뒤
 * 가비지 컬렉션으로 정리된다.
 *
 * @param projectKey 보드가 속한 프로젝트 키 — 목록 캐시 무효화 대상
 */
export function useDeleteBoard(projectKey: string) {
  const queryClient = useQueryClient()

  return useMutation<void, unknown, DeleteBoardInput>({
    mutationFn: ({ boardId }) => deleteBoard(boardId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: boardKeys.list(projectKey) })
    },
  })
}
