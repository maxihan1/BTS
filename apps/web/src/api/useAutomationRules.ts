// 자동화 룰 목록 조회 + 생성/수정/삭제 TanStack Query 훅 (FR-AT-01 D6) — mutation은 invalidate-only
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { UseQueryResult, UseMutationResult, QueryClient } from '@tanstack/react-query'
import {
  fetchAutomationRules,
  createAutomationRule,
  patchAutomationRule,
  deleteAutomationRule,
} from './automation-rules'
import type {
  AutomationRule,
  CreateAutomationRuleInput,
  CreateAutomationRuleResponse,
  PatchAutomationRuleInput,
} from './automation-rules.types'
import type { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 키 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 자동화 룰 목록 쿼리 키 생성 헬퍼.
 *
 * projectKey를 키에 포함해 filter-aware 캐싱을 보장한다 — 프로젝트가 바뀌면 별도 캐시
 * 엔트리를 사용한다. mutation onSuccess의 invalidateQueries도 이 헬퍼로 같은 키를
 * 재구성해 문자열 리터럴 drift를 막는다.
 *
 * @param projectKey 프로젝트 키
 * @returns TanStack Query queryKey 배열 (예: `["automation-rules", "ATLAS"]`)
 */
export const AUTOMATION_RULES_QUERY_KEY = (projectKey: string): [string, string] => [
  'automation-rules',
  projectKey,
]

// ─────────────────────────────────────────────────────────────────────────────
// Query 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 자동화 룰 목록 조회 훅.
 *
 * - queryKey: {@link AUTOMATION_RULES_QUERY_KEY}`(projectKey)`
 * - `GET /api/v1/projects/{projectKey}/automation/rules` 결과를 조회한다.
 *
 * @param projectKey 프로젝트 키
 * @returns TanStack Query `useQuery` 결과
 */
export function useAutomationRules(projectKey: string): UseQueryResult<AutomationRule[], ApiError> {
  return useQuery<AutomationRule[], ApiError>({
    queryKey: AUTOMATION_RULES_QUERY_KEY(projectKey),
    queryFn: () => fetchAutomationRules(projectKey),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// Mutation 훅 — 모든 onSuccess는 invalidate-only (setQueryData 금지)
// setQueryData로 캐시를 통째 덮으면 파생 필드 누락으로 화면 플리커 발생 (memory: mutation-setquerydata-partial-response-flicker)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 자동화 룰 목록 쿼리를 invalidate하는 공용 헬퍼.
 *
 * create/update/delete mutation 3종의 onSuccess가 동일 로직(목록 쿼리 invalidate)을
 * 공유하므로 한 곳에 모아 drift를 막는다.
 *
 * @param queryClient invalidateQueries를 호출할 QueryClient 인스턴스
 * @param projectKey 프로젝트 키
 */
function invalidateAutomationRules(queryClient: QueryClient, projectKey: string): void {
  void queryClient.invalidateQueries({ queryKey: AUTOMATION_RULES_QUERY_KEY(projectKey) })
}

/**
 * 자동화 룰 생성 mutation 훅.
 *
 * - `POST /api/v1/projects/{projectKey}/automation/rules` → 201 `{ rule, webhookToken }`
 * - onSuccess: 목록 쿼리만 invalidate(refetch 유도) — 응답을 캐시에 직접 쓰지 않는다.
 * - WEBHOOK 트리거로 생성하면 webhookToken 원문이 mutateAsync 반환값에만 담기므로,
 *   호출부가 이 반환값을 받아 1회 노출 모달로 전달한다.
 *
 * @param projectKey 프로젝트 키
 * @returns UseMutationResult — mutate(CreateAutomationRuleInput) 호출로 생성 실행
 */
export function useCreateAutomationRule(
  projectKey: string,
): UseMutationResult<CreateAutomationRuleResponse, ApiError, CreateAutomationRuleInput> {
  const queryClient = useQueryClient()

  return useMutation<CreateAutomationRuleResponse, ApiError, CreateAutomationRuleInput>({
    mutationFn: (input: CreateAutomationRuleInput) => createAutomationRule(projectKey, input),
    onSuccess: () => {
      invalidateAutomationRules(queryClient, projectKey)
    },
  })
}

/** useUpdateAutomationRule mutate 입력 타입 */
export interface UpdateAutomationRuleInput {
  /** 수정할 룰 UUID */
  id: string
  /** 수정 요청 바디 (version 포함, OCC) */
  body: PatchAutomationRuleInput
}

/**
 * 자동화 룰 수정(PATCH) mutation 훅 — name·enabled·triggerConfig.
 *
 * - `PATCH /api/v1/projects/{projectKey}/automation/rules/{id}` → 200 `AutomationRule`
 * - onSuccess: 목록 쿼리만 invalidate.
 *
 * @param projectKey 프로젝트 키
 * @returns UseMutationResult — mutate({ id, body }) 호출로 수정 실행
 */
export function useUpdateAutomationRule(
  projectKey: string,
): UseMutationResult<AutomationRule, ApiError, UpdateAutomationRuleInput> {
  const queryClient = useQueryClient()

  return useMutation<AutomationRule, ApiError, UpdateAutomationRuleInput>({
    mutationFn: ({ id, body }: UpdateAutomationRuleInput) => patchAutomationRule(projectKey, id, body),
    onSuccess: () => {
      invalidateAutomationRules(queryClient, projectKey)
    },
  })
}

/**
 * 자동화 룰 삭제(soft delete) mutation 훅.
 *
 * - `DELETE /api/v1/projects/{projectKey}/automation/rules/{id}` → 204
 * - onSuccess: 목록 쿼리만 invalidate.
 *
 * @param projectKey 프로젝트 키
 * @returns UseMutationResult — mutate(id: string) 호출로 삭제 실행
 */
export function useDeleteAutomationRule(projectKey: string): UseMutationResult<void, ApiError, string> {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, string>({
    mutationFn: (id: string) => deleteAutomationRule(projectKey, id),
    onSuccess: () => {
      invalidateAutomationRules(queryClient, projectKey)
    },
  })
}
