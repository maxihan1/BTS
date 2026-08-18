// bulk-operations BC REST API 클라이언트 + Zod 스키마 — 백엔드 DTO와 1:1 대응
import { z } from 'zod'
import { apiGet, apiPost } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 정의
// backend BulkOperationResponse / BulkUpdateRequest DTO 직렬화 형태와 1:1 대응.
// ─────────────────────────────────────────────────────────────────────────────

/** 일괄 작업 처리 상태 enum — PENDING|RUNNING|COMPLETED|FAILED */
export const bulkOperationStatusSchema = z.enum(['PENDING', 'RUNNING', 'COMPLETED', 'FAILED'])

/** 일괄 작업 유형 enum — BULK_EDIT|BULK_TRANSITION */
export const bulkOperationTypeSchema = z.enum(['BULK_EDIT', 'BULK_TRANSITION'])

/** 개별 이슈 처리 상태 enum — PENDING|SUCCEEDED|FAILED */
export const bulkOperationItemStatusSchema = z.enum(['PENDING', 'SUCCEEDED', 'FAILED'])

/**
 * 개별 이슈 처리 실패 사유 코드 enum 7종.
 * backend BulkOperationItemStatus.failureReasonCode 값과 1:1 대응.
 */
export const failureReasonCodeSchema = z.enum([
  'NOT_FOUND',
  'FORBIDDEN',
  'TRANSITION_NOT_ALLOWED',
  'VERSION_CONFLICT',
  'WORKFLOW_NOT_CONFIGURED',
  'TYPE_NOT_FOUND',
  'UNKNOWN',
])

/** BULK_EDIT payload — priority/impact 변경 */
export const bulkEditPayloadSchema = z.object({
  priority: z.number().int().nullable(),
  impact: z.number().int().nullable(),
})

/** BULK_TRANSITION payload — 목표 상태 키 + 선택적 결의안 ID */
export const bulkTransitionPayloadSchema = z.object({
  toStateKey: z.string().min(1),
  /** DONE 전환 시 결의안 UUID. 비DONE 전환은 undefined. */
  resolutionId: z.string().uuid().optional(),
})

/**
 * 일괄 작업 payload union.
 * 백엔드 응답에 discriminator 필드가 없으므로 operationType으로 판별한다.
 * Zod union은 첫 번째 매칭 스키마를 사용한다.
 */
export const bulkOperationPayloadSchema = z.union([
  bulkEditPayloadSchema,
  bulkTransitionPayloadSchema,
])

/** 일괄 작업 개별 이슈 처리 결과 항목 스키마 */
export const bulkOperationItemSchema = z.object({
  issueKey: z.string().min(1),
  status: bulkOperationItemStatusSchema,
  failureReasonCode: failureReasonCodeSchema.nullable(),
})

/** 일괄 작업 단건 응답 스키마 — backend BulkOperationResponse DTO */
export const bulkOperationResponseSchema = z.object({
  id: z.string().uuid(),
  operationType: bulkOperationTypeSchema,
  status: bulkOperationStatusSchema,
  payload: bulkOperationPayloadSchema,
  totalCount: z.number().int().nonnegative(),
  processedCount: z.number().int().nonnegative(),
  succeededCount: z.number().int().nonnegative(),
  failedCount: z.number().int().nonnegative(),
  items: z.array(bulkOperationItemSchema),
})

/** 202 접수 응답 스키마 — backend BulkAcceptedResponse DTO */
export const bulkAcceptedSchema = z.object({
  bulkOperationId: z.string().uuid(),
  status: z.literal('PENDING'),
  totalCount: z.number().int().nonnegative(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 로컬 헬퍼 — issues.ts와 중복 정의를 피하기 위해 로컬 const 허용
// ─────────────────────────────────────────────────────────────────────────────

/** backend 응답 래퍼 `{ data: T }` 파싱 헬퍼 (로컬 재정의) */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 일괄 작업 단건 응답 타입 */
export type BulkOperationResponse = z.infer<typeof bulkOperationResponseSchema>

/** 202 접수 응답 타입 */
export type BulkAccepted = z.infer<typeof bulkAcceptedSchema>

/** BULK_EDIT 입력 payload 타입 */
export interface BulkEditPayloadInput {
  /** 변경할 우선순위 1~5. null이면 변경 없음. */
  priority: number | null
  /** 변경할 영향도 1~3. null이면 변경 없음. */
  impact: number | null
}

/** BULK_TRANSITION 입력 payload 타입 */
export interface BulkTransitionPayloadInput {
  /** 전환할 목표 상태 키 */
  toStateKey: string
  /** DONE 전환 시 결의안 UUID. 비DONE 전환은 생략 가능. */
  resolutionId?: string
}

/**
 * 일괄 작업 요청 입력 타입.
 * POST /api/v1/issues/bulk-update request body 형태와 1:1 대응.
 */
export interface BulkUpdateInput {
  /** 작업 유형 */
  operationType: 'BULK_EDIT' | 'BULK_TRANSITION'
  /** 대상 이슈 키 목록. 1~1000개 */
  issueKeys: string[]
  /** BULK_EDIT 전용 payload. BULK_TRANSITION 시 null. */
  editPayload: BulkEditPayloadInput | null
  /** BULK_TRANSITION 전용 payload. BULK_EDIT 시 null. */
  transitionPayload: BulkTransitionPayloadInput | null
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 일괄 작업을 접수한다.
 * POST /api/v1/issues/bulk-update → 202 Accepted
 *
 * @param input 작업 유형, 대상 이슈 키 목록, payload
 * @returns BulkAccepted — bulkOperationId/status/totalCount (data 래퍼 언래핑)
 * @throws ApiError(400) 유효성 검증 실패 (ISSUE_BULK_VALIDATION_FAILED)
 * @throws ApiError(403) 권한 없음 (ISSUE_BULK_FORBIDDEN)
 */
export async function submitBulkOperation(input: BulkUpdateInput): Promise<BulkAccepted> {
  const wrapped = await apiPost(
    '/api/v1/issues/bulk-update',
    input,
    dataResponseSchema(bulkAcceptedSchema),
  )
  return wrapped.data
}

/**
 * 일괄 작업 단건을 조회한다.
 * GET /api/v1/bulk-operations/{id} → 200 OK
 *
 * @param id 일괄 작업 UUID
 * @returns BulkOperationResponse — data 래퍼 언래핑
 * @throws ApiError(404) 해당 id의 작업이 없을 때 (ISSUE_BULK_NOT_FOUND)
 */
export async function fetchBulkOperation(id: string): Promise<BulkOperationResponse> {
  const wrapped = await apiGet(
    `/api/v1/bulk-operations/${id}`,
    dataResponseSchema(bulkOperationResponseSchema),
  )
  return wrapped.data
}
