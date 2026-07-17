// automation-rules Zod 스키마 + 직렬화 헬퍼 단위 테스트 — backend AutomationRuleResponse DTO 1:1 대응 검증
import { describe, it, expect, vi } from 'vitest'
import { ZodError } from 'zod'
import {
  automationRuleResponseSchema,
  createAutomationRuleResponseSchema,
  triggerTypeSchema,
  serializeTriggerConfig,
  actionTypeSchema,
  actionResponseSchema,
  parseActionConfig,
  serializeActionConfig,
  parseConditionExpression,
  serializeConditionExpression,
  automationImportResponseSchema,
} from './automation-rules.types'
import type { AutomationRule, ConditionNode } from './automation-rules.types'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — AutomationRuleResponse (backend DTO 1:1)
// Zod v4 uuid() 검증 통과를 위해 RFC4122 v4 형식 UUID 사용
// ─────────────────────────────────────────────────────────────────────────────

const scheduledRuleFixture: AutomationRule = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  projectKey: 'ATLAS',
  name: '매일 오전 스캔',
  enabled: true,
  triggerType: 'SCHEDULED',
  triggerConfig: '{"cron":"0 0 9 * * *"}',
  condition: '{"and":[]}',
  actions: [],
  actorUserId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  hasWebhookToken: false,
  nextFireAt: '2026-07-15T09:00:00Z',
  createdBy: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  createdAt: '2026-07-10T10:00:00Z',
  updatedAt: '2026-07-10T10:00:00Z',
  version: 1,
}

const issueCreatedRuleFixture: AutomationRule = {
  id: 'c3d4e5f6-a7b8-4901-9def-012345678902',
  projectKey: 'ATLAS',
  name: '이슈 생성 알림',
  enabled: true,
  triggerType: 'ISSUE_CREATED',
  triggerConfig: '{}',
  condition: null,
  actions: [],
  actorUserId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  hasWebhookToken: false,
  nextFireAt: null,
  createdBy: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  createdAt: '2026-07-10T10:00:00Z',
  updatedAt: '2026-07-10T10:00:00Z',
  version: 1,
}

/** 액션 2건(FR-AT-02)이 채워진 룰 fixture — actions·actorUserId 파싱 검증용 */
const ruleWithActionsFixture: AutomationRule = {
  ...issueCreatedRuleFixture,
  id: 'd4e5f6a7-b8c9-4012-8123-4567890abcde',
  actions: [
    { type: 'SET_FIELD', config: { field: 'priority', value: 3 } },
    { type: 'ASSIGN', config: { assigneeId: null } },
  ],
  actorUserId: 'b2c3d4e5-f6a7-4890-9bcd-ef0123456789',
}

// ─────────────────────────────────────────────────────────────────────────────
// automationRuleResponseSchema
// ─────────────────────────────────────────────────────────────────────────────

describe('automationRuleResponseSchema', () => {
  it('SCHEDULED 룰 fixture(nextFireAt 값 있음)를 파싱한다', () => {
    const result = automationRuleResponseSchema.parse(scheduledRuleFixture)
    expect(result.triggerType).toBe('SCHEDULED')
    expect(result.nextFireAt).toBe('2026-07-15T09:00:00Z')
    expect(result.version).toBe(1)
  })

  it('nextFireAt이 null인 룰 fixture를 파싱한다', () => {
    const result = automationRuleResponseSchema.parse(issueCreatedRuleFixture)
    expect(result.triggerType).toBe('ISSUE_CREATED')
    expect(result.nextFireAt).toBeNull()
  })

  it('잘못된 triggerType 값은 ZodError를 throw한다', () => {
    const invalid = { ...issueCreatedRuleFixture, triggerType: 'NOT_A_TRIGGER' }
    expect(() => automationRuleResponseSchema.parse(invalid)).toThrow(ZodError)
  })

  it('필수 필드(id) 누락 시 ZodError를 throw한다', () => {
    const withoutId = Object.fromEntries(
      Object.entries(issueCreatedRuleFixture).filter(([key]) => key !== 'id'),
    )
    expect(() => automationRuleResponseSchema.parse(withoutId)).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// automationRuleResponseSchema — actions·actorUserId (FR-AT-02, 백엔드 계약 1:1)
// ─────────────────────────────────────────────────────────────────────────────

describe('automationRuleResponseSchema — actions·actorUserId (FR-AT-02)', () => {
  it('actions 배열(2건)과 actorUserId를 파싱한다', () => {
    const result = automationRuleResponseSchema.parse(ruleWithActionsFixture)
    expect(result.actions).toHaveLength(2)
    expect(result.actions[0]).toEqual({ type: 'SET_FIELD', config: { field: 'priority', value: 3 } })
    expect(result.actorUserId).toBe('b2c3d4e5-f6a7-4890-9bcd-ef0123456789')
  })

  it('actions가 빈 배열인 룰(EC5 — 트리거만 있는 룰)도 파싱한다', () => {
    const result = automationRuleResponseSchema.parse(scheduledRuleFixture)
    expect(result.actions).toEqual([])
  })

  it('actorUserId 누락 시 ZodError를 throw한다 (non-null 응답 컨벤션)', () => {
    const withoutActor = Object.fromEntries(
      Object.entries(scheduledRuleFixture).filter(([key]) => key !== 'actorUserId'),
    )
    expect(() => automationRuleResponseSchema.parse(withoutActor)).toThrow(ZodError)
  })

  it('actions 누락 시 ZodError를 throw한다', () => {
    const withoutActions = Object.fromEntries(
      Object.entries(scheduledRuleFixture).filter(([key]) => key !== 'actions'),
    )
    expect(() => automationRuleResponseSchema.parse(withoutActions)).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// createAutomationRuleResponseSchema
// ─────────────────────────────────────────────────────────────────────────────

describe('createAutomationRuleResponseSchema', () => {
  it('WEBHOOK 트리거 생성 응답(webhookToken 원문 동봉)을 파싱한다', () => {
    const webhookRule: AutomationRule = {
      ...issueCreatedRuleFixture,
      triggerType: 'WEBHOOK',
      hasWebhookToken: true,
    }
    const result = createAutomationRuleResponseSchema.parse({
      rule: webhookRule,
      webhookToken: 'whk_secret_raw_token_value',
    })
    expect(result.webhookToken).toBe('whk_secret_raw_token_value')
    expect(result.rule.hasWebhookToken).toBe(true)
  })

  it('비-WEBHOOK 트리거 생성 응답은 webhookToken이 null이다', () => {
    const result = createAutomationRuleResponseSchema.parse({
      rule: issueCreatedRuleFixture,
      webhookToken: null,
    })
    expect(result.webhookToken).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// conflicts — 규칙 충돌 정적 분석 응답 (FR-AT-04 D6/D7, backend RuleConflictResponse 1:1 대응)
// create/patch 응답은 conflicts가 항상 존재(충돌 없으면 빈 배열), GET(목록/단건)은 키 자체가 부재.
// ─────────────────────────────────────────────────────────────────────────────

const cycleConflictFixture = {
  type: 'CYCLE',
  severity: 'WARNING',
  ruleIds: ['550e8400-e29b-41d4-a716-446655440000', '660e8400-e29b-41d4-b716-446655440001'],
  detail: '순환 트리거 감지: 룰 A → 룰 B → 룰 A',
} as const

const fieldConflictFixture = {
  type: 'FIELD_CONFLICT',
  severity: 'WARNING',
  ruleIds: ['770e8400-e29b-41d4-8716-446655440002'],
  detail: '동일 필드에 서로 다른 값을 설정하는 룰이 있습니다.',
} as const

const priorityConflictFixture = {
  type: 'PRIORITY_AMBIGUITY',
  severity: 'WARNING',
  ruleIds: ['880e8400-e29b-41d4-9716-446655440003'],
  detail: '우선순위가 모호한 룰이 있습니다.',
} as const

const permissionConflictFixture = {
  type: 'PERMISSION_MISSING',
  severity: 'WARNING',
  ruleIds: ['990e8400-e29b-41d4-a716-446655440004'],
  detail: '실행자에게 필요한 권한이 없습니다.',
} as const

const allConflictFixtures = [
  cycleConflictFixture,
  fieldConflictFixture,
  priorityConflictFixture,
  permissionConflictFixture,
]

describe('createAutomationRuleResponseSchema — conflicts (FR-AT-04 D6/D7)', () => {
  it('4종 충돌(CYCLE/FIELD_CONFLICT/PRIORITY_AMBIGUITY/PERMISSION_MISSING) 각 1건을 파싱한다', () => {
    const result = createAutomationRuleResponseSchema.parse({
      rule: { ...issueCreatedRuleFixture, conflicts: allConflictFixtures },
      webhookToken: null,
    })
    expect(result.rule.conflicts).toHaveLength(4)
    expect(result.rule.conflicts).toEqual(allConflictFixtures)
  })

  it('conflicts가 빈 배열(충돌 없음)이어도 파싱 통과하고 빈 배열이 보존된다', () => {
    const result = createAutomationRuleResponseSchema.parse({
      rule: { ...issueCreatedRuleFixture, conflicts: [] },
      webhookToken: null,
    })
    expect(result.rule.conflicts).toEqual([])
  })

  it('conflicts의 ruleIds는 UUID 문자열 배열, detail은 문자열이다', () => {
    const result = createAutomationRuleResponseSchema.parse({
      rule: { ...issueCreatedRuleFixture, conflicts: [cycleConflictFixture] },
      webhookToken: null,
    })
    const conflicts = result.rule.conflicts ?? []
    expect(conflicts).toHaveLength(1)
    expect(conflicts.every((conflict) => conflict.ruleIds.every((id) => typeof id === 'string'))).toBe(true)
    expect(conflicts.every((conflict) => typeof conflict.detail === 'string')).toBe(true)
  })

  it('conflicts type이 4종 밖 값이면 ZodError를 throw한다', () => {
    const invalid = {
      rule: {
        ...issueCreatedRuleFixture,
        conflicts: [{ ...cycleConflictFixture, type: 'NOT_A_CONFLICT_TYPE' }],
      },
      webhookToken: null,
    }
    expect(() => createAutomationRuleResponseSchema.parse(invalid)).toThrow(ZodError)
  })
})

describe('automationRuleResponseSchema — conflicts 부재 (GET 단건, FR-AT-04 D6/D7)', () => {
  it('GET 단건 응답(conflicts 키 부재)을 파싱하면 conflicts는 undefined다', () => {
    const result = automationRuleResponseSchema.parse(issueCreatedRuleFixture)
    expect(result.conflicts).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// triggerTypeSchema
// ─────────────────────────────────────────────────────────────────────────────

describe('triggerTypeSchema', () => {
  it('6종 트리거 타입 모두 파싱 성공한다', () => {
    const validTypes = ['ISSUE_CREATED', 'ISSUE_UPDATED', 'ISSUE_COMMENTED', 'SCHEDULED', 'WEBHOOK', 'PR_MERGED']
    for (const type of validTypes) {
      expect(() => triggerTypeSchema.parse(type)).not.toThrow()
    }
  })

  it('정의되지 않은 값은 ZodError를 throw한다', () => {
    expect(() => triggerTypeSchema.parse('ISSUE_DELETED')).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// actionTypeSchema — 자동화 액션 타입 5종 (FR-AT-02, SET_FIX_VERSIONS는 FR-AT-07 PR-B)
// ─────────────────────────────────────────────────────────────────────────────

describe('actionTypeSchema', () => {
  it('5종 액션 타입 모두 파싱 성공한다', () => {
    const validTypes = ['SET_FIELD', 'ASSIGN', 'ADD_COMMENT', 'CALL_WEBHOOK', 'SET_FIX_VERSIONS']
    for (const type of validTypes) {
      expect(() => actionTypeSchema.parse(type)).not.toThrow()
    }
  })

  it('정의되지 않은 값은 ZodError를 throw한다', () => {
    expect(() => actionTypeSchema.parse('DELETE_ISSUE')).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// actionResponseSchema — config는 loose record(EC11, 타입별 discriminated union 아님)
// ─────────────────────────────────────────────────────────────────────────────

describe('actionResponseSchema', () => {
  it('SET_FIELD 액션(config value가 숫자)을 파싱한다', () => {
    const result = actionResponseSchema.parse({ type: 'SET_FIELD', config: { field: 'priority', value: 3 } })
    expect(result.type).toBe('SET_FIELD')
    expect(result.config['value']).toBe(3)
  })

  it('ASSIGN 액션(config assigneeId=null, 담당자 해제)을 파싱한다', () => {
    const result = actionResponseSchema.parse({ type: 'ASSIGN', config: { assigneeId: null } })
    expect(result.config['assigneeId']).toBeNull()
  })

  it('CALL_WEBHOOK 액션(config가 중첩 headers 맵을 가짐)을 파싱한다 — 타입별 형태가 달라도 loose record면 통과', () => {
    const result = actionResponseSchema.parse({
      type: 'CALL_WEBHOOK',
      config: { url: 'https://hooks.example.com/x', method: 'POST', headers: { 'X-Token': 'abc' }, body: '' },
    })
    expect(result.config['headers']).toEqual({ 'X-Token': 'abc' })
  })

  it('잘못된 type 값은 ZodError를 throw한다', () => {
    expect(() => actionResponseSchema.parse({ type: 'DELETE_ISSUE', config: {} })).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// serializeTriggerConfig — 트리거 타입별 5종 분기
// ─────────────────────────────────────────────────────────────────────────────

describe('serializeTriggerConfig', () => {
  it('ISSUE_CREATED는 입력을 무시하고 빈 객체를 반환한다', () => {
    expect(serializeTriggerConfig('ISSUE_CREATED', { cron: '무시됨' })).toBe('{}')
  })

  it('ISSUE_COMMENTED는 입력을 무시하고 빈 객체를 반환한다', () => {
    expect(serializeTriggerConfig('ISSUE_COMMENTED')).toBe('{}')
  })

  it('WEBHOOK은 입력을 무시하고 빈 객체를 반환한다', () => {
    expect(serializeTriggerConfig('WEBHOOK')).toBe('{}')
  })

  it('ISSUE_UPDATED는 fields가 있으면 {"fields":[...]}로 직렬화한다', () => {
    expect(serializeTriggerConfig('ISSUE_UPDATED', { fields: ['status', 'assignee'] })).toBe(
      JSON.stringify({ fields: ['status', 'assignee'] }),
    )
  })

  it('ISSUE_UPDATED는 fields가 없으면 빈 객체를 반환한다', () => {
    expect(serializeTriggerConfig('ISSUE_UPDATED')).toBe('{}')
    expect(serializeTriggerConfig('ISSUE_UPDATED', { fields: [] })).toBe('{}')
  })

  it('SCHEDULED는 cron을 {"cron":"..."}로 직렬화한다', () => {
    expect(serializeTriggerConfig('SCHEDULED', { cron: '0 0 9 * * *' })).toBe(
      JSON.stringify({ cron: '0 0 9 * * *' }),
    )
  })

  // targetBranch 입력은 PR-D 몫이라 폼이 그 값을 넘기지 않는다 — 직렬화도 관여하지 않는다
  // (serializeTriggerConfig KDoc ★ 참조). 생성 모드는 base 가 없으므로 빈 객체다.
  it('PR_MERGED는 폼 입력에 관여하지 않고 빈 객체를 반환한다(미지정=전체 브랜치)', () => {
    expect(serializeTriggerConfig('PR_MERGED')).toBe('{}')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// serializeTriggerConfig — baseConfigJson 병합 (편집 저장 시 백엔드 미지 키 보존, 코드리뷰 SUGGESTION 2)
// ─────────────────────────────────────────────────────────────────────────────

describe('serializeTriggerConfig — baseConfigJson 병합', () => {
  it('SCHEDULED: base의 cron은 새 값으로 덮어쓰고 미지 키(extraKey)는 보존한다', () => {
    const result = serializeTriggerConfig(
      'SCHEDULED',
      { cron: '0 0 9 * * *' },
      JSON.stringify({ cron: 'old', extraKey: 'keep' }),
    )
    expect(JSON.parse(result)).toEqual({ cron: '0 0 9 * * *', extraKey: 'keep' })
  })

  it('ISSUE_UPDATED: base의 fields는 새 값으로 덮어쓰고 미지 키(extraKey)는 보존한다', () => {
    const result = serializeTriggerConfig(
      'ISSUE_UPDATED',
      { fields: ['status'] },
      JSON.stringify({ fields: ['old'], extraKey: 'keep' }),
    )
    expect(JSON.parse(result)).toEqual({ fields: ['status'], extraKey: 'keep' })
  })

  it('ISSUE_UPDATED: fields가 비어도 base의 미지 키(extraKey)는 보존한다', () => {
    const result = serializeTriggerConfig(
      'ISSUE_UPDATED',
      { fields: [] },
      JSON.stringify({ fields: ['old'], extraKey: 'keep' }),
    )
    expect(JSON.parse(result)).toEqual({ extraKey: 'keep' })
  })

  // ★ targetBranch 는 PR-D(입력 UI 도입)부터 managed key 다 — 호출부(AutomationRuleFormDialog)가
  // 이제 `{ cron, fields, targetBranch }` 를 넘긴다. config 에 targetBranch 를 지정하지 않으면
  // ISSUE_UPDATED `fields`와 동일한 관례로 base 의 targetBranch 키도 제거한다 — 그래야 사용자가
  // 입력을 비워 "전 브랜치로 되돌리기"를 할 수 있다(PR-C 리뷰 당시엔 입력 UI가 없어 이 제거가
  // "아무도 채우지 않는 값을 지우는" 코드였지만, PR-D가 입력 UI를 도입하며 그 전제가 사라졌다).
  it('PR_MERGED: config 에 targetBranch 를 지정하지 않으면 base 의 targetBranch 도 제거된다(managed key 전환)', () => {
    const result = serializeTriggerConfig(
      'PR_MERGED',
      {},
      JSON.stringify({ targetBranch: 'develop', extraKey: 'keep' }),
    )
    expect(JSON.parse(result)).toEqual({ extraKey: 'keep' })
  })

  it('PR_MERGED: base 가 없으면 빈 객체를 낸다', () => {
    const result = serializeTriggerConfig('PR_MERGED', {})
    expect(JSON.parse(result)).toEqual({})
  })

  it('base가 잘못된 JSON이면 빈 객체로 안전하게 폴백한다', () => {
    const result = serializeTriggerConfig('SCHEDULED', { cron: '0 0 9 * * *' }, '{not-json')
    expect(JSON.parse(result)).toEqual({ cron: '0 0 9 * * *' })
  })

  it('base 미지정 시 기존 동작(빈 객체에서 시작)은 그대로 유지된다', () => {
    expect(serializeTriggerConfig('SCHEDULED', { cron: '0 0 9 * * *' })).toBe(
      JSON.stringify({ cron: '0 0 9 * * *' }),
    )
    expect(serializeTriggerConfig('ISSUE_UPDATED')).toBe('{}')
    expect(serializeTriggerConfig('ISSUE_CREATED', { cron: '무시됨' })).toBe('{}')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// parseActionConfig / serializeActionConfig — config 비대칭(EC1) round-trip
// 응답 config는 객체(parseActionConfig 입력) ↔ 요청 config는 JSON 문자열(serializeActionConfig 출력).
// ─────────────────────────────────────────────────────────────────────────────

describe('parseActionConfig / serializeActionConfig — SET_FIELD', () => {
  it('priority(1~5): 객체 config → formState → JSON 문자열로 round-trip한다', () => {
    const formState = parseActionConfig('SET_FIELD', { field: 'priority', value: 3 })
    expect(formState).toEqual({ field: 'priority', value: 3 })

    const serialized = serializeActionConfig('SET_FIELD', formState)
    expect(JSON.parse(serialized)).toEqual({ field: 'priority', value: 3 })
  })

  it('priority: HTML select의 문자열 값("3")도 Number()로 강제해 숫자로 직렬화한다 (EC9 핵심)', () => {
    const serialized = serializeActionConfig('SET_FIELD', { field: 'priority', value: '3' })
    const parsedBack = JSON.parse(serialized) as { value: unknown }
    expect(parsedBack.value).toBe(3)
    expect(typeof parsedBack.value).toBe('number')
  })

  it('impact(1~3)도 문자열 값을 숫자로 강제 직렬화한다 (EC9)', () => {
    const serialized = serializeActionConfig('SET_FIELD', { field: 'impact', value: '2' })
    expect(JSON.parse(serialized)).toEqual({ field: 'impact', value: 2 })
  })

  it('labels(문자열 배열)는 숫자로 강제되지 않고 배열 그대로 round-trip한다', () => {
    const formState = parseActionConfig('SET_FIELD', { field: 'labels', value: ['a', 'b'] })
    expect(formState).toEqual({ field: 'labels', value: ['a', 'b'] })

    const serialized = serializeActionConfig('SET_FIELD', formState)
    expect(JSON.parse(serialized)).toEqual({ field: 'labels', value: ['a', 'b'] })
  })

  it('summary(문자열)는 그대로 round-trip한다', () => {
    const formState = parseActionConfig('SET_FIELD', { field: 'summary', value: '변경된 제목' })
    const serialized = serializeActionConfig('SET_FIELD', formState)
    expect(JSON.parse(serialized)).toEqual({ field: 'summary', value: '변경된 제목' })
  })

  it('6종 밖 unknown field(EC10)도 값을 텍스트로 보존해 round-trip한다', () => {
    const formState = parseActionConfig('SET_FIELD', { field: 'customX', value: '알 수 없는 필드 값' })
    expect(formState).toEqual({ field: 'customX', value: '알 수 없는 필드 값' })

    const serialized = serializeActionConfig('SET_FIELD', formState)
    expect(JSON.parse(serialized)).toEqual({ field: 'customX', value: '알 수 없는 필드 값' })
  })
})

describe('parseActionConfig / serializeActionConfig — ASSIGN', () => {
  it('assigneeId=null(담당자 해제)을 round-trip 보존한다', () => {
    const formState = parseActionConfig('ASSIGN', { assigneeId: null })
    expect(formState).toEqual({ assigneeId: null })

    const serialized = serializeActionConfig('ASSIGN', formState)
    expect(JSON.parse(serialized)).toEqual({ assigneeId: null })
  })

  it('assigneeId가 uuid 문자열이면 그대로 round-trip한다', () => {
    const formState = parseActionConfig('ASSIGN', { assigneeId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210' })
    expect(formState).toEqual({ assigneeId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210' })

    const serialized = serializeActionConfig('ASSIGN', formState)
    expect(JSON.parse(serialized)).toEqual({ assigneeId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210' })
  })
})

describe('parseActionConfig / serializeActionConfig — ADD_COMMENT', () => {
  it('body({{템플릿}} 포함)를 round-trip 보존한다', () => {
    const formState = parseActionConfig('ADD_COMMENT', { body: '{{ issue.key }} 자동 처리됨' })
    expect(formState).toEqual({ body: '{{ issue.key }} 자동 처리됨' })

    const serialized = serializeActionConfig('ADD_COMMENT', formState)
    expect(JSON.parse(serialized)).toEqual({ body: '{{ issue.key }} 자동 처리됨' })
  })
})

describe('parseActionConfig / serializeActionConfig — CALL_WEBHOOK', () => {
  it('url·method·body를 round-trip 보존하고, headers 응답 맵은 쌍 배열로 파싱된다', () => {
    const responseConfig = {
      url: 'https://hooks.example.com/x',
      method: 'POST',
      headers: { 'X-Token': 'abc' },
      body: '{"issueKey":"ATLAS-1"}',
    }
    const formState = parseActionConfig('CALL_WEBHOOK', responseConfig)
    expect(formState).toEqual({
      url: 'https://hooks.example.com/x',
      method: 'POST',
      headers: [{ key: 'X-Token', value: 'abc' }],
      body: '{"issueKey":"ATLAS-1"}',
    })

    const serialized = serializeActionConfig('CALL_WEBHOOK', formState)
    expect(JSON.parse(serialized)).toEqual(responseConfig)
  })

  it('method·headers·body 미지정 시 기본값(POST·빈 배열·빈 문자열)으로 채운다', () => {
    const formState = parseActionConfig('CALL_WEBHOOK', { url: 'https://hooks.example.com/y' })
    expect(formState).toEqual({ url: 'https://hooks.example.com/y', method: 'POST', headers: [], body: '' })
  })

  it('응답 headers 객체가 여러 건이면 Object.entries 순서대로 쌍 배열로 변환한다', () => {
    const formState = parseActionConfig('CALL_WEBHOOK', {
      url: 'https://hooks.example.com/x',
      headers: { 'X-Token': 'abc', 'X-Other': 'def' },
    })
    expect(formState.headers).toEqual([
      { key: 'X-Token', value: 'abc' },
      { key: 'X-Other', value: 'def' },
    ])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// serializeActionConfig — CALL_WEBHOOK headers 쌍 배열 (코드리뷰 PR #260 CONCERNS C1·C2 회귀 방지)
//
// C1(버그) — "헤더 추가"로 만든 빈 placeholder 행의 키를 채우지 않고 저장하면, 필터 없는 구현은
// 빈 키({"":"..."})까지 실존 헤더로 직렬화해 backend에 전송한다(계약 오염). 키가 blank(trim 후
// 빈 문자열)인 쌍은 직렬화 시 제외해야 한다.
// C2(엣지) — headers가 Record였을 때는 같은 키를 두 번 입력하면 map dedup으로 편집 중 행 하나가
// 조용히 사라졌다. 쌍 배열 모델에서는 편집 중(폼 상태)에는 중복 키가 모두 보존되고, backend 계약이
// map이라 직렬화(JSON 전송) 시에만 마지막 값으로 축약된다(불가피 — 최종 표현은 map이지만 편집 중
// 소실과는 다른 문제).
// ─────────────────────────────────────────────────────────────────────────────

describe('serializeActionConfig — CALL_WEBHOOK headers 쌍 배열 (C1·C2)', () => {
  it('키가 빈 문자열이거나 공백만인 쌍은 직렬화 결과 headers에서 제외된다(C1)', () => {
    const formState = {
      url: 'https://hooks.example.com/x',
      method: 'POST',
      headers: [
        { key: 'X-Token', value: 'abc' },
        { key: '', value: 'unfilled-placeholder' },
        { key: '   ', value: 'whitespace-only-key' },
      ],
      body: '',
    }
    const serialized = serializeActionConfig('CALL_WEBHOOK', formState)
    const parsed = JSON.parse(serialized) as { headers: Record<string, string> }
    expect(parsed.headers).toEqual({ 'X-Token': 'abc' })
  })

  it('중복 키 쌍은 폼 상태 배열에서는 소실 없이 모두 보존되고, 직렬화 시에만 마지막 값으로 축약된다(C2)', () => {
    const formState = {
      url: 'https://hooks.example.com/x',
      method: 'POST',
      headers: [
        { key: 'X-Token', value: 'first' },
        { key: 'X-Token', value: 'second' },
      ],
      body: '',
    }
    // 폼 상태(배열)는 두 쌍 모두 보존 — 편집 중 소실 없음
    expect(formState.headers).toHaveLength(2)

    const serialized = serializeActionConfig('CALL_WEBHOOK', formState)
    const parsed = JSON.parse(serialized) as { headers: Record<string, string> }
    expect(parsed.headers).toEqual({ 'X-Token': 'second' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// parseActionConfig / serializeActionConfig — SET_FIX_VERSIONS (FR-AT-07 PR-B)
//
// fail-closed 핵심 — `versionIds: []`는 backend에서 "Fix Version 전체 해제"를 의미한다.
// `fixVersionsMode`는 UI 전용 상태(와이어에 실리지 않음)이며, undefined(모드 미지정)는
// 항상 replace로 취급해야 한다 — clear가 명시적으로 적혀 있을 때만 파괴적 동작이 성립한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('parseActionConfig / serializeActionConfig — SET_FIX_VERSIONS', () => {
  it('parseActionConfig 는 SET_FIX_VERSIONS 의 versionIds 를 파싱한다', () => {
    const formState = parseActionConfig('SET_FIX_VERSIONS', {
      versionIds: ['11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222'],
    })
    expect(formState.versionIds).toEqual([
      '11111111-1111-4111-8111-111111111111',
      '22222222-2222-4222-8222-222222222222',
    ])
  })

  it('parseActionConfig 는 versionIds 가 비어 있으면 clear 모드로 복원한다', () => {
    const formState = parseActionConfig('SET_FIX_VERSIONS', { versionIds: [] })
    expect(formState).toEqual({ versionIds: [], fixVersionsMode: 'clear' })
  })

  it('parseActionConfig 는 versionIds 가 있으면 replace 모드로 복원한다', () => {
    const formState = parseActionConfig('SET_FIX_VERSIONS', {
      versionIds: ['11111111-1111-4111-8111-111111111111'],
    })
    expect(formState).toEqual({
      versionIds: ['11111111-1111-4111-8111-111111111111'],
      fixVersionsMode: 'replace',
    })
  })

  it('serializeActionConfig 는 clear 모드면 versionIds 를 빈 배열로 낸다', () => {
    const serialized = serializeActionConfig('SET_FIX_VERSIONS', {
      versionIds: ['11111111-1111-4111-8111-111111111111'],
      fixVersionsMode: 'clear',
    })
    expect(JSON.parse(serialized)).toEqual({ versionIds: [] })
  })

  it('serializeActionConfig 는 fixVersionsMode 가 undefined 면 replace 로 취급한다', () => {
    const serialized = serializeActionConfig('SET_FIX_VERSIONS', {
      versionIds: ['11111111-1111-4111-8111-111111111111'],
    })
    expect(JSON.parse(serialized)).toEqual({ versionIds: ['11111111-1111-4111-8111-111111111111'] })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// automationRuleResponseSchema — condition (FR-AT-03, 조건 분기)
// ─────────────────────────────────────────────────────────────────────────────

describe('automationRuleResponseSchema — condition (FR-AT-03)', () => {
  it('condition이 조건 표현식 JSON 문자열이면 파싱된다', () => {
    const result = automationRuleResponseSchema.parse(scheduledRuleFixture)
    expect(result.condition).toBe('{"and":[]}')
  })

  it('condition이 null(조건 없는 룰)이어도 파싱된다', () => {
    const result = automationRuleResponseSchema.parse(issueCreatedRuleFixture)
    expect(result.condition).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// parseConditionExpression / serializeConditionExpression — 빈 트리 (D1, EC2)
// ─────────────────────────────────────────────────────────────────────────────

const EMPTY_TREE: ConditionNode = { kind: 'group', op: 'and', negated: false, children: [] }

describe('parseConditionExpression — 빈 트리 (D1, EC2)', () => {
  it('null이면 빈 And 그룹 트리를 반환한다', () => {
    expect(parseConditionExpression(null)).toEqual(EMPTY_TREE)
  })

  it('{"and":[]}(빈 그룹 와이어 표현)도 동일한 빈 트리로 파싱된다(EC2 왕복)', () => {
    expect(parseConditionExpression('{"and":[]}')).toEqual(EMPTY_TREE)
  })
})

describe('serializeConditionExpression — 빈 트리 (D1)', () => {
  it('빈 트리는 {"and":[]}로 직렬화된다', () => {
    expect(serializeConditionExpression(EMPTY_TREE)).toBe('{"and":[]}')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// parseConditionExpression / serializeConditionExpression — 중첩 And/Or/Not 왕복
// ─────────────────────────────────────────────────────────────────────────────

describe('parseConditionExpression / serializeConditionExpression — 중첩 트리 왕복', () => {
  it('And( 이항 비교, Or( in 비교, Not(단항 EXISTS 비교) ) )을 왕복한다', () => {
    const tree: ConditionNode = {
      kind: 'group',
      op: 'and',
      negated: false,
      children: [
        { kind: 'comparison', field: 'issue.priority', operator: 'GREATER_THAN', value: 3 },
        {
          kind: 'group',
          op: 'or',
          negated: false,
          children: [
            { kind: 'comparison', field: 'issue.labels', operator: 'IN', value: 'urgent' },
            {
              kind: 'group',
              op: 'and',
              negated: true,
              children: [{ kind: 'comparison', field: 'issue.assignee', operator: 'EXISTS' }],
            },
          ],
        },
      ],
    }

    const serialized = serializeConditionExpression(tree)
    const roundTripped = parseConditionExpression(serialized)
    expect(roundTripped).toEqual(tree)
  })

  it('단항 EMPTY(!)도 값 없이 왕복한다', () => {
    const tree: ConditionNode = { kind: 'comparison', field: 'issue.summary', operator: 'EMPTY' }
    expect(parseConditionExpression(serializeConditionExpression(tree))).toEqual(tree)
  })

  it('in 연산자가 역순({"in":[리터럴,{"var":field}]})으로 와도 var 선두 정규형으로 파싱한다(EC4)', () => {
    const reversed = '{"in":[3,{"var":"issue.priority"}]}'
    expect(parseConditionExpression(reversed)).toEqual({
      kind: 'comparison',
      field: 'issue.priority',
      operator: 'IN',
      value: 3,
    })
  })

  it('파싱 실패(잘못된 JSON)는 console.error를 남기고 빈 트리로 폴백한다(§1.13)', () => {
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(parseConditionExpression('{not-json')).toEqual(EMPTY_TREE)
    expect(consoleErrorSpy).toHaveBeenCalled()
    consoleErrorSpy.mockRestore()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// serializeConditionExpression — 백엔드 Condition.kt 와이어 shape 하드코딩 대조 (E1, 계약갭 방지)
// ─────────────────────────────────────────────────────────────────────────────

describe('serializeConditionExpression — 백엔드 와이어 shape 하드코딩 대조 (E1)', () => {
  it('issue.priority > 3 은 var 선두 이항 배열로 직렬화된다(값은 숫자)', () => {
    const node: ConditionNode = { kind: 'comparison', field: 'issue.priority', operator: 'GREATER_THAN', value: 3 }
    expect(serializeConditionExpression(node)).toBe('{">":[{"var":"issue.priority"},3]}')
  })

  it('urgent in issue.labels 는 var 선두 정규형 {"in":[{"var":field},리터럴]}로 직렬화된다', () => {
    const node: ConditionNode = { kind: 'comparison', field: 'issue.labels', operator: 'IN', value: 'urgent' }
    expect(serializeConditionExpression(node)).toBe('{"in":[{"var":"issue.labels"},"urgent"]}')
  })

  it('issue.assignee !!(EXISTS)는 리터럴 없이 {"!!":{"var":field}}로 직렬화된다', () => {
    const node: ConditionNode = { kind: 'comparison', field: 'issue.assignee', operator: 'EXISTS' }
    expect(serializeConditionExpression(node)).toBe('{"!!":{"var":"issue.assignee"}}')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// serializeConditionExpression — 빈 그룹 prune (G2)
// ─────────────────────────────────────────────────────────────────────────────

describe('serializeConditionExpression — 빈 그룹 prune (G2)', () => {
  it('중첩된 빈 Or 그룹은 부모의 children에서 제거된다', () => {
    const tree: ConditionNode = {
      kind: 'group',
      op: 'and',
      negated: false,
      children: [
        { kind: 'comparison', field: 'issue.status', operator: 'EQUALS', value: 'OPEN' },
        { kind: 'group', op: 'or', negated: false, children: [] },
      ],
    }
    expect(serializeConditionExpression(tree)).toBe('{"and":[{"==":[{"var":"issue.status"},"OPEN"]}]}')
  })

  it('최상위 그룹이 비어 있으면 {"and":[]}로 직렬화된다', () => {
    const tree: ConditionNode = { kind: 'group', op: 'or', negated: false, children: [] }
    expect(serializeConditionExpression(tree)).toBe('{"and":[]}')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// serializeConditionExpression — 숫자 강제 (E2, priority 배열 원소까지 적용)
// ─────────────────────────────────────────────────────────────────────────────

describe('serializeConditionExpression — 숫자 강제 (E2)', () => {
  it('issue.priority in [1,2,3]은 배열 원소를 숫자로 강제해 직렬화한다', () => {
    const node: ConditionNode = {
      kind: 'comparison',
      field: 'issue.priority',
      operator: 'IN',
      value: ['1', 2, '3'],
    }
    expect(serializeConditionExpression(node)).toBe('{"in":[{"var":"issue.priority"},[1,2,3]]}')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// automationImportResponseSchema — YAML import 응답 (FR-AT-06 D6, backend AutomationImportResponse 1:1 대응)
// webhookTokens는 새 WEBHOOK 룰이 없으면 키 자체가 생략(@JsonInclude(NON_NULL) + ifEmpty { null }).
// conflicts는 ruleConflictResponseSchema(FR-AT-04 D6/D7 기존 스키마)를 재사용한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('automationImportResponseSchema', () => {
  const base = { created: 1, updated: 2, total: 3, ruleIds: ['550e8400-e29b-41d4-a716-446655440000'] }

  it('webhookTokens 키가 생략된 응답을 파싱한다 (새 WEBHOOK 룰 없음 — 백엔드가 키를 뺀다)', () => {
    const parsed = automationImportResponseSchema.parse({ ...base, conflicts: [] })
    expect(parsed.webhookTokens).toBeUndefined()
  })

  it('webhookTokens 를 파싱한다', () => {
    const parsed = automationImportResponseSchema.parse({
      ...base,
      conflicts: [],
      webhookTokens: [{ ruleId: '550e8400-e29b-41d4-a716-446655440000', name: '웹훅 룰', token: 'secret-token' }],
    })
    expect(parsed.webhookTokens?.[0]?.token).toBe('secret-token')
  })

  it('conflicts 는 기존 ruleConflictResponseSchema 를 재사용한다', () => {
    const parsed = automationImportResponseSchema.parse({
      ...base,
      conflicts: [{ type: 'CYCLE', severity: 'WARNING', ruleIds: ['550e8400-e29b-41d4-a716-446655440000'], detail: '순환' }],
    })
    expect(parsed.conflicts?.[0]?.type).toBe('CYCLE')
  })
})
