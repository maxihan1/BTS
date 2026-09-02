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
  deleteSprint,
} from '@/api/backlog'
import type {
  BacklogView,
  IssueRankResult,
  SprintMeta,
  RerankIssueBody,
  CreateSprintParams,
  UpdateSprintBody,
} from '@/api/backlog'
import { withDeleteTimeout } from '@/lib/delete-timeout'
import { boardKeys } from './use-boards'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그 BC queryKey 팩토리.
 *
 * 모든 mutation의 onSuccess에서 이 키를 통해 invalidate하므로
 * 드래그 순서 변경·스프린트 이동·스프린트 상태 전환 후 단일 재조회로 정합이 보장된다.
 *
 * ### 읽기는 {@link detail}, 무효화는 {@link project} 다 (FR-BD-04)
 * 백로그가 보드 단위로 갈리면서(J14) 키에 board 축이 붙었다. 두 갈래를 나눈 이유가 다르다.
 * - **읽기**(`useQuery`·`getQueryData`·`fetchQuery`)는 **완전 일치**라 보드를 빠뜨리면 안 된다.
 * - **무효화**는 접두 매칭이고, **일부러 프로젝트 전체를 덮는다** — 스프린트에서 뺀 이슈는
 *   그 보드뿐 아니라 **모든** 보드의 백로그 칸에 나타나므로(E12 · J20) 한 보드의 변경이
 *   다른 보드의 화면을 바꾼다.
 */
export const backlogKeys = {
  /**
   * 보드 스코프 백로그 뷰 queryKey — **읽기용**.
   *
   * ### `boardId` 를 필수 인자로 둔 이유
   * 옵셔널이면 board 축을 빠뜨린 호출이 **타입 에러 없이** 통과한다. 그 결과가 조용하다는
   * 것이 문제다 — `getQueryData` 는 키가 어긋나면 에러 대신 **`undefined`** 를 돌려주고
   * (`StartSprintDialog` 의 409 복구가 무음으로 멈춘다), `fetchQuery` 는 캐시 미스로 보고
   * **기본 보드에 네트워크를 태운다**(`CompleteSprintDialog` 가 다른 보드 기준으로 완료를
   * 판정한다). 둘 다 실패가 아니라 오판이라 어떤 가드에도 안 걸린다.
   * 그래서 값은 `undefined`(= 서버 기본 보드 폴백)를 허용하되 **인자는 필수**로 둔다.
   *
   * @param projectKey 프로젝트 식별 키. 예: "ATLAS"
   * @param boardId 보고 있는 보드 UUID. `?board=` 미지정이면 `undefined`
   */
  detail: (projectKey: string, boardId: string | undefined) =>
    ['backlog', projectKey, boardId] as const,

  /**
   * 프로젝트의 **모든 보드** 백로그를 덮는 접두 키 — **무효화 전용**.
   *
   * 🛑 조회에 쓰지 마라. 이 키로 `getQueryData` 를 부르면 어느 보드의 캐시와도 완전 일치하지
   * 않아 항상 `undefined` 다.
   *
   * @param projectKey 프로젝트 식별 키. 예: "ATLAS"
   */
  project: (projectKey: string) => ['backlog', projectKey] as const,
}

// ─────────────────────────────────────────────────────────────────────────────
// useBacklog — 백로그 전체 뷰 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 백로그 전체 뷰를 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/backlog[?board=] → BacklogView (미할당 이슈 + 스프린트별 이슈)
 * staleTime 30초 — 빈번한 재조회를 방지한다.
 *
 * 보드가 다르면 **캐시가 갈린다**({@link backlogKeys.detail}). 갈리지 않으면 보드를 바꿔도
 * 캐시 히트로 끝나 **다른 보드의 백로그가 그대로 남는다**.
 *
 * @param projectKey 프로젝트 키. 빈 문자열이면 쿼리가 비활성화된다.
 * @param boardId 보고 있는 보드 UUID. `undefined` 면 서버가 기본 보드로 폴백한다 —
 *   프론트가 기본 보드를 골라주지 않는다(판단이 두 곳으로 갈리면 화면과 서버가 어긋난다)
 */
export function useBacklog(projectKey: string, boardId: string | undefined) {
  return useQuery<BacklogView>({
    queryKey: backlogKeys.detail(projectKey, boardId),
    queryFn: () => fetchBacklog(projectKey, boardId),
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
      await queryClient.invalidateQueries({ queryKey: backlogKeys.project(projectKey) })
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
      await queryClient.invalidateQueries({ queryKey: backlogKeys.project(projectKey) })
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
      await queryClient.invalidateQueries({ queryKey: backlogKeys.project(projectKey) })
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
      await queryClient.invalidateQueries({ queryKey: backlogKeys.project(projectKey) })
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
      await queryClient.invalidateQueries({ queryKey: backlogKeys.project(projectKey) })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 스프린트 상태 전환 — 공통 무효화 (FR-BD-04)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스프린트 상태 전환(시작·완료·삭제) 뒤 다시 그려야 할 캐시를 전부 무효화한다.
 *
 * ### 왜 백로그만으로는 모자라는가
 * 전환은 백로그의 스프린트 칸만 바꾸는 조작이 아니다 — 스크럼 보드의 **카드 집합 자체가**
 * 활성 스프린트에서 파생된다(백엔드 `getBoard` 가 SCRUM 이면 활성 스프린트 이슈를 배치한다).
 * 그래서 시작하는 순간 보드가 채워지고 완료하는 순간 다시 빈다. 보드 무효화가 없으면
 * `useBoard` 의 `staleTime` 30초 동안 **전환 전 시점의 보드**가 그대로 재사용된다.
 * J18 「select Start sprint, and the stories will move into the Active sprints view」와
 * J5 「the board displays only the work items added to the sprint you started」가 그 사이
 * 깨지고, D7 E2E(`e2e/scrum-board.spec.ts` S7)가 그것을 잡는다.
 *
 * `staleTime` 을 낮춰 때우지 않는다 — 그러면 스프린트와 무관한 모든 보드 조회가 함께 느려진다.
 * 대상을 보드 단위로 좁히지도 않는다 — 이유는 `boardKeys.all` KDoc.
 *
 * ### 왜 한 함수인가
 * 시작과 완료는 **대칭**이라 무효화 대상이 같아야 한다. 두 곳에 흩어 두면 한쪽만 늘어난
 * 차이가 실패로 안 드러나고 「가끔 안 바뀌는 화면」으로만 남는다. 삭제도 같은 자리를 쓴다 —
 * 스프린트가 사라지면 백로그 칸과 보드가 동시에 바뀌므로 덮어야 할 캐시가 정확히 같다.
 *
 * @param queryClient 무효화 대상 쿼리 클라이언트
 * @param projectKey 백로그 queryKey 대상 프로젝트 키
 */
async function invalidateAfterSprintTransition(
  queryClient: ReturnType<typeof useQueryClient>,
  projectKey: string,
): Promise<void> {
  await queryClient.invalidateQueries({ queryKey: backlogKeys.project(projectKey) })
  await queryClient.invalidateQueries({ queryKey: boardKeys.all })
}

// ─────────────────────────────────────────────────────────────────────────────
// useStartSprint — 스프린트 시작 (PLANNED → ACTIVE)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스프린트를 시작한다 (PLANNED → ACTIVE).
 *
 * POST /api/v1/sprints/{id}/start → SprintMeta (status: "ACTIVE")
 *
 * onSuccess → invalidateQueries (invalidate-only). 백로그와 **보드**를 함께 무효화한다 —
 * 근거는 {@link invalidateAfterSprintTransition}.
 *
 * @param projectKey 백로그 queryKey 대상 프로젝트 키
 */
export function useStartSprint(projectKey: string) {
  const queryClient = useQueryClient()

  return useMutation<SprintMeta, unknown, string>({
    mutationFn: (sprintId) => startSprint(sprintId),
    onSuccess: () => invalidateAfterSprintTransition(queryClient, projectKey),
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
 * onSuccess → invalidateQueries (invalidate-only). 백로그와 **보드**를 함께 무효화한다 —
 * 완료는 시작의 대칭이라 스크럼 보드가 다시 빈다(활성 스프린트가 사라진다). 근거는
 * {@link invalidateAfterSprintTransition}.
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
    onSuccess: () => invalidateAfterSprintTransition(queryClient, projectKey),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteSprint — 스프린트 삭제 (FR-3)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스프린트를 삭제한다.
 *
 * DELETE /api/v1/sprints/{id} → 204 (본문 없음). 스프린트만 사라지고 그 이슈는 백로그 칸으로
 * 돌아온다.
 *
 * onSuccess → invalidateQueries (invalidate-only). 백로그와 **보드**를 함께 무효화한다 —
 * 시작·완료와 **같은** {@link invalidateAfterSprintTransition} 을 쓴다. 사본을 만들면 세 조작의
 * 무효화 규약이 그 순간 갈라지고, 그 차이는 실패가 아니라 「가끔 안 바뀌는 화면」으로만 남는다.
 *
 * 요청에는 `DELETE_TIMEOUT_MS`(`@/lib/delete-timeout`) 상한이 걸려 있고 상한을 넘기면
 * **요청 자체가 취소된다.** 삭제 확인 창이 `isPending` 에 묶여 닫히지 못하는 상태를 끊기 위한
 * 것이며, 사유는 같은 모듈의 `DeleteTimeoutError` 로 전달된다 — 호출부는 상태 코드가 없는 이
 * 실패를 「응답이 없다」로 안내하고, `ApiError` 는 status(404·403)로 갈라 안내한다.
 *
 * @param projectKey 백로그 queryKey 대상 프로젝트 키
 */
export function useDeleteSprint(projectKey: string) {
  const queryClient = useQueryClient()

  return useMutation<void, unknown, string>({
    mutationFn: (sprintId) => withDeleteTimeout((signal) => deleteSprint(sprintId, signal)),
    onSuccess: () => invalidateAfterSprintTransition(queryClient, projectKey),
  })
}
