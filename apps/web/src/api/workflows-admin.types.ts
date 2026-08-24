// 워크플로우 관리 API 의 Zod 스키마·입력 타입 — 백엔드 DTO 형태마다 스키마 1장
import { z } from 'zod'
import { stateCategorySchema, transitionKindSchema } from './workflows'

/**
 * backend `{ data: T }` 래퍼 파싱 헬퍼.
 *
 * ★ `.strict()` 는 **base 스키마에** 건다. 바깥 래퍼에만 걸면 `data` 안쪽 필드 추가가 통과해
 * 봉인이 절반만 닫힌다(workflow-schemes 독립 리뷰 B1 실측과 같은 자리다).
 */
export const dataOf = <T>(innerSchema: z.ZodSchema<T>) => z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// 전역 상태 카탈로그 — `StatusController`
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `GET /api/v1/statuses` 원소 — 백엔드 `StatusResponse` 6필드와 1:1.
 *
 * `description` 이 nullable 인 이유. Kotlin 이 `String?` 이라 실제로 null 이 온다. `''` 로
 * 흡수하면 「설명 없음」과 「빈 설명」을 소비처가 구별할 수 없다 — `workflows.ts` 의
 * `fromStateKey` 주석이 세운 것과 같은 규칙이다.
 */
export const statusSchema = z
  .object({
    id: z.string().uuid(),
    key: z.string().min(1),
    name: z.string().min(1),
    description: z.string().nullable(),
    category: stateCategorySchema,
    isSystem: z.boolean(),
  })
  .strict()

/** `POST /api/v1/statuses` 응답 — 백엔드 `CreatedStatusResponse`. */
export const createdStatusSchema = z.object({ id: z.string().uuid(), key: z.string().min(1) }).strict()

// ─────────────────────────────────────────────────────────────────────────────
// 워크플로우 쓰기 — `WorkflowController`
// ─────────────────────────────────────────────────────────────────────────────

/** `POST /api/v1/workflows` · `.../duplicate` 응답 — 백엔드 `CreatedWorkflowResponse`. */
export const createdWorkflowSchema = z.object({ key: z.string().min(1) }).strict()

/**
 * 전환 정의 응답 — 백엔드 `TransitionResponse` 6필드.
 *
 * 요청은 `fromStatusKey`/`toStatusKey` 인데 응답은 `fromStateKey`/`toStateKey` 다. 백엔드가
 * 응답 필드를 **읽기 API 와 맞춘** 결과이므로(spec §API 계약) 이름을 임의로 통일하지 않는다.
 */
export const transitionDefinitionSchema = z
  .object({
    id: z.string().uuid(),
    key: z.string().min(1),
    kind: transitionKindSchema,
    fromStateKey: z.string().min(1).nullable(),
    toStateKey: z.string().min(1),
    name: z.string().min(1),
  })
  .strict()

// ─────────────────────────────────────────────────────────────────────────────
// 추론 타입
// ─────────────────────────────────────────────────────────────────────────────

export type StatusCatalogEntry = z.infer<typeof statusSchema>
export type CreatedStatus = z.infer<typeof createdStatusSchema>
export type CreatedWorkflow = z.infer<typeof createdWorkflowSchema>
export type TransitionDefinition = z.infer<typeof transitionDefinitionSchema>
export type StateCategory = z.infer<typeof stateCategorySchema>
export type TransitionKind = z.infer<typeof transitionKindSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 요청 입력 타입 — 백엔드 요청 DTO 와 1:1. **키 불변 필드는 아예 없다.**
// ─────────────────────────────────────────────────────────────────────────────

/** `CreateStatusRequest`. `key` 는 여기서만 정해지고 이후 불변이다. */
export interface CreateStatusInput {
  key: string
  name: string
  description: string | null
  category: StateCategory
}

/** `WorkflowStatusSeedRequest` — 새 워크플로우에 처음 편성할 상태. */
export interface WorkflowStatusSeedInput {
  key: string
  name: string
  category: StateCategory
  displayOrder: number
}

/** `CreateWorkflowRequest`. `statuses` 가 비면 백엔드가 400 이다. */
export interface CreateWorkflowInput {
  key: string
  name: string
  description: string | null
  statuses: WorkflowStatusSeedInput[]
}

/** `UpdateWorkflowRequest`. ★ `key` 가 없다 — 참조가 문자열이라 바뀌면 조용히 끊긴다. */
export interface UpdateWorkflowInput {
  name: string
  description: string | null
}

/** `DuplicateWorkflowRequest`. */
export interface DuplicateWorkflowInput {
  key: string
  name: string
}

/** `AddWorkflowStatusRequest`. */
export interface AddWorkflowStatusInput {
  statusId: string
  displayOrder: number
}

/**
 * `TransitionDefinitionRequest`. 생성·수정이 같은 바디를 쓴다 — PUT 은 표현 전체 교체다.
 *
 * `fromStatusKey` 가 null 이면 직렬화에서 **키 자체를 뺀다**. `GLOBAL`·`INITIAL` 에 그 키를
 * 실으면 백엔드가 400 이라, `null` 을 그대로 보내면 전역 전환을 못 만든다.
 */
export interface TransitionDefinitionInput {
  fromStatusKey: string | null
  toStatusKey: string
  name: string
  kind: TransitionKind
}
