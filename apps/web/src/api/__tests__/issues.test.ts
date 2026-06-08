// issueResponseSchema Zod 파싱 단위 테스트 — restrictedFields/noneditableFields 포함 (FR-PM-07 T3)
import { describe, it, expect } from 'vitest'
import { ZodError } from 'zod'
import { issueResponseSchema } from '../issues'

// ─────────────────────────────────────────────────────────────────────────────
// Fixtures — backend IssueResponse DTO 필드와 1:1
// ─────────────────────────────────────────────────────────────────────────────

const BASE_ISSUE = {
  key: 'ATLAS-1',
  id: '11111111-1111-4111-a111-111111111111',
  projectKey: 'ATLAS',
  summary: '테스트 이슈',
  currentStateKey: 'TODO',
  reporterId: '22222222-2222-4222-a222-222222222222',
  assigneeId: null,
  version: 0,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
  typeId: 1,
  typeKey: 'BUG',
  typeName: '버그',
  description: null,
  descriptionHtml: null,
  priority: 3,
  priorityName: 'Medium',
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// T-IS-1. restrictedFields — 열람 마스킹된 필드 키 목록
// ─────────────────────────────────────────────────────────────────────────────

describe('issueResponseSchema — restrictedFields', () => {
  it('T-IS-1a: restrictedFields 배열을 파싱한다', () => {
    const result = issueResponseSchema.parse({
      ...BASE_ISSUE,
      restrictedFields: ['description', 'environment'],
    })
    expect(result.restrictedFields).toEqual(['description', 'environment'])
  })

  it('T-IS-1b: restrictedFields 빈 배열을 파싱한다', () => {
    const result = issueResponseSchema.parse({
      ...BASE_ISSUE,
      restrictedFields: [],
    })
    expect(result.restrictedFields).toEqual([])
  })

  it('T-IS-1c: restrictedFields 필드가 없으면 기본값 빈 배열로 파싱된다', () => {
    const result = issueResponseSchema.parse({ ...BASE_ISSUE })
    expect(result.restrictedFields).toEqual([])
  })

  it('T-IS-1d: restrictedFields 항목이 string이 아니면 ZodError를 throw한다', () => {
    expect(() =>
      issueResponseSchema.parse({
        ...BASE_ISSUE,
        restrictedFields: [123, 456],
      }),
    ).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IS-2. noneditableFields — 보이지만 편집 불가인 필드 키 목록
// ─────────────────────────────────────────────────────────────────────────────

describe('issueResponseSchema — noneditableFields', () => {
  it('T-IS-2a: noneditableFields 배열을 파싱한다', () => {
    const result = issueResponseSchema.parse({
      ...BASE_ISSUE,
      noneditableFields: ['priority', 'labels'],
    })
    expect(result.noneditableFields).toEqual(['priority', 'labels'])
  })

  it('T-IS-2b: noneditableFields 빈 배열을 파싱한다', () => {
    const result = issueResponseSchema.parse({
      ...BASE_ISSUE,
      noneditableFields: [],
    })
    expect(result.noneditableFields).toEqual([])
  })

  it('T-IS-2c: noneditableFields 필드가 없으면 기본값 빈 배열로 파싱된다', () => {
    const result = issueResponseSchema.parse({ ...BASE_ISSUE })
    expect(result.noneditableFields).toEqual([])
  })

  it('T-IS-2d: noneditableFields 항목이 string이 아니면 ZodError를 throw한다', () => {
    expect(() =>
      issueResponseSchema.parse({
        ...BASE_ISSUE,
        noneditableFields: [true, false],
      }),
    ).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IS-3. restrictedFields + noneditableFields 동시 존재
// ─────────────────────────────────────────────────────────────────────────────

describe('issueResponseSchema — restrictedFields + noneditableFields 동시 파싱', () => {
  it('T-IS-3a: 두 필드가 모두 있으면 각각 올바르게 파싱된다', () => {
    const result = issueResponseSchema.parse({
      ...BASE_ISSUE,
      restrictedFields: ['description'],
      noneditableFields: ['priority', 'summary'],
    })
    expect(result.restrictedFields).toEqual(['description'])
    expect(result.noneditableFields).toEqual(['priority', 'summary'])
  })

  it('T-IS-3b: 두 필드가 모두 없으면 각각 빈 배열 기본값이 적용된다', () => {
    const result = issueResponseSchema.parse({ ...BASE_ISSUE })
    expect(result.restrictedFields).toEqual([])
    expect(result.noneditableFields).toEqual([])
  })
})
