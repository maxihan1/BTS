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
 * 특정 키의 워크플로우를 단건 조회한다.
 * GET /api/v1/workflows/{key} → { data: WorkflowView }
 * 404 시 ApiError(404, ...) throw.
 */
export async function fetchWorkflow(key: string): Promise<WorkflowView> {
  const wrapped = await apiGet(
    `/api/v1/workflows/${key}`,
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
    `/api/v1/workflows/${key}/transitions/plan`,
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
