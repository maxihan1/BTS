// automation-rules Zod 스키마 + 직렬화 헬퍼 단위 테스트 — backend AutomationRuleResponse DTO 1:1 대응 검증
import { describe, it, expect } from 'vitest'
import { ZodError } from 'zod'
import {
  automationRuleResponseSchema,
  createAutomationRuleResponseSchema,
  triggerTypeSchema,
  serializeTriggerConfig,
} from './automation-rules.types'
import type { AutomationRule } from './automation-rules.types'

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
  hasWebhookToken: false,
  nextFireAt: null,
  createdBy: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  createdAt: '2026-07-10T10:00:00Z',
  updatedAt: '2026-07-10T10:00:00Z',
  version: 1,
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
// triggerTypeSchema
// ─────────────────────────────────────────────────────────────────────────────

describe('triggerTypeSchema', () => {
  it('5종 트리거 타입 모두 파싱 성공한다', () => {
    const validTypes = ['ISSUE_CREATED', 'ISSUE_UPDATED', 'ISSUE_COMMENTED', 'SCHEDULED', 'WEBHOOK']
    for (const type of validTypes) {
      expect(() => triggerTypeSchema.parse(type)).not.toThrow()
    }
  })

  it('정의되지 않은 값은 ZodError를 throw한다', () => {
    expect(() => triggerTypeSchema.parse('ISSUE_DELETED')).toThrow(ZodError)
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
