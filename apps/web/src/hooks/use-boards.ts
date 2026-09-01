// 칸반 보드 조회·생성 TanStack Query 훅 (FR-BD-01/02)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { fetchBoards, fetchBoard, createBoard, updateBoardName, deleteBoard } from '@/api/boards'
import type { BoardCreated, BoardMeta, BoardCardFilterParams, BoardType } from '@/api/boards'

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
 * 삭제 요청을 스스로 끊는 상한 (밀리초).
 *
 * **왜 훅에 타임아웃이 필요한가.** 삭제 확인 창(`components/ui/confirm-dialog.tsx`)은
 * `confirming` 인 동안 취소·Esc·오버레이·X 를 **전부** 잠근다. 그 설계는 KDoc 이 밝히듯
 * 「파괴적 조작이고 이미 확인을 누른 뒤라 **기다림은 짧다**」를 전제한다. 네트워크가 끊겨
 * 요청이 pending 에 머물면 그 전제가 깨지고 사용자가 창에 갇힌다.
 *
 * 프리미티브를 고치지 않고 여기서 끊는다 — 프리미티브는 소비처가 여럿이고, 여기서 reject 하면
 * `isPending` 이 풀려 창이 다시 조작 가능해지며 사유는 **이미 설계된 실패 경로**(창 안 `error`)로
 * 흐른다. 새 UI 상태가 늘지 않는다.
 *
 * 값의 근거. 정상 204 응답은 수백 ms 다. 10초는 느린 회선의 정상 응답을 성급히 자르지 않으면서,
 * 사람이 「멈췄다」고 느껴 창을 강제로 벗어나려 하기 전에 조작권을 돌려주는 상한이다.
 */
export const DELETE_BOARD_TIMEOUT_MS = 10_000

/**
 * 응답을 기다리다 [DELETE_BOARD_TIMEOUT_MS] 를 넘겨 스스로 끊은 실패.
 *
 * 서버가 준 실패(`ApiError`)와 구별되는 별도 타입이다 — 소비자는 상태 코드가 없는 실패로 묶어
 * 「응답이 없다」는 안내를 고른다.
 */
export class BoardDeleteTimeoutError extends Error {
  constructor() {
    super(`보드 삭제 응답이 ${DELETE_BOARD_TIMEOUT_MS}ms 안에 오지 않았습니다`)
    this.name = 'BoardDeleteTimeoutError'
  }
}

/**
 * 삭제 요청에 [DELETE_BOARD_TIMEOUT_MS] 상한을 씌운다.
 *
 * 요청 자체를 취소하지는 않는다 — `api/boards.ts` 에 abort 배선이 없다. 서버는 지웠는데 화면만
 * 실패로 보이는 경우가 남지만, 목록을 다시 그리면 그 어긋남은 수렴한다. 창에 갇히는 쪽이 나쁘다.
 *
 * @param request 진행 중인 삭제 요청
 * @throws BoardDeleteTimeoutError 상한을 넘겼을 때
 */
async function withDeleteTimeout(request: Promise<void>): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const expiry = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => {
      reject(new BoardDeleteTimeoutError())
    }, DELETE_BOARD_TIMEOUT_MS)
  })

  try {
    await Promise.race([request, expiry])
  } finally {
    clearTimeout(timer)
  }
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
 * 요청에는 [DELETE_BOARD_TIMEOUT_MS] 상한이 걸려 있다 — 확인 창이 `isPending` 에 묶여 닫히지
 * 못하는 상태를 끊기 위해서다. 사유는 [BoardDeleteTimeoutError] 로 소비자에게 전달된다.
 *
 * @param projectKey 보드가 속한 프로젝트 키 — 목록 캐시 무효화 대상
 */
export function useDeleteBoard(projectKey: string) {
  const queryClient = useQueryClient()

  return useMutation<void, unknown, DeleteBoardInput>({
    mutationFn: ({ boardId }) => withDeleteTimeout(deleteBoard(boardId)),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: boardKeys.list(projectKey) })
    },
  })
}
