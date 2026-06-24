// 백로그·스프린트 조회·변경 TanStack Query 훅 (FR-BL-01/02 D6/D7)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  fetchBacklog,
  rerankIssue,
  assignToSprint,
  unassignFromSprint,
  createSprint,
  startSprint,
  completeSprint,
} from '@/api/backlog'
import type { BacklogView, IssueRankResult, SprintMeta, RerankIssueBody, CreateSprintParams } from '@/api/backlog'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그 BC queryKey 팩토리.
 *
 * 모든 mutation의 onSuccess에서 이 키를 통해 invalidate하므로
 * 드래그 순서 변경·스프린트 이동·스프린트 상태 전이 후 단일 재조회로 정합이 보장된다.
 */
export const backlogKeys = {
  /**
   * 프로젝트 백로그 전체 뷰 queryKey.
   *
   * @param projectKey 프로젝트 식별 키. 예: "ATLAS"
   */
  detail: (projectKey: string) => ['backlog', projectKey] as const,
}

// ─────────────────────────────────────────────────────────────────────────────
// useBacklog — 백로그 전체 뷰 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 백로그 전체 뷰를 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/backlog → BacklogView (미할당 이슈 + 스프린트별 이슈)
 * staleTime 30초 — 빈번한 재조회를 방지한다.
 *
 * @param projectKey 프로젝트 키. 빈 문자열이면 쿼리가 비활성화된다.
 */
export function useBacklog(projectKey: string) {
  return useQuery<BacklogView>({
    queryKey: backlogKeys.detail(projectKey),
    queryFn: () => fetchBacklog(projectKey),
    staleTime: 30_000,
    enabled: projectKey.length > 0,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useRerankIssue — 이슈 rank 변경
// ─────────────────────────────────────────────────────────────────────────────

/** useRerankIssue mutation 입력 타입 */
export interface RerankIssueInput {
  /** rank를 변경할 이슈 키. 예: "ATLAS-1" */
  issueKey: string
  /** 이웃 이슈 키 (이전·다음). 둘 다 undefined이면 400 */
  body: RerankIssueBody
}

/**
 * 이슈 rank를 변경한다 (LexoRank 드래그 앤 드롭).
 *
 * PATCH /api/v1/issues/{key}/rank → IssueRankResult
 *
 * onSuccess → invalidateQueries (invalidate-only, setQueryData 금지).
 * 드래그 후 백로그 전체를 단일 재조회해 정합을 보장한다.
 *
 * @param projectKey 백로그 queryKey 대상 프로젝트 키
 */
export function useRerankIssue(projectKey: string) {
  const queryClient = useQueryClient()

  return useMutation<IssueRankResult, unknown, RerankIssueInput>({
    mutationFn: ({ issueKey, body }) => rerankIssue(issueKey, body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: backlogKeys.detail(projectKey) })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useAssignToSprint — 이슈 → 스프린트 할당
// ─────────────────────────────────────────────────────────────────────────────

/** useAssignToSprint mutation 입력 타입 */
export interface AssignToSprintInput {
  /** 대상 스프린트 UUID */
  sprintId: string
  /** 할당할 이슈 키. 예: "ATLAS-1" */
  issueKey: string
}

/**
 * 이슈를 스프린트에 할당한다.
 *
 * POST /api/v1/sprints/{id}/issues → 201 void
 *
 * onSuccess → invalidateQueries (invalidate-only).
 *
 * @param projectKey 백로그 queryKey 대상 프로젝트 키
 */
export function useAssignToSprint(projectKey: string) {
  const queryClient = useQueryClient()

  return useMutation<void, unknown, AssignToSprintInput>({
    mutationFn: ({ sprintId, issueKey }) => assignToSprint(sprintId, issueKey),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: backlogKeys.detail(projectKey) })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useUnassignFromSprint — 스프린트 → 백로그 복귀
// ─────────────────────────────────────────────────────────────────────────────

/** useUnassignFromSprint mutation 입력 타입 */
export interface UnassignFromSprintInput {
  /** 대상 스프린트 UUID */
  sprintId: string
  /** 제거할 이슈 키. 예: "ATLAS-1" */
  issueKey: string
}

/**
 * 이슈를 스프린트에서 제거한다 (백로그로 복귀).
 *
 * DELETE /api/v1/sprints/{id}/issues/{issueKey} → 204 void
 *
 * onSuccess → invalidateQueries (invalidate-only).
 *
 * @param projectKey 백로그 queryKey 대상 프로젝트 키
 */
export function useUnassignFromSprint(projectKey: string) {
  const queryClient = useQueryClient()

  return useMutation<void, unknown, UnassignFromSprintInput>({
    mutationFn: ({ sprintId, issueKey }) => unassignFromSprint(sprintId, issueKey),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: backlogKeys.detail(projectKey) })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useCreateSprint — 스프린트 생성
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 새 스프린트를 생성한다.
 *
 * POST /api/v1/sprints → 201 SprintMeta
 *
 * onSuccess → invalidateQueries (invalidate-only).
 * 생성 후 백로그 재조회로 새 스프린트가 sprints 목록에 등장한다.
 *
 * @param projectKey 백로그 queryKey 대상 프로젝트 키
 */
export function useCreateSprint(projectKey: string) {
  const queryClient = useQueryClient()

  return useMutation<SprintMeta, unknown, CreateSprintParams>({
    mutationFn: (params) => createSprint(params),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: backlogKeys.detail(projectKey) })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useStartSprint — 스프린트 시작 (PLANNED → ACTIVE)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스프린트를 시작한다 (PLANNED → ACTIVE).
 *
 * POST /api/v1/sprints/{id}/start → SprintMeta (status: "ACTIVE")
 *
 * onSuccess → invalidateQueries (invalidate-only).
 *
 * @param projectKey 백로그 queryKey 대상 프로젝트 키
 */
export function useStartSprint(projectKey: string) {
  const queryClient = useQueryClient()

  return useMutation<SprintMeta, unknown, string>({
    mutationFn: (sprintId) => startSprint(sprintId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: backlogKeys.detail(projectKey) })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useCompleteSprint — 스프린트 완료 (ACTIVE → COMPLETED)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스프린트를 완료 처리한다 (ACTIVE → COMPLETED).
 *
 * POST /api/v1/sprints/{id}/complete → SprintMeta (status: "COMPLETED")
 *
 * onSuccess → invalidateQueries (invalidate-only).
 * 완료 후 미완성 이슈는 백엔드에서 backlog로 이동시키므로
 * 단일 재조회로 백로그 뷰 전체가 갱신된다.
 *
 * @param projectKey 백로그 queryKey 대상 프로젝트 키
 */
export function useCompleteSprint(projectKey: string) {
  const queryClient = useQueryClient()

  return useMutation<SprintMeta, unknown, string>({
    mutationFn: (sprintId) => completeSprint(sprintId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: backlogKeys.detail(projectKey) })
    },
  })
}
