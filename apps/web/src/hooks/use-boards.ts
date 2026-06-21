// 칸반 보드 조회·생성 TanStack Query 훅 (FR-BD-01)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { fetchBoards, fetchBoard, createBoard } from '@/api/boards'
import type { BoardCreated } from '@/api/boards'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/** 보드 BC queryKey 팩토리 */
export const boardKeys = {
  /** 프로젝트별 보드 목록 queryKey */
  list: (projectKey: string) => ['boards', projectKey] as const,
  /** 보드 단건 상세 queryKey */
  detail: (boardId: string | undefined) => ['board', boardId] as const,
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
 * GET /api/v1/boards/{boardId} → BoardDetail (컬럼·카드 포함)
 * staleTime 30초.
 *
 * @param boardId 보드 UUID. undefined이면 쿼리가 비활성화된다.
 */
export function useBoard(boardId: string | undefined) {
  return useQuery({
    queryKey: boardKeys.detail(boardId),
    queryFn: () => {
      if (!boardId) {
        throw new Error('boardId is required')
      }
      return fetchBoard(boardId)
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
