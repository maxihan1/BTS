// 워크플로우 스킴 API client 단위 테스트 — fixture mock + Zod 파싱 검증
import { describe, it, expect } from 'vitest'
import { ZodError } from 'zod'
import {
  schemeResponseSchema,
  schemeDetailResponseSchema,
  mappingResponseSchema,
  assignmentResponseSchema,
  toNullableIssueTypeKey,
} from '../workflow-schemes'
import type {
  SchemeResponse,
  SchemeDetailResponse,
  MappingResponse,
  AssignmentResponse,
  CreateSchemeInput,
  UpdateSchemeInput,
  AddMappingInput,
  AssignSchemeInput,
} from '../workflow-schemes'

// ─────────────────────────────────────────────────────────────────────────────
// Fixtures
// ─────────────────────────────────────────────────────────────────────────────

const mappingFixture: MappingResponse = {
  id: 1,
  issueTypeKey: 'bug',
  issueTypeName: '버그',
  workflowKey: 'bug-tracking',
  workflowName: '버그 추적 워크플로우',
  isDefault: false,
}

const defaultMappingFixture: MappingResponse = {
  id: 2,
  issueTypeKey: null,
  issueTypeName: null,
  workflowKey: 'software-default',
  workflowName: '소프트웨어 개발 기본 워크플로우',
  isDefault: true,
}

const schemeFixture: SchemeResponse = {
  schemeKey: 'scheme-atlas',
  name: 'Atlas 기본 스킴',
  description: 'Atlas 프로젝트 워크플로우 스킴',
  isStandard: false,
  usedByProjectsCount: 2,
  mappingsCount: 3,
}

const schemeDetailFixture: SchemeDetailResponse = {
  schemeKey: 'scheme-atlas',
  name: 'Atlas 기본 스킴',
  description: 'Atlas 프로젝트 워크플로우 스킴',
  isStandard: false,
  usedByProjectsCount: 2,
  mappingsCount: 3,
  mappings: [mappingFixture, defaultMappingFixture],
}

const assignmentFixture: AssignmentResponse = {
  projectKey: 'ATLAS',
  schemeKey: 'scheme-atlas',
  schemeName: 'Atlas 기본 스킴',
}

// ─────────────────────────────────────────────────────────────────────────────
// T2-1. schemeResponseSchema — 5 필드 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('schemeResponseSchema', () => {
  it('T2-1a: 6 필드가 모두 있는 SchemeResponse를 파싱한다', () => {
    const result = schemeResponseSchema.parse(schemeFixture)

    expect(result.schemeKey).toBe('scheme-atlas')
    expect(result.name).toBe('Atlas 기본 스킴')
    expect(result.description).toBe('Atlas 프로젝트 워크플로우 스킴')
    expect(result.isStandard).toBe(false)
    expect(result.usedByProjectsCount).toBe(2)
    expect(result.mappingsCount).toBe(3)
  })

  it('T2-1b: description이 빈 문자열이어도 파싱 성공한다', () => {
    const result = schemeResponseSchema.parse({ ...schemeFixture, description: '' })
    expect(result.description).toBe('')
  })

  it('T2-1c: 필수 필드 누락 시 ZodError를 throw한다', () => {
    expect(() => schemeResponseSchema.parse({ schemeKey: 'only-key' })).toThrow(ZodError)
  })

  it('T2-1d: usedByProjectsCount가 음수면 ZodError를 throw한다', () => {
    expect(() => schemeResponseSchema.parse({ ...schemeFixture, usedByProjectsCount: -1 })).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2-2. mappingResponseSchema — default 매핑 시 issueTypeKey/Name이 null
// ─────────────────────────────────────────────────────────────────────────────
describe('mappingResponseSchema', () => {
  it('T2-2a: 일반 매핑 파싱 — issueTypeKey와 issueTypeName이 string이다', () => {
    const result = mappingResponseSchema.parse(mappingFixture)

    expect(result.id).toBe(1)
    expect(result.issueTypeKey).toBe('bug')
    expect(result.issueTypeName).toBe('버그')
    expect(result.workflowKey).toBe('bug-tracking')
    expect(result.isDefault).toBe(false)
  })

  it('T2-2b: 기본(default) 매핑 파싱 — issueTypeKey/issueTypeName이 null이다', () => {
    const result = mappingResponseSchema.parse(defaultMappingFixture)

    expect(result.issueTypeKey).toBeNull()
    expect(result.issueTypeName).toBeNull()
    expect(result.isDefault).toBe(true)
  })

  it('T2-2c: id 필드 누락 시 ZodError를 throw한다', () => {
    const withoutId = { ...mappingFixture, id: undefined }
    expect(() => mappingResponseSchema.parse(withoutId)).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2-3. schemeDetailResponseSchema — mappings 배열 포함
// ─────────────────────────────────────────────────────────────────────────────
describe('schemeDetailResponseSchema', () => {
  it('T2-3a: mappings 배열을 포함한 SchemeDetailResponse를 파싱한다', () => {
    const result = schemeDetailResponseSchema.parse(schemeDetailFixture)

    expect(result.schemeKey).toBe('scheme-atlas')
    expect(result.mappings).toHaveLength(2)
    expect(result.mappings[0]?.issueTypeKey).toBe('bug')
    expect(result.mappings[1]?.issueTypeKey).toBeNull()
  })

  it('T2-3b: mappings 배열이 비어있어도 파싱 성공한다', () => {
    const result = schemeDetailResponseSchema.parse({ ...schemeDetailFixture, mappings: [] })
    expect(result.mappings).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2-4. assignmentResponseSchema — projectKey + schemeKey + schemeName
// ─────────────────────────────────────────────────────────────────────────────
describe('assignmentResponseSchema', () => {
  it('T2-4a: AssignmentResponse를 파싱한다', () => {
    const result = assignmentResponseSchema.parse(assignmentFixture)

    expect(result.projectKey).toBe('ATLAS')
    expect(result.schemeKey).toBe('scheme-atlas')
    expect(result.schemeName).toBe('Atlas 기본 스킴')
  })

  it('T2-4b: 필수 필드 누락 시 ZodError를 throw한다', () => {
    expect(() => assignmentResponseSchema.parse({ projectKey: 'ATLAS' })).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2-5. toNullableIssueTypeKey sentinel 변환
// ─────────────────────────────────────────────────────────────────────────────
describe('toNullableIssueTypeKey', () => {
  it('T2-5a: "__default__" 값을 null로 변환한다', () => {
    expect(toNullableIssueTypeKey('__default__')).toBeNull()
  })

  it('T2-5b: 일반 값은 그대로 반환한다', () => {
    expect(toNullableIssueTypeKey('bug')).toBe('bug')
    expect(toNullableIssueTypeKey('task')).toBe('task')
    expect(toNullableIssueTypeKey('')).toBe('')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2-6. 타입 컴파일 가드 — Input 인터페이스가 올바른 형태인지 컴파일 시 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('Input 인터페이스 컴파일 가드', () => {
  it('T2-6a: CreateSchemeInput 인터페이스가 schemeKey와 name 필드를 가진다', () => {
    const input: CreateSchemeInput = { schemeKey: 'new-scheme', name: '새 스킴', description: '설명' }
    expect(input.schemeKey).toBe('new-scheme')
    expect(input.name).toBe('새 스킴')
  })

  it('T2-6b: UpdateSchemeInput 인터페이스가 name과 description을 선택 필드로 가진다', () => {
    const input: UpdateSchemeInput = {}
    expect(input.name).toBeUndefined()
  })

  it('T2-6c: AddMappingInput의 issueTypeKey가 null을 허용한다', () => {
    const input: AddMappingInput = { issueTypeKey: null, workflowKey: 'bug-tracking' }
    expect(input.issueTypeKey).toBeNull()
  })

  it('T2-6d: AssignSchemeInput이 schemeKey를 필수로 가진다', () => {
    const input: AssignSchemeInput = { schemeKey: 'scheme-atlas' }
    expect(input.schemeKey).toBe('scheme-atlas')
  })
})
