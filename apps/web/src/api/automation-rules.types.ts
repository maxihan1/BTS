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
export const actionTypeSchema = z.enum(['SET_FIELD', 'ASSIGN', 'ADD_COMMENT', 'CALL_WEBHOOK', 'SET_FIX_VERSIONS'])

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
 * 규칙 충돌 타입 enum — backend `ConflictType` 4종(CYCLE·FIELD_CONFLICT·PRIORITY_AMBIGUITY·
 * PERMISSION_MISSING) 1:1 대응 (FR-AT-04 D6/D7 규칙 충돌 정적 분석).
 */
export const conflictTypeSchema = z.enum(['CYCLE', 'FIELD_CONFLICT', 'PRIORITY_AMBIGUITY', 'PERMISSION_MISSING'])

/**
 * 규칙 충돌 심각도 enum — backend `ConflictSeverity` 1:1 대응. 현재는 `WARNING` 단일 값뿐이다
 * (soft 경고 — 저장 자체를 막지 않는다. 서버가 저장을 거부하는 hard 위반이 추가되면 이 enum도
 * backend와 함께 확장한다).
 */
export const conflictSeveritySchema = z.enum(['WARNING'])

/**
 * 규칙 충돌 1건의 응답 Zod 스키마 — backend `RuleConflictResponse` DTO(com.bts.automation.adapter.web.dto.
 * AutomationRuleResponses.kt) 1:1 대응 (FR-AT-04 D6/D7). `ruleIds`는 이 충돌에 연루된 상대 룰들의
 * UUID 목록, `detail`은 사용자에게 보여줄 설명 문구(서버가 완성해 내려준다 — 프론트는 조립하지 않는다).
 */
export const ruleConflictResponseSchema = z.object({
  type: conflictTypeSchema,
  severity: conflictSeveritySchema,
  ruleIds: z.array(z.string().uuid()),
  detail: z.string(),
})

/**
 * 자동화 룰 표준 응답 Zod 스키마 — 목록/단건/PATCH 응답에 공통으로 쓰인다.
 * backend AutomationRuleResponse DTO 1:1 대응. 웹훅 토큰 원문/해시는 필드 자체가 없다.
 *
 * `conflicts`(FR-AT-04 D6/D7) — create 응답은 이 스키마가 `CreateAutomationRuleResponse.rule`로
 * 중첩돼 쓰이므로 `rule.conflicts`, patch 응답은 최상위 `conflicts`로 같은 필드를 공유한다.
 * create/patch는 충돌이 없어도 항상 빈 배열을 포함하지만, GET(목록/단건)은 backend가
 * `@JsonInclude(NON_NULL)`로 직렬화해 키 자체가 응답에서 빠진다 — backend가 `null`을 명시적으로
 * 보내는 경로는 없으므로 `.nullable()`이 아니라 `.optional()`로 이 키 부재를 표현한다.
 */
export const automationRuleResponseSchema = z.object({
  id: z.string().uuid(),
  projectKey: z.string(),
  name: z.string(),
  enabled: z.boolean(),
  triggerType: triggerTypeSchema,
  triggerConfig: z.string(),
  condition: z.string().nullable(),
  actions: z.array(actionResponseSchema),
  actorUserId: z.string().uuid(),
  hasWebhookToken: z.boolean(),
  nextFireAt: z.string().datetime().nullable(),
  createdBy: z.string().uuid(),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
  version: z.number().int(),
  conflicts: z.array(ruleConflictResponseSchema).optional(),
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

/** 규칙 충돌 타입 (FR-AT-04 D6/D7) */
export type ConflictType = z.infer<typeof conflictTypeSchema>

/** 규칙 충돌 심각도 (FR-AT-04 D6/D7) */
export type ConflictSeverity = z.infer<typeof conflictSeveritySchema>

/** 규칙 충돌 1건의 응답 타입 (FR-AT-04 D6/D7) */
export type RuleConflict = z.infer<typeof ruleConflictResponseSchema>

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

/**
 * 자동화 룰 생성 요청 입력 타입 — backend CreateAutomationRuleRequest 1:1 대응.
 * `condition`은 조건 표현식(JSONLogic 부분집합) JSON 문자열(FR-AT-03). 미지정 시 조건 없음(항상 실행).
 */
export interface CreateAutomationRuleInput {
  name: string
  triggerType: TriggerType
  triggerConfig: string
  condition?: string
  actions?: ActionRequestInput[]
  actorUserId?: string
}

/**
 * 자동화 룰 부분 수정(PATCH) 요청 입력 타입.
 * version은 OCC(낙관적 동시성 제어) 대조용으로 필수, 나머지는 선택(미지정 시 무변경).
 * actions는 지정 시 **전체 교체**(부분 병합 아님, backend PatchAutomationRuleRequest 동일 컨벤션).
 * `condition`도 조건 표현식 JSON 문자열(FR-AT-03), 미지정 시 무변경.
 */
export interface PatchAutomationRuleInput {
  version: number
  name?: string
  enabled?: boolean
  triggerConfig?: string
  condition?: string
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
  versionIds?: string[]
  fixVersionsMode?: 'replace' | 'clear'
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
    case 'SET_FIX_VERSIONS': {
      const raw = config['versionIds']
      const versionIds = Array.isArray(raw) ? raw.filter((v): v is string => typeof v === 'string') : []
      return { versionIds, fixVersionsMode: versionIds.length === 0 ? 'clear' : 'replace' }
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
    case 'SET_FIX_VERSIONS':
      return config.fixVersionsMode === 'clear'
        ? JSON.stringify({ versionIds: [] })
        : JSON.stringify({ versionIds: config.versionIds ?? [] })
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// condition 표현식 도메인 — JSONLogic 부분집합 트리 ↔ backend Condition.kt 와이어 포맷 (FR-AT-03)
//
// 코드 실행 경로가 없는 순수 데이터 트리(backend `Condition.kt`의 프론트 미러). 이항 비교
// (==/!=/>/>=/</<=/in)는 `{"<op>":[{"var":field},리터럴]}`(var 선두 고정)이고, `in`만 파싱 시
// 역순(`{"in":[리터럴,{"var":field}]}`)도 허용하되 직렬화는 항상 var 선두 정규형으로 통일한다.
// 단항(!/!!)은 `{"<op>":{"var":field}}`(리터럴 없음). And/Or는 `{"and"|"or":[...]}`.
// backend `Not(cond)`는 프론트 트리에서 별도 노드를 두지 않고 {@link ConditionGroup}의 `negated`
// 플래그로 흡수한다 — 단일 비교식을 부정할 때는 그 비교식 하나만 담은 And 그룹에 negated=true를
// 씌운다(파싱: `{"not":X}`에서 X가 그룹이면 negated 토글, 비교식이면 단일 자식 And로 감싼다).
// ─────────────────────────────────────────────────────────────────────────────

/** 조건 비교 연산자 9종 — backend `ComparisonOperator` enum 1:1 대응 (FR-AT-03). */
export type ComparisonOperator =
  | 'EQUALS'
  | 'NOT_EQUALS'
  | 'GREATER_THAN'
  | 'GREATER_THAN_OR_EQUAL'
  | 'LESS_THAN'
  | 'LESS_THAN_OR_EQUAL'
  | 'IN'
  | 'EMPTY'
  | 'EXISTS'

/** {@link ComparisonOperator} 1건의 메타데이터 — 와이어 JSON 키·피연산자 개수·UI 표시 라벨. */
export interface ComparisonOperatorMeta {
  /** 와이어 포맷(backend Condition.kt) JSON 키 — `ComparisonOperator.jsonKey`와 1:1 대응 */
  readonly jsonKey: string
  /** 피연산자 개수 — `unary`(!/!!, var 참조 하나) vs `binary`(var+리터럴 2개 배열) */
  readonly arity: 'unary' | 'binary'
  /** UI 연산자 select에 표시할 한국어 라벨 */
  readonly label: string
}

/**
 * 연산자 9종의 메타 테이블(jsonKey↔arity↔label) — backend `ComparisonOperator` KDoc 표와 1:1 대응.
 * 파싱(jsonKey→operator 역조회, {@link JSON_KEY_TO_OPERATOR})과 직렬화(operator→jsonKey) 양방향에서
 * 이 테이블 하나만 참조한다 — 연산자 추가/변경 시 수정 지점을 하나로 모은다.
 */
export const COMPARISON_OPERATOR_META: Record<ComparisonOperator, ComparisonOperatorMeta> = {
  EQUALS: { jsonKey: '==', arity: 'binary', label: '같음' },
  NOT_EQUALS: { jsonKey: '!=', arity: 'binary', label: '다름' },
  GREATER_THAN: { jsonKey: '>', arity: 'binary', label: '초과' },
  GREATER_THAN_OR_EQUAL: { jsonKey: '>=', arity: 'binary', label: '이상' },
  LESS_THAN: { jsonKey: '<', arity: 'binary', label: '미만' },
  LESS_THAN_OR_EQUAL: { jsonKey: '<=', arity: 'binary', label: '이하' },
  IN: { jsonKey: 'in', arity: 'binary', label: '포함' },
  EMPTY: { jsonKey: '!', arity: 'unary', label: '비어있음' },
  EXISTS: { jsonKey: '!!', arity: 'unary', label: '존재함' },
}

/** 와이어 JSON 키 → {@link ComparisonOperator} 역조회 맵({@link COMPARISON_OPERATOR_META}에서 파생). */
const JSON_KEY_TO_OPERATOR: ReadonlyMap<string, ComparisonOperator> = new Map(
  (Object.entries(COMPARISON_OPERATOR_META) as Array<[ComparisonOperator, ComparisonOperatorMeta]>).map(
    ([operator, meta]) => [meta.jsonKey, operator],
  ),
)

/** `var` 참조 필드 화이트리스트 9종 — backend `Condition.FIELD_WHITELIST`와 1:1 대응. */
export const FIELD_WHITELIST: readonly string[] = [
  'issue.key',
  'issue.type',
  'issue.status',
  'issue.priority',
  'issue.assignee',
  'issue.reporter',
  'issue.labels',
  'issue.summary',
  'issue.projectKey',
]

/** 값이 숫자로 강제 직렬화되어야 하는 필드(서수 비교 대상) — 9종 중 `issue.priority`만 해당. */
const NUMERIC_CONDITION_FIELDS: readonly string[] = ['issue.priority']

/** `field`가 숫자 강제 대상(`issue.priority`)인지 판별한다(E2 — 배열 원소에도 동일 적용). */
function isNumericConditionField(field: string): boolean {
  return NUMERIC_CONDITION_FIELDS.includes(field)
}

/**
 * 조건 노드 와이어 JSON 키 상수 — backend `Condition.kt`의 private `KEY_AND`/`KEY_OR`/`KEY_NOT`/
 * `KEY_VAR`를 프론트에도 동일하게 미러링한다. 파싱/직렬화 양쪽에서 매직 스트링 산재를 막는다.
 */
const CONDITION_WIRE_KEY = {
  AND: 'and',
  OR: 'or',
  NOT: 'not',
  VAR: 'var',
} as const

/**
 * 조건 그룹 노드 — And/Or 결합자 + 부정 플래그.
 * backend `Not(cond)`은 별도 노드가 아니라 이 그룹의 `negated=true`로 흡수한다(파일 상단 주석 참고).
 */
export interface ConditionGroup {
  kind: 'group'
  op: 'and' | 'or'
  negated: boolean
  children: ConditionNode[]
}

/**
 * 조건 비교 leaf 노드 — 필드-연산자-리터럴.
 * 단항 연산자(EMPTY/EXISTS)는 `value`를 쓰지 않는다(키 자체를 생략한다).
 */
export interface ConditionComparison {
  kind: 'comparison'
  field: string
  operator: ComparisonOperator
  value?: unknown
}

/** 조건 표현식 트리 노드 — {@link ConditionGroup} | {@link ConditionComparison}. */
export type ConditionNode = ConditionGroup | ConditionComparison

/**
 * 빈 조건 트리(항상 참, backend 빈 `{"and":[]}`와 대응)를 새로 생성한다.
 * 조건 없는 룰의 기본 편집 상태 및 조건 빌더 UI의 "그룹 추가" 시작점으로 쓰인다.
 *
 * @returns 자식 없는 And 그룹(호출마다 새 객체를 반환 — 공유 참조로 인한 의도치 않은 변경 방지)
 */
export function createEmptyConditionTree(): ConditionGroup {
  return { kind: 'group', op: CONDITION_WIRE_KEY.AND, negated: false, children: [] }
}

// ─────────────────────────────────────────────────────────────────────────────
// parseConditionExpression — 와이어 JSON 문자열 → ConditionNode 트리
// ─────────────────────────────────────────────────────────────────────────────

/** `node`가 `{"var": "..."}` 형태(정확히 `var` 키 하나뿐인 객체)인지 판별한다. */
function isVarNode(node: unknown): boolean {
  if (typeof node !== 'object' || node === null || Array.isArray(node)) return false
  const keys = Object.keys(node)
  return keys.length === 1 && keys[0] === CONDITION_WIRE_KEY.VAR
}

/** `{"var": F}`에서 `F`를 추출한다. 문자열이 아니거나 {@link FIELD_WHITELIST} 밖이면 예외를 던진다. */
function extractVarField(node: unknown): string {
  const field = (node as Record<typeof CONDITION_WIRE_KEY.VAR, unknown>)[CONDITION_WIRE_KEY.VAR]
  if (typeof field !== 'string' || field.trim() === '' || !FIELD_WHITELIST.includes(field)) {
    throw new Error('var 참조가 화이트리스트 필드가 아닙니다.')
  }
  return field
}

/** `!`/`!!` 단항 비교를 파싱한다 — 피연산자는 `{"var": F}` 하나뿐(리터럴 없음). */
function parseUnaryComparison(operator: ComparisonOperator, operand: unknown): ConditionComparison {
  if (!isVarNode(operand)) {
    throw new Error('단항 연산자는 var 참조 하나만 피연산자로 가져야 합니다.')
  }
  return { kind: 'comparison', field: extractVarField(operand), operator }
}

/** 이항 비교를 파싱한다. `in`만 `[리터럴, var]` 역순도 허용, 나머지는 첫 원소가 var여야 한다. */
function parseBinaryComparison(operator: ComparisonOperator, operand: unknown): ConditionComparison {
  if (!Array.isArray(operand) || operand.length !== 2) {
    throw new Error('이항 연산자는 2개 원소 배열이어야 합니다.')
  }
  const [left, right] = operand as [unknown, unknown]
  const allowReversed = operator === 'IN'
  if (isVarNode(left) && !isVarNode(right)) {
    return { kind: 'comparison', field: extractVarField(left), operator, value: right }
  }
  if (allowReversed && isVarNode(right) && !isVarNode(left)) {
    return { kind: 'comparison', field: extractVarField(right), operator, value: left }
  }
  throw new Error('비교 연산자 피연산자 형식이 올바르지 않습니다.')
}

/** 비교 연산자 노드(단일 키가 `and`/`or`/`not`이 아닌 경우)를 파싱한다 — arity에 따라 단항/이항 위임. */
function parseComparisonNode(jsonKey: string, operand: unknown): ConditionComparison {
  const operator = JSON_KEY_TO_OPERATOR.get(jsonKey)
  if (!operator) {
    throw new Error('지원하지 않는 연산자입니다.')
  }
  const meta = COMPARISON_OPERATOR_META[operator]
  return meta.arity === 'unary' ? parseUnaryComparison(operator, operand) : parseBinaryComparison(operator, operand)
}

/** `{"not": X}`를 파싱한다 — X가 그룹이면 `negated` 토글, 비교식이면 단일 자식 And 그룹으로 감싼다. */
function parseNotNode(operand: unknown): ConditionNode {
  const inner = parseConditionNode(operand)
  if (inner.kind === 'group') {
    return { ...inner, negated: !inner.negated }
  }
  return { kind: 'group', op: CONDITION_WIRE_KEY.AND, negated: true, children: [inner] }
}

/** `and`/`or` 값(조건 배열)을 파싱한다. */
function parseConditionChildren(value: unknown): ConditionNode[] {
  if (!Array.isArray(value)) {
    throw new Error('and/or 값은 조건 배열이어야 합니다.')
  }
  return value.map(parseConditionNode)
}

/** 조건 노드 하나(JSON.parse 결과인 unknown 값)를 재귀적으로 {@link ConditionNode}로 변환한다. */
function parseConditionNode(node: unknown): ConditionNode {
  if (typeof node !== 'object' || node === null || Array.isArray(node)) {
    throw new Error('조건 노드는 JSON 객체여야 합니다.')
  }
  const entries = Object.entries(node as Record<string, unknown>)
  const first = entries[0]
  if (entries.length !== 1 || !first) {
    throw new Error('조건 노드는 정확히 하나의 연산자 키를 가져야 합니다.')
  }
  const [key, value] = first
  if (key === CONDITION_WIRE_KEY.AND) {
    return { kind: 'group', op: 'and', negated: false, children: parseConditionChildren(value) }
  }
  if (key === CONDITION_WIRE_KEY.OR) {
    return { kind: 'group', op: 'or', negated: false, children: parseConditionChildren(value) }
  }
  if (key === CONDITION_WIRE_KEY.NOT) return parseNotNode(value)
  return parseComparisonNode(key, value)
}

/**
 * 자동화 룰 `condition` 필드(와이어 JSON 문자열 또는 `null`)를 조건 트리로 파싱한다.
 *
 * `null`이거나 빈 문자열이면 빈 트리(항상 참, {@link createEmptyConditionTree})를 반환한다.
 * JSON 파싱 실패이거나 형식이 올바르지 않으면(화이트리스트 밖 필드·미지원 연산자 포함)
 * `console.error`로 로그를 남기고 안전하게 빈 트리로 폴백한다(§1.13 빈 catch 금지,
 * `parseBaseConfig` 선례와 동형).
 *
 * @param json backend `AutomationRule.condition` 필드 값
 * @returns 조건 트리(파싱 실패 시 빈 트리)
 */
export function parseConditionExpression(json: string | null): ConditionNode {
  if (json === null || json.trim() === '') {
    return createEmptyConditionTree()
  }
  try {
    return parseConditionNode(JSON.parse(json))
  } catch (error) {
    console.error('automation condition 표현식 파싱 실패 — 빈 트리로 폴백', error)
    return createEmptyConditionTree()
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// serializeConditionExpression — ConditionNode 트리 → 와이어 JSON 문자열
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 빈 그룹(`children.length === 0`)을 재귀적으로 가지치기한다(G2). 자식이 모두 사라져 그룹
 * 자신이 비면 `null`(부모 children에서 제거 대상)을 반환한다. 비교 leaf는 가지치기되지 않는다.
 */
function pruneEmptyGroups(node: ConditionNode): ConditionNode | null {
  if (node.kind === 'comparison') return node
  const prunedChildren = node.children.map(pruneEmptyGroups).filter((child): child is ConditionNode => child !== null)
  if (prunedChildren.length === 0) return null
  return { ...node, children: prunedChildren }
}

/** `issue.priority` 필드 값(단일 값 또는 배열)을 숫자로 강제한다(E2). 그 외 필드는 값을 그대로 둔다. */
function coerceLiteralForWire(field: string, value: unknown): unknown {
  if (!isNumericConditionField(field)) return value ?? null
  return Array.isArray(value) ? value.map((entry) => Number(entry)) : Number(value)
}

/** 비교 leaf를 와이어 노드(JSON.stringify 대상)로 변환한다 — var 선두 정규형으로 고정한다. */
function comparisonToWireNode(node: ConditionComparison): Record<string, unknown> {
  const meta = COMPARISON_OPERATOR_META[node.operator]
  const varNode = { [CONDITION_WIRE_KEY.VAR]: node.field }
  if (meta.arity === 'unary') {
    return { [meta.jsonKey]: varNode }
  }
  return { [meta.jsonKey]: [varNode, coerceLiteralForWire(node.field, node.value)] }
}

/** 가지치기 완료된 노드 하나를 와이어 노드로 변환한다 — `negated` 그룹은 `not`으로 감싼다. */
function toWireNode(node: ConditionNode): unknown {
  if (node.kind === 'comparison') return comparisonToWireNode(node)
  const inner = { [node.op]: node.children.map(toWireNode) }
  return node.negated ? { [CONDITION_WIRE_KEY.NOT]: inner } : inner
}

/**
 * 조건 트리를 backend `condition` 필드와 동일한 와이어 JSON 문자열로 직렬화한다.
 *
 * 빈 그룹은 가지치기되어 결과에서 사라진다(G2 — UI가 만든 빈 placeholder 그룹이
 * `{"or":[]}`(=항상 거짓, footgun)로 저장되는 사고를 방지). 가지치기 후 트리 전체가 비면
 * (최상위 그룹이 원래 비었거나 모든 자식이 가지치기됨) 빈 트리의 정규형인 `'{"and":[]}'`
 * (항상 참, D1)를 반환한다. `issue.priority` 필드 값은 배열 원소까지 포함해 숫자로 강제한다(E2).
 *
 * @param node 직렬화할 조건 트리
 * @returns backend `condition` 필드와 동일한 형식의 JSON 문자열
 */
export function serializeConditionExpression(node: ConditionNode): string {
  const pruned = pruneEmptyGroups(node)
  if (pruned === null) return '{"and":[]}'
  return JSON.stringify(toWireNode(pruned))
}

// ─────────────────────────────────────────────────────────────────────────────
// YAML import 응답 Zod 스키마 — backend AutomationImportResponse 1:1 대응 (FR-AT-06 D6)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * import 로 새로 생성된 WEBHOOK 룰의 1회 노출 토큰 — backend `ImportedWebhookTokenResponse` 1:1 대응
 * (FR-AT-06 D6). 갱신된 WEBHOOK 룰은 토큰을 재발급하지 않아 여기 담기지 않는다.
 */
export const importedWebhookTokenSchema = z.object({
  ruleId: z.string().uuid(),
  name: z.string(),
  token: z.string(),
})

/**
 * YAML import 응답 Zod 스키마 — backend `AutomationImportResponse` 1:1 대응 (FR-AT-06 D6).
 *
 * `webhookTokens` 는 새로 생성된 WEBHOOK 룰이 없으면 **키 자체가 생략**된다(백엔드가
 * `ifEmpty { null }` + `@JsonInclude(NON_NULL)`) → `.optional()` 필수. `conflicts` 는 같은
 * 어노테이션을 공유하지만 실제로는 항상 배열(빈 배열이어도 `[]`)이라 생략을 기대하지 않는다 —
 * 방어적으로만 `.optional()`.
 */
export const automationImportResponseSchema = z.object({
  created: z.number().int(),
  updated: z.number().int(),
  total: z.number().int(),
  ruleIds: z.array(z.string().uuid()),
  webhookTokens: z.array(importedWebhookTokenSchema).optional(),
  conflicts: z.array(ruleConflictResponseSchema).optional(),
})

export type ImportedWebhookToken = z.infer<typeof importedWebhookTokenSchema>
export type AutomationImportResponse = z.infer<typeof automationImportResponseSchema>
