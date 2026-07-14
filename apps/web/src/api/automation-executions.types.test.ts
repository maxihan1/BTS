// automation-executions Zod 스키마 단위 테스트 — backend RuleExecutionSummaryResponse/RuleExecutionDetailResponse DTO 1:1 대응 검증
import { describe, it, expect } from 'vitest'
import { ZodError } from 'zod'
import {
  ruleExecutionStatusSchema,
  actionOutcomeSchema,
  ruleExecutionSummarySchema,
  ruleExecutionDetailSchema,
} from './automation-executions.types'
import type { RuleExecutionSummary, RuleExecutionDetail, ActionOutcome } from './automation-executions.types'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — RuleExecutionSummaryResponse/RuleExecutionDetailResponse (backend DTO 1:1)
// Zod v4 uuid() 검증 통과를 위해 RFC4122 v4 형식 UUID 사용
// ─────────────────────────────────────────────────────────────────────────────

const summaryFixture: RuleExecutionSummary = {
  id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
  ruleId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  triggerType: 'ISSUE_CREATED',
  issueKey: 'ATLAS-1',
  status: 'SUCCESS',
  actionCount: 2,
  successCount: 2,
  startedAt: '2026-07-10T10:00:00Z',
  finishedAt: '2026-07-10T10:00:01Z',
  replayedFrom: null,
}

const outcomeFixture: ActionOutcome = {
  position: 0,
  actionType: 'SET_FIELD',
  success: true,
  error: null,
}

const detailFixture: RuleExecutionDetail = {
  ...summaryFixture,
  projectKey: 'ATLAS',
  triggerEvent: { issueKey: 'ATLAS-1', type: 'ISSUE_CREATED' },
  outcomes: [outcomeFixture],
}

// ─────────────────────────────────────────────────────────────────────────────
// ruleExecutionStatusSchema
// ─────────────────────────────────────────────────────────────────────────────

describe('ruleExecutionStatusSchema', () => {
  it.each(['SUCCESS', 'PARTIAL', 'FAILED', 'SKIPPED'] as const)('%s 값을 허용한다', (status) => {
    expect(ruleExecutionStatusSchema.parse(status)).toBe(status)
  })

  it('정의되지 않은 값은 reject한다', () => {
    expect(() => ruleExecutionStatusSchema.parse('UNKNOWN')).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// actionOutcomeSchema
// ─────────────────────────────────────────────────────────────────────────────

describe('actionOutcomeSchema', () => {
  it('성공 outcome(error null)을 파싱한다', () => {
    const result = actionOutcomeSchema.parse(outcomeFixture)
    expect(result.error).toBeNull()
    expect(result.success).toBe(true)
  })

  it('실패 outcome(error 문자열)을 파싱한다', () => {
    const failed = { position: 1, actionType: 'CALL_WEBHOOK', success: false, error: 'TIMEOUT' }
    const result = actionOutcomeSchema.parse(failed)
    expect(result.error).toBe('TIMEOUT')
    expect(result.success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ruleExecutionSummarySchema
// ─────────────────────────────────────────────────────────────────────────────

describe('ruleExecutionSummarySchema', () => {
  it('유효 fixture를 파싱한다', () => {
    const result = ruleExecutionSummarySchema.parse(summaryFixture)
    expect(result.status).toBe('SUCCESS')
    expect(result.actionCount).toBe(2)
  })

  it('issueKey null(이슈 무관 실행)을 허용한다', () => {
    const fixture = { ...summaryFixture, issueKey: null }
    const result = ruleExecutionSummarySchema.parse(fixture)
    expect(result.issueKey).toBeNull()
  })

  it('replayedFrom null(최초 실행)을 허용한다', () => {
    const result = ruleExecutionSummarySchema.parse(summaryFixture)
    expect(result.replayedFrom).toBeNull()
  })

  it('replayedFrom uuid(replay 실행)를 허용한다', () => {
    const fixture = { ...summaryFixture, replayedFrom: 'c3d4e5f6-a7b8-4c9d-8e1f-2a3b4c5d6e7f' }
    const result = ruleExecutionSummarySchema.parse(fixture)
    expect(result.replayedFrom).toBe('c3d4e5f6-a7b8-4c9d-8e1f-2a3b4c5d6e7f')
  })

  it('triggerType은 미지 값(향후 트리거 추가)도 허용한다(loose string)', () => {
    const fixture = { ...summaryFixture, triggerType: 'FUTURE_TRIGGER' }
    const result = ruleExecutionSummarySchema.parse(fixture)
    expect(result.triggerType).toBe('FUTURE_TRIGGER')
  })

  it('status가 정의되지 않은 값이면 reject한다', () => {
    const fixture = { ...summaryFixture, status: 'UNKNOWN' }
    expect(() => ruleExecutionSummarySchema.parse(fixture)).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ruleExecutionDetailSchema
// ─────────────────────────────────────────────────────────────────────────────

describe('ruleExecutionDetailSchema — backend RuleExecutionDetailResponse 정합', () => {
  // backend RuleExecutionDetailResponse(RuleExecutionResponses.kt)는 actionCount/successCount를
  // 갖지 않는다(그 두 필드는 RuleExecutionSummaryResponse 전용 집계값이다). detail 스키마가
  // ruleExecutionSummarySchema.extend(...)로 정의되면 이 두 필드가 required가 되어, 실제
  // 백엔드 응답(필드 없음)이 ZodError로 깨진다 — 이 테스트는 그 계약 정합을 고정한다.
  const backendDetailPayload = {
    id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
    ruleId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
    projectKey: 'ATLAS',
    triggerType: 'ISSUE_CREATED',
    triggerEvent: { issueKey: 'ATLAS-1', type: 'ISSUE_CREATED' },
    issueKey: 'ATLAS-1',
    status: 'SUCCESS',
    outcomes: [outcomeFixture],
    replayedFrom: null,
    startedAt: '2026-07-10T10:00:00Z',
    finishedAt: '2026-07-10T10:00:01Z',
  }

  it('actionCount/successCount 없는 실제 backend 응답 형태를 파싱한다', () => {
    const result = ruleExecutionDetailSchema.parse(backendDetailPayload)
    expect(result.projectKey).toBe('ATLAS')
    expect(result.outcomes).toHaveLength(1)
    expect('actionCount' in result).toBe(false)
    expect('successCount' in result).toBe(false)
  })

  it.each(['projectKey', 'triggerEvent', 'outcomes'] as const)('%s가 없으면 reject한다', (field) => {
    const payload: Record<string, unknown> = { ...backendDetailPayload }
    delete payload[field]
    expect(() => ruleExecutionDetailSchema.parse(payload)).toThrow(ZodError)
  })
})

describe('ruleExecutionDetailSchema', () => {
  it('유효 fixture(outcomes 1건)를 파싱한다', () => {
    const result = ruleExecutionDetailSchema.parse(detailFixture)
    expect(result.projectKey).toBe('ATLAS')
    expect(result.outcomes).toHaveLength(1)
    expect(result.outcomes[0]?.actionType).toBe('SET_FIELD')
  })

  it('triggerEvent 객체를 임의 JSON으로 허용한다', () => {
    const fixture = { ...detailFixture, triggerEvent: { nested: { a: 1, b: [1, 2, 3] } } }
    const result = ruleExecutionDetailSchema.parse(fixture)
    expect(result.triggerEvent).toEqual({ nested: { a: 1, b: [1, 2, 3] } })
  })

  it('triggerEvent 배열을 임의 JSON으로 허용한다', () => {
    const fixture = { ...detailFixture, triggerEvent: [1, 2, 3] }
    const result = ruleExecutionDetailSchema.parse(fixture)
    expect(result.triggerEvent).toEqual([1, 2, 3])
  })

  it('triggerEvent 원시값(문자열)을 임의 JSON으로 허용한다', () => {
    const fixture = { ...detailFixture, triggerEvent: 'raw-string-payload' }
    const result = ruleExecutionDetailSchema.parse(fixture)
    expect(result.triggerEvent).toBe('raw-string-payload')
  })

  it('outcomes 빈 배열(모든 액션 실패 이전 SKIPPED 등)을 허용한다', () => {
    const fixture = { ...detailFixture, outcomes: [] }
    const result = ruleExecutionDetailSchema.parse(fixture)
    expect(result.outcomes).toEqual([])
  })
})
