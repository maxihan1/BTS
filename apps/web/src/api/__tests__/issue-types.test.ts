// 이슈 타입 API client 단위 테스트 — fixture mock + Zod 파싱 검증
import { describe, it, expect } from 'vitest'
import { ZodError } from 'zod'
import { issueTypeResponseSchema } from '../issue-types'
import type { IssueTypeResponse } from '../issue-types'

// ─────────────────────────────────────────────────────────────────────────────
// Fixtures — 5 표준 이슈 타입 (backend 시드 데이터와 1:1 대응)
// ─────────────────────────────────────────────────────────────────────────────

const bugFixture: IssueTypeResponse = {
  key: 'bug',
  name: '버그',
  description: '예상치 못한 동작 또는 결함',
  iconUrl: null,
}

const taskFixture: IssueTypeResponse = {
  key: 'task',
  name: '작업',
  description: '일반 작업',
  iconUrl: null,
}

const storyFixture: IssueTypeResponse = {
  key: 'story',
  name: '스토리',
  description: '사용자 스토리',
  iconUrl: null,
}

const epicFixture: IssueTypeResponse = {
  key: 'epic',
  name: '에픽',
  description: '대형 작업 묶음',
  iconUrl: null,
}

const subtaskFixture: IssueTypeResponse = {
  key: 'subtask',
  name: '하위 작업',
  description: '다른 이슈의 하위 작업',
  iconUrl: null,
}

const allFiveTypes = [bugFixture, taskFixture, storyFixture, epicFixture, subtaskFixture]

// ─────────────────────────────────────────────────────────────────────────────
// T3-1. issueTypeResponseSchema — 4 필드 파싱 + iconUrl nullable
// ─────────────────────────────────────────────────────────────────────────────
describe('issueTypeResponseSchema', () => {
  it('T3-1a: 4 필드가 모두 있는 IssueTypeResponse를 파싱한다', () => {
    const result = issueTypeResponseSchema.parse(bugFixture)

    expect(result.key).toBe('bug')
    expect(result.name).toBe('버그')
    expect(result.description).toBe('예상치 못한 동작 또는 결함')
    expect(result.iconUrl).toBeNull()
  })

  it('T3-1b: iconUrl이 URL 문자열일 때도 파싱 성공한다', () => {
    const withIcon = { ...bugFixture, iconUrl: 'https://cdn.example.com/icons/bug.svg' }
    const result = issueTypeResponseSchema.parse(withIcon)
    expect(result.iconUrl).toBe('https://cdn.example.com/icons/bug.svg')
  })

  it('T3-1c: key 필드 누락 시 ZodError를 throw한다', () => {
    expect(() => issueTypeResponseSchema.parse({ name: '버그' })).toThrow(ZodError)
  })

  it('T3-1d: 5 표준 이슈 타입 모두 스키마를 통과한다', () => {
    for (const issueType of allFiveTypes) {
      expect(() => issueTypeResponseSchema.parse(issueType)).not.toThrow()
    }
  })

  it('T3-1e: key가 빈 문자열이면 ZodError를 throw한다', () => {
    expect(() => issueTypeResponseSchema.parse({ ...bugFixture, key: '' })).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3-2. 타입 컴파일 가드 — IssueTypeResponse 인터페이스 형태 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('IssueTypeResponse 타입 컴파일 가드', () => {
  it('T3-2a: IssueTypeResponse 배열을 처리할 수 있다', () => {
    const types: IssueTypeResponse[] = allFiveTypes
    expect(types).toHaveLength(5)

    for (const issueType of types) {
      expect(typeof issueType.key).toBe('string')
      expect(typeof issueType.name).toBe('string')
      expect(typeof issueType.description).toBe('string')
      // iconUrl은 string | null
      expect(issueType.iconUrl === null || typeof issueType.iconUrl === 'string').toBe(true)
    }
  })
})
