// 자동화 룰 REST 응답/요청 Zod 스키마 + 타입 (FR-AT-01 D6) — 봉투 없이 backend DTO를 그대로 반환(bare)
import { z } from 'zod'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마
// backend AutomationRuleResponse/CreateAutomationRuleResponse DTO 직렬화 형태와 1:1 대응.
// automation 응답은 `{data}` 봉투 없이 bare DTO를 직접 반환한다(custom-fields dataResponseSchema 미사용).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 자동화 트리거 타입 enum — backend TriggerType 5종 1:1 대응.
 */
export const triggerTypeSchema = z.enum([
  'ISSUE_CREATED',
  'ISSUE_UPDATED',
  'ISSUE_COMMENTED',
  'SCHEDULED',
  'WEBHOOK',
])

/**
 * 자동화 룰 표준 응답 Zod 스키마 — 목록/단건/PATCH 응답에 공통으로 쓰인다.
 * backend AutomationRuleResponse DTO 1:1 대응. 웹훅 토큰 원문/해시는 필드 자체가 없다.
 */
export const automationRuleResponseSchema = z.object({
  id: z.string().uuid(),
  projectKey: z.string(),
  name: z.string(),
  enabled: z.boolean(),
  triggerType: triggerTypeSchema,
  triggerConfig: z.string(),
  hasWebhookToken: z.boolean(),
  nextFireAt: z.string().datetime().nullable(),
  createdBy: z.string().uuid(),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
  version: z.number().int(),
})

/**
 * 자동화 룰 생성 응답 Zod 스키마 — `POST .../rules` 전용.
 * backend CreateAutomationRuleResponse DTO 1:1 대응. WEBHOOK 트리거면 webhookToken 원문을
 * 이 응답에서만 1회 동봉하고, 그 외에는 null.
 */
export const createAutomationRuleResponseSchema = z.object({
  rule: automationRuleResponseSchema,
  webhookToken: z.string().nullable(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 자동화 트리거 타입 */
export type TriggerType = z.infer<typeof triggerTypeSchema>

/** 자동화 룰 표준 응답 타입 */
export type AutomationRule = z.infer<typeof automationRuleResponseSchema>

/** 자동화 룰 생성 응답 타입 (rule + 1회성 webhookToken) */
export type CreateAutomationRuleResponse = z.infer<typeof createAutomationRuleResponseSchema>

/** 자동화 룰 생성 요청 입력 타입 — backend CreateAutomationRuleRequest 1:1 대응 */
export interface CreateAutomationRuleInput {
  name: string
  triggerType: TriggerType
  triggerConfig: string
}

/**
 * 자동화 룰 부분 수정(PATCH) 요청 입력 타입.
 * version은 OCC(낙관적 동시성 제어) 대조용으로 필수, 나머지는 선택(미지정 시 무변경).
 */
export interface PatchAutomationRuleInput {
  version: number
  name?: string
  enabled?: boolean
  triggerConfig?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// triggerConfig 직렬화 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 트리거 타입별 구조화 입력을 backend triggerConfig JSON 문자열로 직렬화한다.
 *
 * backend `TriggerConfig.validate` 형식과 1:1 대응.
 * - ISSUE_CREATED / ISSUE_COMMENTED / WEBHOOK — 입력 무시, 빈 객체 `"{}"` 고정.
 * - ISSUE_UPDATED — `fields` 배열이 비어있지 않으면 `{"fields":[...]}`, 없거나 비어있으면 `"{}"`.
 * - SCHEDULED — `{"cron":"..."}` 로 직렬화한다. cron 형식 자체의 유효성(파싱 가능 여부)은
 *   검증하지 않는다 — 프론트 사전검증은 폼 컴포넌트에서 별도로 수행한다.
 *
 * @param triggerType 직렬화 기준이 되는 트리거 타입.
 * @param config 트리거 타입에 대응하는 구조화 입력. 해당 타입에 쓰이지 않는 필드는 무시된다.
 * @returns backend triggerConfig 필드와 동일한 형식의 JSON 문자열.
 */
export function serializeTriggerConfig(
  triggerType: TriggerType,
  config: { cron?: string; fields?: string[] } = {},
): string {
  switch (triggerType) {
    case 'SCHEDULED':
      return JSON.stringify({ cron: config.cron ?? '' })
    case 'ISSUE_UPDATED':
      return config.fields && config.fields.length > 0 ? JSON.stringify({ fields: config.fields }) : '{}'
    case 'ISSUE_CREATED':
    case 'ISSUE_COMMENTED':
    case 'WEBHOOK':
      return '{}'
  }
}
