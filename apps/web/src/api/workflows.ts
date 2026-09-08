// project-workflow BC REST API client + Zod 스키마
import { z } from 'zod'
import { apiGet, apiPost, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 정의
// spec §6 WorkflowView 타입과 1:1 대응.
// backend WorkflowDto(web/dto/WorkflowDto.kt) 직렬화 형태를 기반으로 하되
// description, transition.key는 frontend spec §6 표기를 따른다.
// ─────────────────────────────────────────────────────────────────────────────

/** 워크플로우 상태 카테고리 — backend StateCategory enum 3종과 1:1 대응 */
export const stateCategorySchema = z.enum(['TODO', 'IN_PROGRESS', 'DONE'])

/** 워크플로우 단일 상태 Zod 스키마 */
export const workflowStateViewSchema = z.object({
  key: z.string().min(1),
  name: z.string().min(1),
  category: stateCategorySchema,
  displayOrder: z.number().int().nonnegative(),
})

/** 전환 종류 — backend TransitionKind enum 3종과 1:1 대응 */
export const transitionKindSchema = z.enum(['NORMAL', 'GLOBAL', 'INITIAL'])

/**
 * 워크플로우 전환(화살표) Zod 스키마 — backend `WorkflowTransitionDto` 6필드와 1:1.
 *
 * `fromStateKey` 가 nullable 인 이유. 출발 상태가 없는 전환이 두 종류 있다 —
 * GLOBAL(어느 상태에서나)과 INITIAL(이슈 생성 진입). 마이그레이션 V207 ⑨ 가 워크플로우마다
 * INITIAL 1건을 백필하므로 **모든 기존 DB 의 응답에 null 원소가 섞인다**.
 *
 * 빈 문자열 폴백을 넣지 않는다. null 을 `''` 로 흡수하면 계약 위반이 화면까지 조용히 흘러가고,
 * 「출발 상태가 없다」와 「출발 상태 키가 빈 문자열이다」를 소비처가 구별할 수 없게 된다.
 * 두 종류의 의미는 정반대이므로 `kind` 로 갈라서 그린다.
 */
export const workflowTransitionViewSchema = z.object({
  key: z.string().min(1),
  name: z.string().min(1),
  fromStateKey: z.string().min(1).nullable(),
  toStateKey: z.string().min(1),
  id: z.string().uuid(),
  kind: transitionKindSchema,
})

/** 워크플로우 전체 Zod 스키마 */
export const workflowViewSchema = z.object({
  key: z.string().min(1),
  name: z.string().min(1),
  description: z.string(),
  states: z.array(workflowStateViewSchema),
  transitions: z.array(workflowTransitionViewSchema),
  /**
   * 소유 프로젝트 UUID. null = 전역 공유 템플릿 (FR-WF-08).
   *
   * ★선택 필드로 두지 않는다. 백엔드는 항상 보내므로 `optional` 은 「안 올 수도 있다」는
   * 거짓말이 되고, 목 픽스처가 이 필드를 빠뜨린 채 초록으로 남는다 — 그러면 화면이
   * 전역/전용을 못 가르는 것이 테스트에 안 잡힌다.
   */
  projectId: z.string().uuid().nullable(),
})

/** 워크플로우 목록 Zod 스키마 */
export const workflowListSchema = z.array(workflowViewSchema)

/** 전환 계획 결과 Zod 스키마 — backend TransitionResponseDto 대응 */
export const transitionPlanSchema = z.object({
  toStateKey: z.string().min(1),
  fieldChanges: z.array(
    z.object({
      field: z.string(),
      oldValue: z.unknown(),
      newValue: z.unknown(),
    }),
  ),
  events: z.array(
    z.object({
      type: z.string(),
      payload: z.record(z.string(), z.unknown()),
    }),
  ),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지 — spec §6 WorkflowView 타입과 별개로 Zod 인스턴스)
// ─────────────────────────────────────────────────────────────────────────────
export type WorkflowView = z.infer<typeof workflowViewSchema>
export type TransitionPlan = z.infer<typeof transitionPlanSchema>

/** backend 응답 래퍼 `{ data: T }` 파싱 헬퍼 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전체 워크플로우 목록을 조회한다.
 * GET /api/v1/workflows → { data: WorkflowView[] }
 */
export async function fetchWorkflows(): Promise<WorkflowView[]> {
  const wrapped = await apiGet(
    '/api/v1/workflows',
    dataResponseSchema(workflowListSchema),
  )
  return wrapped.data
}

/**
 * 프로젝트가 쓸 수 있는 워크플로우 목록을 조회한다 — 전역 공유 + 그 프로젝트 전용.
 * GET /api/v1/projects/{projectKey}/workflows → { data: WorkflowView[] }
 *
 * ★[fetchWorkflows] 를 프로젝트 화면에서 쓰지 않는다. 그쪽은 **권한 게이트가 없는 전량 목록**이라
 * 남의 프로젝트 전용 워크플로우가 이름째 온다(FR-WF-08). 서버가 좁힌 창구를 따로 탄다.
 *
 * 403 은 「이 프로젝트의 워크플로우를 관리할 권한이 없다」, 404 는 「그 프로젝트가 없다」다.
 */
export async function fetchProjectWorkflows(projectKey: string): Promise<WorkflowView[]> {
  const wrapped = await apiGet(
    // 경로 파라미터가 URL 에서 그대로 온다 — 인코딩 없이 박으면 `..%2F` 로 다른 자원에 닿는다
    `/api/v1/projects/${encodeURIComponent(projectKey)}/workflows`,
    dataResponseSchema(workflowListSchema),
  )
  return wrapped.data
}

/**
 * 특정 키의 워크플로우를 단건 조회한다.
 * GET /api/v1/workflows/{key} → { data: WorkflowView }
 * 404 시 ApiError(404, ...) throw.
 */
export async function fetchWorkflow(key: string): Promise<WorkflowView> {
  const wrapped = await apiGet(
    // 경로 파라미터가 URL 에서 그대로 온다 — 인코딩 없이 박으면 `..%2F` 로 다른 자원에 닿는다
    `/api/v1/workflows/${encodeURIComponent(key)}`,
    dataResponseSchema(workflowViewSchema),
  ).catch((err: unknown) => {
    if (err instanceof ApiError && err.status === 404) {
      throw new ApiError(404, { message: `워크플로우를 찾을 수 없습니다: ${key}` })
    }
    throw err
  })
  return wrapped.data
}

/**
 * 워크플로우 전환 계획을 계산한다.
 * POST /api/v1/workflows/{key}/transitions/plan → { data: TransitionPlan }
 *
 * 경로가 `/transitions` 가 아니라 `/transitions/plan` 인 이유. `/transitions` 는 전환 **정의**
 * CRUD 로 쓰인다(POST 는 전환 생성). 계획 요청을 그리로 보내면 조용히 오라우팅된다 — 결정 D-1.
 *
 * ADR 2026-08-18-workflow-transition-id-identity 참조.
 *
 * @param key 워크플로우 식별 키
 * @param request 전환 요청 — issueKey와 transitionKey를 포함
 */
export async function planTransition(
  key: string,
  request: {
    issueKey: string
    transitionKey: string
    fromStateKey: string
    toStateKey: string
    actorId: string
    actorRoles?: string[]
    version: number
  },
): Promise<TransitionPlan> {
  const wrapped = await apiPost(
    `/api/v1/workflows/${encodeURIComponent(key)}/transitions/plan`,
    {
      issueKey: request.issueKey,
      fromStateKey: request.fromStateKey,
      toStateKey: request.toStateKey,
      actorId: request.actorId,
      actorRoles: request.actorRoles ?? [],
      version: request.version,
    },
    dataResponseSchema(transitionPlanSchema),
  )
  return wrapped.data
}
