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

/** 워크플로우 전이(화살표) Zod 스키마 */
export const workflowTransitionViewSchema = z.object({
  key: z.string().min(1),
  name: z.string().min(1),
  fromStateKey: z.string().min(1),
  toStateKey: z.string().min(1),
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

/** 전이 계획 결과 Zod 스키마 — backend TransitionResponseDto 대응 */
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
 * 워크플로우 전이 계획을 계산한다.
 * POST /api/v1/workflows/{key}/transitions → { data: TransitionPlan }
 *
 * transition identity = (fromStateKey, toStateKey) — transitionName은 불필요.
 * ADR 2026-05-28-workflow-transition-identity-policy 참조.
 *
 * @param key 워크플로우 식별 키
 * @param request 전이 요청 — issueKey와 transitionKey를 포함
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
    `/api/v1/workflows/${key}/transitions`,
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
