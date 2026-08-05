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
  updateSprint,
} from '@/api/backlog'
import type {
  BacklogView,
  IssueRankResult,
  SprintMeta,
  RerankIssueBody,
  CreateSprintParams,
  UpdateSprintBody,
} from '@/api/backlog'

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
// useUpdateSprint — 스프린트 메타 수정 (이름·목표·기간)
// ─────────────────────────────────────────────────────────────────────────────

/** useUpdateSprint mutation 입력 타입 */
export interface UpdateSprintInput {
  /** 대상 스프린트 UUID */
  sprintId: string
  /** 바뀐 필드 + version (3-state partial) */
  body: UpdateSprintBody
}

/**
 * 스프린트의 이름·목표·기간을 수정한다.
 *
 * PATCH /api/v1/sprints/{id} → SprintMeta (version +1)
 *
 * onSuccess → invalidateQueries (invalidate-only, setQueryData 금지).
 *
 * ### 호출자가 알아야 할 것 — 응답을 버리지 말 것
 * 시작 다이얼로그는 `PATCH` → `start` 2단계로 동작하고, 중간 실패가 정상 경로다
 * (FR-UX-13 F15 FR-4). 재시도가 **낡은 `version` 으로 409** 를 받지 않으려면 호출자가
 * 이 mutation 의 **응답 SprintMeta 로 자기 기준값과 `version` 을 갱신**해야 한다.
 * 여기서 invalidate 를 하더라도 재조회는 비동기라 그 사이의 재시도를 막아주지 못한다.
 *
 * @param projectKey 백로그 queryKey 대상 프로젝트 키
 */
export function useUpdateSprint(projectKey: string) {
  const queryClient = useQueryClient()

  return useMutation<SprintMeta, unknown, UpdateSprintInput>({
    mutationFn: ({ sprintId, body }) => updateSprint(sprintId, body),
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
 *
 * ### ⚠️ 완료는 이슈를 옮기지 않는다 — 되돌릴 수도 없다
 * 이 자리에 있던 「완료 후 미완성 이슈는 백엔드에서 backlog로 이동시키므로」라는 주석은
 * **거짓이었다** (FR-UX-13 F15 착수 전 실측). `SprintApplicationService.kt:282-290` 은
 * 스프린트 **상태만 뒤집고** 이슈는 그대로 둔다.
 *
 * 그리고 COMPLETED 스프린트에 남은 이슈는 **영구 동결된다** — `unassignIssue` 가
 * `STATUS <> 'COMPLETED'` 조건부 DELETE 라 조용히 204 만 주고(실패가 아니라 침묵),
 * `sprint_issues` 의 `UNIQUE (issue_key)` 때문에 다른 스프린트로도 못 옮긴다.
 * 그래서 **미완료 이슈 이관은 반드시 완료보다 먼저** 끝나야 한다 (ADR C1 · 스펙 FR-6).
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
