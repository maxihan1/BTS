// 워크플로우 전이 post-action CRUD TanStack Query hooks
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  listPostActions,
  createPostAction,
  updatePostAction,
  deletePostAction,
} from '@/api/post-actions'
import type { PostActionRequest, PostActionResponse } from '@/api/post-actions'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 상수
// ─────────────────────────────────────────────────────────────────────────────

/** queryKey 팩토리 — 매직 문자열 방지 */
export const POST_ACTION_KEYS = {
  /**
   * 전이별 post-action 목록 queryKey.
   * @param workflowKey  워크플로우 키
   * @param transitionKey  `fromStateKey__toStateKey` 합성 키
   */
  list: (workflowKey: string, transitionKey: string) =>
    ['post-actions', workflowKey, transitionKey] as const,
}

// ─────────────────────────────────────────────────────────────────────────────
// Query hooks
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전이의 post-action 목록을 조회한다.
 * GET /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions
 *
 * workflowKey 또는 transitionKey가 빈 문자열이면 query가 비활성화된다
 * (전이 미선택 상태에서 불필요한 API 호출 방지).
 *
 * @param workflowKey  워크플로우 키
 * @param transitionKey  `fromStateKey__toStateKey` 합성 키 (호출자가 조합)
 */
export function usePostActions(workflowKey: string, transitionKey: string) {
  return useQuery<PostActionResponse[]>({
    queryKey: POST_ACTION_KEYS.list(workflowKey, transitionKey),
    queryFn: () => listPostActions(workflowKey, transitionKey),
    staleTime: 30_000,
    enabled: !!workflowKey && !!transitionKey,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// Mutation hooks
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전이에 post-action을 추가한다.
 * POST /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions
 * 성공 시 목록 캐시를 무효화한다 (invalidate-only, 플리커 회피).
 *
 * @param workflowKey  워크플로우 키
 * @param transitionKey  `fromStateKey__toStateKey` 합성 키
 */
export function useAddPostAction(workflowKey: string, transitionKey: string) {
  const queryClient = useQueryClient()

  return useMutation<PostActionResponse, unknown, PostActionRequest>({
    mutationFn: (body) => createPostAction(workflowKey, transitionKey, body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: POST_ACTION_KEYS.list(workflowKey, transitionKey),
      })
    },
  })
}

/**
 * post-action을 수정한다.
 * PUT /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions/{id}
 * 성공 시 목록 캐시를 무효화한다 (invalidate-only, 플리커 회피).
 *
 * @param workflowKey  워크플로우 키
 * @param transitionKey  `fromStateKey__toStateKey` 합성 키
 * @param id  수정할 post-action UUID
 */
export function useUpdatePostAction(workflowKey: string, transitionKey: string, id: string) {
  const queryClient = useQueryClient()

  return useMutation<PostActionResponse, unknown, PostActionRequest>({
    mutationFn: (body) => updatePostAction(workflowKey, transitionKey, id, body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: POST_ACTION_KEYS.list(workflowKey, transitionKey),
      })
    },
  })
}

/**
 * post-action을 삭제한다.
 * DELETE /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions/{id}
 * 성공 시 목록 캐시를 무효화한다 (invalidate-only, 플리커 회피).
 *
 * @param workflowKey  워크플로우 키
 * @param transitionKey  `fromStateKey__toStateKey` 합성 키
 */
export function useRemovePostAction(workflowKey: string, transitionKey: string) {
  const queryClient = useQueryClient()

  return useMutation<void, unknown, string>({
    mutationFn: (id) => deletePostAction(workflowKey, transitionKey, id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: POST_ACTION_KEYS.list(workflowKey, transitionKey),
      })
    },
  })
}
