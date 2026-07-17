// Git 웹훅 목록 조회 + 등록/삭제 TanStack Query 훅 (FR-AT-07 PR-D) — mutation은 invalidate-only
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { UseQueryResult, UseMutationResult, QueryClient } from '@tanstack/react-query'
import { listGitWebhooks, createGitWebhook, deleteGitWebhook } from './automation-git-webhooks'
import type { CreateGitWebhookInput, CreateGitWebhookResponse, GitWebhookSummary } from './automation-git-webhooks.types'
import type { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 키 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Git 웹훅 목록 쿼리 키 생성 헬퍼.
 *
 * projectKey를 키에 포함해 filter-aware 캐싱을 보장한다 — 프로젝트가 바뀌면 별도 캐시
 * 엔트리를 사용한다. mutation onSuccess의 invalidateQueries도 이 헬퍼로 같은 키를
 * 재구성해 문자열 리터럴 drift를 막는다(useAutomationRules.ts AUTOMATION_RULES_QUERY_KEY 동형).
 *
 * @param projectKey 프로젝트 키
 * @returns TanStack Query queryKey 배열 (예: `["automation-git-webhooks", "ATLAS"]`)
 */
export const GIT_WEBHOOKS_QUERY_KEY = (projectKey: string): [string, string] => [
  'automation-git-webhooks',
  projectKey,
]

// ─────────────────────────────────────────────────────────────────────────────
// Query 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 Git 웹훅 목록 조회 훅.
 *
 * - queryKey: {@link GIT_WEBHOOKS_QUERY_KEY}`(projectKey)`
 * - `GET /api/v1/projects/{projectKey}/automation/git-webhooks` 결과를 조회한다.
 *
 * @param projectKey 프로젝트 키
 * @returns TanStack Query `useQuery` 결과
 */
export function useGitWebhooks(projectKey: string): UseQueryResult<GitWebhookSummary[], ApiError> {
  return useQuery<GitWebhookSummary[], ApiError>({
    queryKey: GIT_WEBHOOKS_QUERY_KEY(projectKey),
    queryFn: () => listGitWebhooks(projectKey),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// Mutation 훅 — 모든 onSuccess는 invalidate-only (setQueryData 금지)
//
// ## ★ NFR2 — setQueryData를 쓰지 않는 이유
// 등록 응답(CreateGitWebhookResponse = id·provider·webhookUrl·token)과 목록 항목
// (GitWebhookSummary = id·provider·createdAt·createdBy)은 필드 집합이 다르다. 등록 응답으로
// setQueryData하면 목록 캐시에 createdAt·createdBy가 없는 불완전 항목이 들어가 Zod 계약을
// 깨뜨리고 화면이 플리커한다(mutation-setquerydata-partial-response-flicker). 그래서 create·delete
// 두 mutation 모두 목록 쿼리를 invalidate만 하고, 서버 재조회로 완전한 항목을 채운다.
//
// ## ★ NFR3 — 409(OCC) 분기를 만들지 않는 이유
// GitWebhookSummary/CreateGitWebhookResponse 어느 쪽에도 version 필드가 없다(automation-rules와
// 달리 OCC 낙관적 동시성 제어 대상이 아니다) — 존재하지 않는 필드를 위한 409 분기는 도달 불가능한
// dead path이므로 만들지 않는다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Git 웹훅 목록 쿼리를 invalidate하는 공용 헬퍼.
 *
 * create/delete mutation 2종의 onSuccess가 동일 로직(목록 쿼리 invalidate)을 공유하므로
 * 한 곳에 모아 drift를 막는다(useAutomationRules.ts invalidateAutomationRules 동형).
 *
 * @param queryClient invalidateQueries를 호출할 QueryClient 인스턴스
 * @param projectKey 프로젝트 키
 */
function invalidateGitWebhooks(queryClient: QueryClient, projectKey: string): void {
  void queryClient.invalidateQueries({ queryKey: GIT_WEBHOOKS_QUERY_KEY(projectKey) })
}

/**
 * Git 웹훅 등록 mutation 훅.
 *
 * - `POST /api/v1/projects/{projectKey}/automation/git-webhooks` → 201 `CreateGitWebhookResponse`
 * - onSuccess: 목록 쿼리만 invalidate(refetch 유도) — 응답을 캐시에 직접 쓰지 않는다(NFR2).
 * - token 원문은 mutateAsync 반환값에만 담기므로, 호출부가 이 반환값을 받아 1회 노출 모달로
 *   전달한다(자체적으로 로그/저장하지 않는다).
 *
 * @param projectKey 프로젝트 키
 * @returns UseMutationResult — mutate(CreateGitWebhookInput) 호출로 등록 실행
 */
export function useCreateGitWebhook(
  projectKey: string,
): UseMutationResult<CreateGitWebhookResponse, ApiError, CreateGitWebhookInput> {
  const queryClient = useQueryClient()

  return useMutation<CreateGitWebhookResponse, ApiError, CreateGitWebhookInput>({
    mutationFn: (input: CreateGitWebhookInput) => createGitWebhook(projectKey, input),
    onSuccess: () => {
      invalidateGitWebhooks(queryClient, projectKey)
    },
  })
}

/**
 * Git 웹훅 삭제 mutation 훅.
 *
 * - `DELETE /api/v1/projects/{projectKey}/automation/git-webhooks/{id}` → 204
 * - onSuccess: 목록 쿼리만 invalidate.
 *
 * @param projectKey 프로젝트 키
 * @returns UseMutationResult — mutate(id: string) 호출로 삭제 실행
 */
export function useDeleteGitWebhook(projectKey: string): UseMutationResult<void, ApiError, string> {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, string>({
    mutationFn: (id: string) => deleteGitWebhook(projectKey, id),
    onSuccess: () => {
      invalidateGitWebhooks(queryClient, projectKey)
    },
  })
}
