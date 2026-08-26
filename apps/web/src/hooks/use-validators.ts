// 워크플로우 전환 규칙(validator) CRUD TanStack Query 훅 — 목록 조회 + 추가/수정/삭제
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createValidator, deleteValidator, fetchValidators, updateValidator } from '@/api/validators'
import type { ValidatorRequest, ValidatorResponse } from '@/api/validators'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 상수
// ─────────────────────────────────────────────────────────────────────────────

/** queryKey 팩토리 — 매직 문자열 방지 */
export const VALIDATOR_KEYS = {
  /**
   * 전환별 validator 목록 queryKey.
   *
   * @param workflowKey 워크플로우 키.
   * @param transitionId 전환 id(UUID). 종전 합성 키는 새 소비자가 쓰지 않는다(제약 C4).
   */
  list: (workflowKey: string, transitionId: string) =>
    ['validators', workflowKey, transitionId] as const,
}

// ─────────────────────────────────────────────────────────────────────────────
// Query hook
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전환의 validator 목록을 조회한다.
 * GET `/api/v1/workflows/{workflowKey}/transitions/{transitionId}/validators`
 *
 * `workflowKey` 또는 `transitionId` 가 빈 문자열이면 query 를 비활성화한다 — 전환 미선택 상태에서
 * 경로에 빈 세그먼트를 실은 요청이 나가지 않게 한다.
 *
 * 편집 불가 행도 그대로 온다 — 반쪽 목록은 관리자를 속인다(FR-2).
 *
 * @param workflowKey 워크플로우 키.
 * @param transitionId 전환 id(UUID).
 */
export function useValidators(workflowKey: string, transitionId: string) {
  return useQuery<ValidatorResponse[]>({
    queryKey: VALIDATOR_KEYS.list(workflowKey, transitionId),
    queryFn: () => fetchValidators(workflowKey, transitionId),
    staleTime: 30_000,
    enabled: !!workflowKey && !!transitionId,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// Mutation hooks
//
// ★ 셋 다 **invalidate-only** 다. 응답으로 캐시를 덮어쓰면(`setQueryData`) 목록이 서버가 준 순서·
//   파생 필드와 어긋난 채 한 프레임 그려졌다가 refetch 로 다시 바뀌어 화면이 플리커한다.
//   형제 `use-post-actions.ts` 가 같은 이유로 같은 선택을 했고 그 판단을 승계한다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전환에 validator 를 추가한다.
 * POST `/api/v1/workflows/{workflowKey}/transitions/{transitionId}/validators`
 * 성공 시 그 전환의 목록 캐시만 무효화한다.
 *
 * @param workflowKey 워크플로우 키.
 * @param transitionId 전환 id(UUID).
 */
export function useAddValidator(workflowKey: string, transitionId: string) {
  const queryClient = useQueryClient()

  return useMutation<ValidatorResponse, unknown, ValidatorRequest>({
    mutationFn: (body) => createValidator(workflowKey, transitionId, body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: VALIDATOR_KEYS.list(workflowKey, transitionId),
      })
    },
  })
}

/**
 * validator 를 수정한다.
 * PUT `/api/v1/workflows/{workflowKey}/transitions/{transitionId}/validators/{id}`
 * 성공 시 그 전환의 목록 캐시만 무효화한다.
 *
 * PUT 은 표현 전체 교체다 — 호출자는 로드한 config 를 baseline 으로 들고 아는 키만 덮어써서
 * 전체를 보낸다(제약 C6).
 *
 * @param workflowKey 워크플로우 키.
 * @param transitionId 전환 id(UUID).
 * @param id 수정할 validator UUID.
 */
export function useUpdateValidator(workflowKey: string, transitionId: string, id: string) {
  const queryClient = useQueryClient()

  return useMutation<ValidatorResponse, unknown, ValidatorRequest>({
    mutationFn: (body) => updateValidator(workflowKey, transitionId, id, body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: VALIDATOR_KEYS.list(workflowKey, transitionId),
      })
    },
  })
}

/**
 * validator 를 삭제한다. 하드 삭제다.
 * DELETE `/api/v1/workflows/{workflowKey}/transitions/{transitionId}/validators/{id}`
 * 성공 시 그 전환의 목록 캐시만 무효화한다.
 *
 * @param workflowKey 워크플로우 키.
 * @param transitionId 전환 id(UUID).
 */
export function useDeleteValidator(workflowKey: string, transitionId: string) {
  const queryClient = useQueryClient()

  return useMutation<void, unknown, string>({
    mutationFn: (id) => deleteValidator(workflowKey, transitionId, id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: VALIDATOR_KEYS.list(workflowKey, transitionId),
      })
    },
  })
}
