// 칸반 보드 조회·생성 TanStack Query 훅 (FR-BD-01/02)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { fetchBoards, fetchBoard, createBoard, updateBoardName, deleteBoard } from '@/api/boards'
import type { BoardCreated, BoardMeta, BoardCardFilterParams, BoardType } from '@/api/boards'
import { withDeleteTimeout } from '@/lib/delete-timeout'

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
  /**
   * 모든 보드 상세를 덮는 접두 키 — **무효화 전용**.
   *
   * 🛑 조회에 쓰지 마라. 이 키로 `getQueryData` 를 부르면 어느 보드의 캐시와도 완전 일치하지
   * 않아 항상 `undefined` 다 (`backlogKeys.project` 와 같은 규약).
   *
   * ### 왜 보드 단위로 좁히지 않는가
   * 보드 내용을 바꾸는 쪽이 **어느 보드인지 모를 때**가 있다 — 스프린트 시작·완료는 스프린트
   * UUID 만 쥐고 있고 그 스프린트의 소속 보드는 응답에 없다(`SprintMeta`). 좁히려면 훅이
   * 보드를 추측해야 하고, 추측이 틀리면 무효화가 조용히 빗나간다. 접두 무효화의 비용은
   * 지금 열려 있는 보드 화면 하나를 다시 부르는 것뿐이다.
   *
   * {@link list} 는 이 접두에 **안 걸린다** — `'boards'` 와 `'board'` 는 다른 원소다.
   * 보드의 이름·종류는 스프린트 전환으로 바뀌지 않으니 그게 맞다.
   */
  all: ['board'] as const,

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
  /**
   * 생성할 보드의 종류 (FR-BD-04). **생략 불가 — 화면의 선택이 곧 계약이다.**
   *
   * `boardType?:` 로 두면 안 되는 이유. 백엔드 `BoardCreateRequest.boardType` 은 선택 인자라
   * 생략하면 KANBAN 으로 채워 **201 로 성공한다**(#421). 즉 호출부가 종류를 빠뜨려도 요청은
   * 통과하고, 스크럼을 고른 사용자만 칸반 보드를 받는다 — 실패가 아니라 **조용한 오생성**이다.
   * 필수로 두면 그 누락을 컴파일이 잡는다.
   */
  boardType: BoardType
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
    mutationFn: ({ name, boardType }) => createBoard(projectKey, name, boardType),
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
 * 삭제 상한의 보드 전용 별칭 — 정본은 `lib/delete-timeout.ts` 하나다.
 *
 * 값도 클래스도 **같은 것**이라 `instanceof BoardDeleteTimeoutError` 는 스프린트 삭제가 던진
 * 실패도 그대로 잡는다. 사본을 두면 두 상한이 갈리므로 별칭만 남긴다.
 */
export { DELETE_TIMEOUT_MS as DELETE_BOARD_TIMEOUT_MS, DeleteTimeoutError as BoardDeleteTimeoutError } from '@/lib/delete-timeout'

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
 * 요청에는 [DELETE_BOARD_TIMEOUT_MS] 상한이 걸려 있고, 상한을 넘기면 **요청 자체가 취소된다** —
 * 확인 창이 `isPending` 에 묶여 닫히지 못하는 상태를 끊고, 화면만 실패로 돌아선 채 서버는 계속
 * 지우는 어긋남도 남기지 않는다. 사유는 [BoardDeleteTimeoutError] 로 소비자에게 전달된다.
 *
 * @param projectKey 보드가 속한 프로젝트 키 — 목록 캐시 무효화 대상
 */
export function useDeleteBoard(projectKey: string) {
  const queryClient = useQueryClient()

  return useMutation<void, unknown, DeleteBoardInput>({
    mutationFn: ({ boardId }) => withDeleteTimeout((signal) => deleteBoard(boardId, signal)),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: boardKeys.list(projectKey) })
    },
  })
}
