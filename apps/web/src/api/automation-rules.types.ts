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
 * 자동화 액션 타입 enum — backend ActionType 4종 1:1 대응 (FR-AT-02).
 */
export const actionTypeSchema = z.enum(['SET_FIELD', 'ASSIGN', 'ADD_COMMENT', 'CALL_WEBHOOK'])

/**
 * 액션 1건의 응답 Zod 스키마 — backend `ActionResponse` DTO 1:1 대응 (FR-AT-02).
 *
 * `config`는 액션 타입별 형태가 서로 달라(SET_FIELD `{field,value}` / ASSIGN `{assigneeId}` /
 * ADD_COMMENT `{body}` / CALL_WEBHOOK `{url,method,headers,body}`) 타입별 discriminated union으로
 * 강하게 표현하지 않고 `z.record(z.string(), z.unknown())` loose record로 받는다(EC11 — brittle 회피).
 * 타입에 따른 구조화는 {@link parseActionConfig}가 수행한다.
 */
export const actionResponseSchema = z.object({
  type: actionTypeSchema,
  config: z.record(z.string(), z.unknown()),
})

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
  actions: z.array(actionResponseSchema),
  actorUserId: z.string().uuid(),
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

/** 자동화 액션 타입 (FR-AT-02) */
export type ActionType = z.infer<typeof actionTypeSchema>

/** 액션 1건의 응답 타입 — config는 객체(요청 config=JSON 문자열과 비대칭, EC1) */
export type ActionResponse = z.infer<typeof actionResponseSchema>

/** 자동화 룰 표준 응답 타입 */
export type AutomationRule = z.infer<typeof automationRuleResponseSchema>

/** 자동화 룰 생성 응답 타입 (rule + 1회성 webhookToken) */
export type CreateAutomationRuleResponse = z.infer<typeof createAutomationRuleResponseSchema>

/**
 * 액션 1건의 요청 입력 타입 — backend `ActionRequest` DTO 1:1 대응 (FR-AT-02).
 *
 * `config`는 **JSON 문자열**이다(응답 `ActionResponse.config`는 객체 — EC1 비대칭 주의).
 * {@link serializeActionConfig}로 폼 상태를 이 문자열로 직렬화한다.
 */
export interface ActionRequestInput {
  type: ActionType
  config: string
}

/** 자동화 룰 생성 요청 입력 타입 — backend CreateAutomationRuleRequest 1:1 대응 */
export interface CreateAutomationRuleInput {
  name: string
  triggerType: TriggerType
  triggerConfig: string
  actions?: ActionRequestInput[]
  actorUserId?: string
}

/**
 * 자동화 룰 부분 수정(PATCH) 요청 입력 타입.
 * version은 OCC(낙관적 동시성 제어) 대조용으로 필수, 나머지는 선택(미지정 시 무변경).
 * actions는 지정 시 **전체 교체**(부분 병합 아님, backend PatchAutomationRuleRequest 동일 컨벤션).
 */
export interface PatchAutomationRuleInput {
  version: number
  name?: string
  enabled?: boolean
  triggerConfig?: string
  actions?: ActionRequestInput[]
  actorUserId?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// triggerConfig 직렬화 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 편집 저장 시 병합 시작점으로 쓸 기존 triggerConfig JSON 문자열을 파싱한다.
 * base 미지정(생성 모드)이면 빈 객체에서 시작 — 기존 동작과 동일.
 * JSON 파싱 실패이거나 객체가 아니면(배열·원시값 포함) 안전하게 빈 객체로 폴백한다
 * (§1.13 빈 catch 금지 — 최소한 콘솔 로그를 남긴다).
 */
function parseBaseConfig(baseConfigJson: string | undefined): Record<string, unknown> {
  if (baseConfigJson === undefined) return {}
  try {
    const parsed: unknown = JSON.parse(baseConfigJson)
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return {}
    }
    return parsed as Record<string, unknown>
  } catch (error) {
    console.error('automation triggerConfig base 파싱 실패 — 빈 객체로 폴백', error)
    return {}
  }
}

/** base 객체에서 폼이 관리하는 키들을 제거한 사본을 반환한다 (원본은 변경하지 않는다). */
function omitManagedKeys(base: Record<string, unknown>, keys: readonly string[]): Record<string, unknown> {
  const rest: Record<string, unknown> = { ...base }
  for (const key of keys) {
    delete rest[key]
  }
  return rest
}

/**
 * 트리거 타입별 구조화 입력을 backend triggerConfig JSON 문자열로 직렬화한다.
 *
 * backend `TriggerConfig.validate` 형식과 1:1 대응.
 * - ISSUE_CREATED / ISSUE_COMMENTED / WEBHOOK — 폼 입력 무시, base 그대로(없으면 빈 객체 `"{}"`).
 * - ISSUE_UPDATED — `fields` 배열이 비어있지 않으면 `{"fields":[...]}`(base 병합), 없거나
 *   비어있으면 base에서 `fields` 키만 제거한 나머지(base 없으면 `"{}"`).
 * - SCHEDULED — `{"cron":"..."}` 로 직렬화한다(base 병합). cron 형식 자체의 유효성(파싱 가능
 *   여부)은 검증하지 않는다 — 프론트 사전검증은 폼 컴포넌트에서 별도로 수행한다.
 *
 * `baseConfigJson`(편집 모드에서 백엔드가 내려준 기존 triggerConfig)을 주면 그 JSON을 병합
 * 시작점으로 삼아, 폼이 인지하지 못하는 키(백엔드가 향후 config에 추가할 수 있는 필드)를
 * 보존한다 — 폼이 관리하는 키(cron·fields)만 새 값으로 덮어쓴다. 생성 모드처럼 base를
 * 지정하지 않으면 기존 동작(빈 객체에서 시작) 그대로다.
 *
 * @param triggerType 직렬화 기준이 되는 트리거 타입.
 * @param config 트리거 타입에 대응하는 구조화 입력. 해당 타입에 쓰이지 않는 필드는 무시된다.
 * @param baseConfigJson 편집 모드에서 병합 시작점으로 쓸 기존 triggerConfig JSON 문자열.
 * @returns backend triggerConfig 필드와 동일한 형식의 JSON 문자열.
 */
export function serializeTriggerConfig(
  triggerType: TriggerType,
  config: { cron?: string; fields?: string[] } = {},
  baseConfigJson?: string,
): string {
  const base = parseBaseConfig(baseConfigJson)
  switch (triggerType) {
    case 'SCHEDULED':
      return JSON.stringify({ ...base, cron: config.cron ?? '' })
    case 'ISSUE_UPDATED': {
      const rest = omitManagedKeys(base, ['fields'])
      return config.fields && config.fields.length > 0
        ? JSON.stringify({ ...rest, fields: config.fields })
        : JSON.stringify(rest)
    }
    case 'ISSUE_CREATED':
    case 'ISSUE_COMMENTED':
    case 'WEBHOOK':
      return JSON.stringify(base)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// action config 직렬화/역직렬화 헬퍼 (FR-AT-02)
//
// ⚠️ config 비대칭(EC1) — 응답(ActionResponse.config)은 객체, 요청(ActionRequest.config)은
// JSON 문자열이다. 읽기는 parseActionConfig(객체 → 폼 상태), 쓰기는 serializeActionConfig
// (폼 상태 → JSON 문자열)로 명시적으로 분리한다. `serializeTriggerConfig` 선례와 동형.
// ─────────────────────────────────────────────────────────────────────────────

/** SET_FIELD 액션이 지원하는 6종 필드 중 값이 정수인 필드(EC9 — 숫자 강제 대상). */
const NUMERIC_SET_FIELD_FIELDS: readonly string[] = ['priority', 'impact']

/** SET_FIELD `field`가 정수 값 필드(priority/impact)인지 판별한다(EC9 숫자 강제 여부 분기). */
function isNumericSetField(field: string): boolean {
  return NUMERIC_SET_FIELD_FIELDS.includes(field)
}

/** CALL_WEBHOOK method 기본값 — backend `Action.DEFAULT_METHOD`와 동일. */
const DEFAULT_WEBHOOK_METHOD = 'POST'

/** CALL_WEBHOOK body 기본값 — backend `Action.DEFAULT_BODY`와 동일. */
const DEFAULT_WEBHOOK_BODY = ''

/**
 * CALL_WEBHOOK 헤더 1행 — 키·값 쌍.
 *
 * backend/응답 계약은 `Record<string,string>`(map)이지만, 폼 편집 중에는 쌍 **배열**로 관리한다
 * (코드리뷰 PR #260 CONCERNS C1·C2 회귀 방지).
 * - C2(엣지) — map으로 관리하면 두 행에 같은 키를 입력할 때 dedup으로 행 하나가 조용히 사라진다.
 *   배열이면 편집 중에는 중복 키가 모두 보존된다(최종 저장 시 map 축약은 {@link serializeActionConfig}
 *   참고, 이건 backend 계약의 불가피한 한계이지 편집 중 소실과는 다른 문제다).
 * - 배열 인덱스 기반 행 식별은 UI({@link ActionConfigEditor}의 헤더 행)에서 안정적인 삭제/편집을
 *   가능하게 한다 — Record 키 기반 삭제는 키가 비었거나 중복일 때 행을 특정할 수 없었다.
 */
export interface WebhookHeaderEntry {
  key: string
  value: string
}

/**
 * 액션 타입별 구조화 폼 상태.
 *
 * `serializeTriggerConfig`의 `{cron?: string; fields?: string[]}` 선례와 동형으로, 4종 액션의
 * config 필드를 optional 유니온 하나에 담는다 — 타입별 discriminated union으로 쪼개지 않는다
 * (EC11, `actionResponseSchema`의 loose record 설계와 일관).
 *
 * 실제로 채워지는 필드는 `actionType`에 따라 다르다.
 * - SET_FIELD → `field`·`value`(value는 필드 타입에 따라 string/number/string[] 등 임의 값)
 * - ASSIGN → `assigneeId`(uuid 문자열 또는 `null` = 담당자 해제)
 * - ADD_COMMENT → `body`
 * - CALL_WEBHOOK → `url`·`method`·`headers`(쌍 배열, {@link WebhookHeaderEntry} 참고)·`body`
 */
export interface ActionConfigFormState {
  field?: string
  value?: unknown
  assigneeId?: string | null
  body?: string
  url?: string
  method?: string
  headers?: WebhookHeaderEntry[]
}

/** `value`가 문자열 값만 가진 순수 객체(Record<string, string>)인지 타입 가드로 확인한다. */
function isStringRecord(value: unknown): value is Record<string, string> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  return Object.values(value).every((entry) => typeof entry === 'string')
}

/** 응답 headers 맵(Record)을 폼 상태가 쓰는 쌍 배열로 변환한다({@link parseActionConfig} 전용). */
function headersRecordToEntries(record: Record<string, string>): WebhookHeaderEntry[] {
  return Object.entries(record).map(([key, value]) => ({ key, value }))
}

/**
 * 폼 상태의 헤더 쌍 배열을 요청 headers 맵(Record)으로 변환한다({@link serializeActionConfig} 전용).
 *
 * C1(코드리뷰 PR #260) — 키가 blank(trim 후 빈 문자열)인 쌍은 결과에서 제외한다. "헤더 추가"로
 * 만든 빈 placeholder 행을 채우지 않고 저장해도 실존 헤더로 오인되지 않도록 한다.
 * C2 — 중복 키가 있으면 `Object.fromEntries`가 배열 순서상 마지막 값으로 덮어쓴다(map 계약이라
 * 불가피한 최종 표현. 편집 중에는 배열이라 소실 없이 모두 보존된다).
 */
function headersEntriesToRecord(entries: readonly WebhookHeaderEntry[]): Record<string, string> {
  const filled = entries.filter((entry) => entry.key.trim() !== '')
  return Object.fromEntries(filled.map((entry) => [entry.key, entry.value] as const))
}

/**
 * 응답 `ActionResponse.config`(객체)를 액션 타입별 구조화 폼 상태로 역직렬화한다.
 *
 * EC10(6종 밖 unknown SET_FIELD field) — `field`가 6종(summary/description/environment/
 * priority/impact/labels) 밖이어도 값은 그대로 보존한다(임의 변경 없음, 텍스트 위젯 fallback은
 * 이 함수를 소비하는 UI 컴포넌트 책임).
 *
 * @param actionType 파싱 기준이 되는 액션 타입.
 * @param config 응답 config 객체(`actionResponseSchema.config`, loose record).
 * @returns 액션 타입에 대응하는 구조화 폼 상태.
 */
export function parseActionConfig(
  actionType: ActionType,
  config: Record<string, unknown>,
): ActionConfigFormState {
  switch (actionType) {
    case 'SET_FIELD':
      return {
        field: typeof config['field'] === 'string' ? config['field'] : '',
        value: config['value'],
      }
    case 'ASSIGN':
      return { assigneeId: typeof config['assigneeId'] === 'string' ? config['assigneeId'] : null }
    case 'ADD_COMMENT':
      return { body: typeof config['body'] === 'string' ? config['body'] : '' }
    case 'CALL_WEBHOOK':
      return {
        url: typeof config['url'] === 'string' ? config['url'] : '',
        method: typeof config['method'] === 'string' ? config['method'] : DEFAULT_WEBHOOK_METHOD,
        headers: isStringRecord(config['headers']) ? headersRecordToEntries(config['headers']) : [],
        body: typeof config['body'] === 'string' ? config['body'] : DEFAULT_WEBHOOK_BODY,
      }
  }
}

/**
 * 액션 타입별 구조화 폼 상태를 요청 `ActionRequest.config`(JSON 문자열)로 직렬화한다.
 *
 * EC9(핵심) — SET_FIELD `field`가 `priority`/`impact`이면 `value`를 `Number()`로 강제해
 * 숫자로 직렬화한다(`{"value":3}`, `"3"` 문자열이 아님). HTML select의 값은 항상 문자열이므로
 * 이 강제 없이는 backend `decodeValue(Int)`가 실패한다. 그 외 필드(EC10 unknown field 포함)는
 * 값을 그대로 보존한다.
 *
 * @param actionType 직렬화 기준이 되는 액션 타입.
 * @param config 액션 타입에 대응하는 구조화 폼 상태.
 * @returns backend `ActionRequest.config` 필드와 동일한 형식의 JSON 문자열.
 */
export function serializeActionConfig(actionType: ActionType, config: ActionConfigFormState): string {
  switch (actionType) {
    case 'SET_FIELD': {
      const field = config.field ?? ''
      const value = isNumericSetField(field) ? Number(config.value) : config.value
      return JSON.stringify({ field, value })
    }
    case 'ASSIGN':
      return JSON.stringify({ assigneeId: config.assigneeId ?? null })
    case 'ADD_COMMENT':
      return JSON.stringify({ body: config.body ?? '' })
    case 'CALL_WEBHOOK':
      return JSON.stringify({
        url: config.url ?? '',
        method: config.method ?? DEFAULT_WEBHOOK_METHOD,
        headers: headersEntriesToRecord(config.headers ?? []),
        body: config.body ?? DEFAULT_WEBHOOK_BODY,
      })
  }
}
