// 자동화 룰 실행 이력(RuleExecution) 조회 응답 Zod 스키마 + 타입 (FR-AT-05 D6/D7) — 봉투 없이 backend DTO를 그대로 반환(bare)
import { z } from 'zod'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마
// backend RuleExecutionSummaryResponse/RuleExecutionDetailResponse/ActionOutcomeResponse DTO
// (com.bts.automation.adapter.web.dto.RuleExecutionResponses.kt) 직렬화 형태와 1:1 대응.
// automation 응답은 `{data}` 봉투 없이 bare DTO를 직접 반환한다(automation-rules.types.ts 선례와 동일).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 실행 이력 결과 집계 상태 enum — backend `ActionExecutionStatus` 4종 1:1 대응.
 */
export const ruleExecutionStatusSchema = z.enum(['SUCCESS', 'PARTIAL', 'FAILED', 'SKIPPED'])

/**
 * 액션 1건의 실행 결과 응답 Zod 스키마 — backend `ActionOutcomeResponse` DTO 1:1 대응.
 * `error`는 실패 사유 코드이며, 성공이면 `null`이다.
 */
export const actionOutcomeSchema = z.object({
  position: z.number().int(),
  actionType: z.string(),
  success: z.boolean(),
  error: z.string().nullable(),
})

/**
 * 룰별 실행 이력 목록 조회(`GET .../rules/{ruleId}/executions`) 응답 Zod 스키마.
 * backend `RuleExecutionSummaryResponse` DTO 1:1 대응 — outcomes/triggerEvent 원문은 미포함,
 * 집계값(`actionCount`/`successCount`)만 담는다.
 *
 * `triggerType`은 backend가 `TriggerType.name`(문자열)으로 내려주므로 enum이 아니라
 * `z.string()`으로 느슨하게 받는다(향후 트리거 타입 추가에도 이 스키마가 깨지지 않도록,
 * `automation-rules.types.ts`의 `triggerTypeSchema`처럼 강하게 검증하지 않는다).
 */
export const ruleExecutionSummarySchema = z.object({
  id: z.string().uuid(),
  ruleId: z.string().uuid(),
  triggerType: z.string(),
  issueKey: z.string().nullable(),
  status: ruleExecutionStatusSchema,
  actionCount: z.number().int(),
  successCount: z.number().int(),
  startedAt: z.string().datetime(),
  finishedAt: z.string().datetime(),
  replayedFrom: z.string().uuid().nullable(),
})

/**
 * 실행 이력 단건 trace 조회(`GET /api/v1/automation/executions/{id}`) 응답 Zod 스키마.
 * backend `RuleExecutionDetailResponse` DTO 1:1 대응 — {@link ruleExecutionSummarySchema}에
 * replay 재료인 `triggerEvent` 원문과 액션별 결과 전체(`outcomes`)를 더한 형태다.
 *
 * `triggerEvent`는 backend가 `JsonNode`로 그대로 직렬화하는 임의 JSON payload라
 * (BC 격리상 issue-tracking 등 다른 BC 타입을 구조화하지 않는다) `z.unknown()`으로 받는다.
 */
export const ruleExecutionDetailSchema = ruleExecutionSummarySchema.extend({
  projectKey: z.string(),
  triggerEvent: z.unknown(),
  outcomes: z.array(actionOutcomeSchema),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 실행 이력 결과 집계 상태 */
export type RuleExecutionStatus = z.infer<typeof ruleExecutionStatusSchema>

/** 액션 1건의 실행 결과 응답 타입 */
export type ActionOutcome = z.infer<typeof actionOutcomeSchema>

/** 룰별 실행 이력 목록 요약 응답 타입 */
export type RuleExecutionSummary = z.infer<typeof ruleExecutionSummarySchema>

/** 실행 이력 단건 trace 상세 응답 타입 */
export type RuleExecutionDetail = z.infer<typeof ruleExecutionDetailSchema>
