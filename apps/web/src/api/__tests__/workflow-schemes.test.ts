// 워크플로우 스킴 API client 단위 테스트 — fixture mock + Zod 파싱 검증
import { describe, it, expect } from 'vitest'
import { ZodError } from 'zod'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  schemeListItemSchema,
  schemeDetailSchema,
  schemeMutationResultSchema,
  mappingDetailSchema,
  assignmentRecordSchema,
  assignedSchemeSchema,
  toNullableIssueTypeKey,
  fetchAssignableWorkflowSchemes,
  fetchProjectAssignment,
  WorkflowSchemeApiError,
} from '../workflow-schemes'
import type {
  SchemeListItem,
  SchemeDetail,
  SchemeMutationResult,
  MappingDetail,
  AssignedScheme,
  AssignmentRecord,
  CreateSchemeInput,
  UpdateSchemeInput,
  AddMappingInput,
  AssignSchemeInput,
} from '../workflow-schemes'

// ─────────────────────────────────────────────────────────────────────────────
// Fixtures
// ─────────────────────────────────────────────────────────────────────────────

const mappingFixture: MappingDetail = {
  id: 1,
  issueTypeKey: 'bug',
  issueTypeName: '버그',
  workflowKey: 'bug-tracking',
  workflowName: '버그 추적 워크플로우',
  isDefault: false,
}

const defaultMappingFixture: MappingDetail = {
  id: 2,
  issueTypeKey: null,
  issueTypeName: null,
  workflowKey: 'software-default',
  workflowName: '소프트웨어 개발 기본 워크플로우',
  isDefault: true,
}

// 픽스처는 계약 스냅샷(docs/contracts/workflow-schemes.snapshot.json)의 형태를 따른다 —
// 프론트가 임의로 만든 형태가 아니라 백엔드가 실제로 내보내는 형태다.
const schemeFixture: SchemeListItem = {
  id: 1,
  key: 'scheme-atlas',
  name: 'Atlas 기본 스킴',
  description: 'Atlas 프로젝트 워크플로우 스킴',
  isStandard: false,
  projectId: null,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  usedByProjectsCount: 2,
  mappingsCount: 3,
  mappings: [],
}

const schemeDetailFixture: SchemeDetail = {
  ...schemeFixture,
  mappings: [mappingFixture, defaultMappingFixture],
}

const mutationResultFixture: SchemeMutationResult = {
  id: 1,
  key: 'scheme-atlas',
  name: 'Atlas 기본 스킴',
  description: 'Atlas 프로젝트 워크플로우 스킴',
  isStandard: false,
  projectId: null,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const assignmentRecordFixture: AssignmentRecord = {
  projectId: '11111111-1111-4111-8111-111111111111',
  workflowSchemeId: 1,
  assignedAt: '2026-01-01T00:00:00Z',
  assignedBy: '22222222-2222-4222-8222-222222222222',
}

const assignmentFixture: AssignedScheme = {
  id: 1,
  key: 'scheme-atlas',
  name: 'Atlas 기본 스킴',
  description: 'Atlas 프로젝트 워크플로우 스킴',
  isStandard: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// T2-1. schemeListItemSchema — 5 필드 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('schemeListItemSchema', () => {
  it('T2-1a: 6 필드가 모두 있는 SchemeResponse를 파싱한다', () => {
    const result = schemeListItemSchema.parse(schemeFixture)

    expect(result.key).toBe('scheme-atlas')
    expect(result.name).toBe('Atlas 기본 스킴')
    expect(result.description).toBe('Atlas 프로젝트 워크플로우 스킴')
    expect(result.isStandard).toBe(false)
    expect(result.usedByProjectsCount).toBe(2)
    expect(result.mappingsCount).toBe(3)
  })

  it('T2-1b: description이 빈 문자열이어도 파싱 성공한다', () => {
    const result = schemeListItemSchema.parse({ ...schemeFixture, description: '' })
    expect(result.description).toBe('')
  })

  it('T2-1c: 필수 필드 누락 시 ZodError를 throw한다', () => {
    expect(() => schemeListItemSchema.parse({ schemeKey: 'only-key' })).toThrow(ZodError)
  })

  it('T2-1d: usedByProjectsCount가 음수면 ZodError를 throw한다', () => {
    expect(() => schemeListItemSchema.parse({ ...schemeFixture, usedByProjectsCount: -1 })).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2-2. mappingDetailSchema — default 매핑 시 issueTypeKey/Name이 null
// ─────────────────────────────────────────────────────────────────────────────
describe('mappingDetailSchema', () => {
  it('T2-2a: 일반 매핑 파싱 — issueTypeKey와 issueTypeName이 string이다', () => {
    const result = mappingDetailSchema.parse(mappingFixture)

    expect(result.id).toBe(1)
    expect(result.issueTypeKey).toBe('bug')
    expect(result.issueTypeName).toBe('버그')
    expect(result.workflowKey).toBe('bug-tracking')
    expect(result.isDefault).toBe(false)
  })

  it('T2-2b: 기본(default) 매핑 파싱 — issueTypeKey/issueTypeName이 null이다', () => {
    const result = mappingDetailSchema.parse(defaultMappingFixture)

    expect(result.issueTypeKey).toBeNull()
    expect(result.issueTypeName).toBeNull()
    expect(result.isDefault).toBe(true)
  })

  it('T2-2c: id 필드 누락 시 ZodError를 throw한다', () => {
    const withoutId = { ...mappingFixture, id: undefined }
    expect(() => mappingDetailSchema.parse(withoutId)).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2-3. schemeDetailSchema — mappings 배열 포함
// ─────────────────────────────────────────────────────────────────────────────
describe('schemeDetailSchema', () => {
  it('T2-3a: mappings 배열을 포함한 SchemeDetailResponse를 파싱한다', () => {
    const result = schemeDetailSchema.parse(schemeDetailFixture)

    expect(result.key).toBe('scheme-atlas')
    expect(result.mappings).toHaveLength(2)
    expect(result.mappings[0]?.issueTypeKey).toBe('bug')
    expect(result.mappings[1]?.issueTypeKey).toBeNull()
  })

  it('T2-3b: mappings 배열이 비어있어도 파싱 성공한다', () => {
    const result = schemeDetailSchema.parse({ ...schemeDetailFixture, mappings: [] })
    expect(result.mappings).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2-3b. schemeMutationResultSchema — 생성·수정 응답. 목록 응답과 형태가 다르다.
// ─────────────────────────────────────────────────────────────────────────────
describe('schemeMutationResultSchema', () => {
  it('생성·수정 응답(카운트 없음)을 파싱한다', () => {
    const result = schemeMutationResultSchema.parse(mutationResultFixture)

    expect(result.key).toBe('scheme-atlas')
    expect(result.isStandard).toBe(false)
  })

  it('목록 스키마는 이 응답을 통과시키지 못한다 — 한 스키마로 두 형태를 덮을 수 없다는 증명', () => {
    // 이 PR 이전에는 두 endpoint 가 같은 스키마를 공유해 카운트 부재가 드러나지 않았다.
    expect(() => schemeListItemSchema.parse(mutationResultFixture)).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2-4. assignmentRecordSchema — 배정 이력(PUT 응답). 배정 조회(GET)와 형태가 다르다.
// ─────────────────────────────────────────────────────────────────────────────
describe('assignmentRecordSchema', () => {
  it('T2-4a: 배정 이력 응답을 파싱한다', () => {
    const result = assignmentRecordSchema.parse(assignmentRecordFixture)

    expect(result.projectId).toBe('11111111-1111-4111-8111-111111111111')
    expect(result.workflowSchemeId).toBe(1)
    expect(result.assignedBy).toBe('22222222-2222-4222-8222-222222222222')
  })

  it('T2-4b: 필수 필드 누락 시 ZodError를 throw한다', () => {
    expect(() => assignmentRecordSchema.parse({ projectId: 'x' })).toThrow(ZodError)
  })

  it('T2-4c: 배정 조회(GET) 응답 형태는 이 스키마를 통과하지 못한다 — 두 형태가 다르다는 증명', () => {
    expect(() => assignmentRecordSchema.parse(assignmentFixture)).toThrow(ZodError)
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
  it('T2-6a: CreateSchemeInput 인터페이스가 key와 name 필드를 가진다 (백엔드 요청 DTO 와 동일 어휘)', () => {
    const input: CreateSchemeInput = { key: 'new-scheme', name: '새 스킴', description: '설명' }
    expect(input.key).toBe('new-scheme')
    expect(input.name).toBe('새 스킴')
  })

  it('T2-6b: UpdateSchemeInput 의 name 은 필수다 (백엔드 UpdateWorkflowSchemeRequest.name 이 non-null)', () => {
    const input: UpdateSchemeInput = { name: '수정된 이름' }
    expect(input.name).toBe('수정된 이름')
    expect(input.description).toBeUndefined()
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

// ─────────────────────────────────────────────────────────────────────────────
// T2-7. assignedSchemeSchema — backend 어휘(key/isDefault) → 프론트 어휘(schemeKey/isStandard) 정규화
// ─────────────────────────────────────────────────────────────────────────────
describe('assignedSchemeSchema', () => {
  it('T2-7a: id와 description이 null이어도 파싱 성공한다', () => {
    const result = assignedSchemeSchema.parse({
      id: null,
      key: 'software-scheme',
      name: '소프트웨어 스킴',
      description: null,
      isStandard: true,
    })

    expect(result.id).toBeNull()
    expect(result.description).toBeNull()
  })

  it('T2-7b: 백엔드 어휘를 그대로 쓴다 — 경계 .transform() 정규화가 사라졌다', () => {
    // 이전에는 백엔드가 key/isDefault 를 내보내고 프론트가 schemeKey/isStandard 로 뒤집었다.
    // 이제 백엔드 뷰 레이어가 key/isStandard 를 직접 내보내므로 변환 지점이 없다 —
    // 변환이 남아 있으면 계약 스냅샷 파싱(workflow-schemes.contract.test.ts)이 먼저 깨진다.
    const result = assignedSchemeSchema.parse({
      id: 1,
      key: 'x',
      name: 'X 스킴',
      description: null,
      isStandard: true,
    })

    expect(result).toEqual({
      id: 1,
      key: 'x',
      name: 'X 스킴',
      description: null,
      isStandard: true,
    })
  })

  it('T2-7c: 옛 백엔드 어휘(isDefault)는 더 이상 통과하지 못한다', () => {
    expect(() =>
      assignedSchemeSchema.parse({ id: 1, key: 'x', name: 'X', description: null, isDefault: true }),
    ).toThrow(ZodError)
  })

  it('T2-7c: 필수 필드 누락 시 ZodError를 throw한다', () => {
    expect(() => assignedSchemeSchema.parse({ key: 'only-key' })).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2-8. fetchAssignableWorkflowSchemes — 프로젝트 스코프 할당 가능 스킴 목록 조회
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchAssignableWorkflowSchemes', () => {
  it('T2-8a: GET /api/v1/projects/:projectKey/assignable-workflow-schemes 를 호출하고 배열을 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects/ATLAS/assignable-workflow-schemes', () =>
        HttpResponse.json({
          data: [
            { id: 1, key: 'software-scheme', name: '소프트웨어 스킴', description: null, isStandard: true },
          ],
        }),
      ),
    )

    const result = await fetchAssignableWorkflowSchemes('ATLAS')

    expect(result).toHaveLength(1)
    expect(result[0]?.key).toBe('software-scheme')
    expect(result[0]?.isStandard).toBe(true)
    expect(result[0]?.id).toBe(1)
  })

  it('T2-8b: 비-2xx 응답 시 에러를 throw한다', async () => {
    server.use(
      http.get('/api/v1/projects/ATLAS/assignable-workflow-schemes', () =>
        HttpResponse.json({ message: 'Unauthorized' }, { status: 401 }),
      ),
    )

    await expect(fetchAssignableWorkflowSchemes('ATLAS')).rejects.toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchProjectAssignment — 404 의 의미
//
// ★ 예전에는 404 를 「스킴 미할당」으로 읽어 `null` 을 돌려줬다. 백엔드는 그렇게 답하지 않는다 —
//   `WorkflowSchemeApplicationService.findAssignedScheme` 이 배정이 없으면 software-scheme 을
//   **자동 배정하고 그것을 반환**한다(EC-1 D10). 그래서 이 엔드포인트의 404 는
//   `ProjectWorkflowSchemeController.getAssignedScheme` 의 `Project not found` 하나뿐이다.
//
//   그 오독의 증상은 조용했다 — 존재하지 않는 프로젝트 URL 로 들어가면 "이 프로젝트는 아직
//   워크플로우 스킴이 할당되지 않았습니다" 라는 **틀린 안내**가 떴다.
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchProjectAssignment', () => {
  it('404 를 「미할당」으로 삼키지 않고 에러로 전파한다 (404 = 프로젝트 없음)', async () => {
    server.use(
      http.get('/api/v1/projects/NOPE/workflow-scheme', () =>
        HttpResponse.json({ code: 'PROJECT_NOT_FOUND', detail: '프로젝트를 찾을 수 없습니다' }, { status: 404 }),
      ),
    )

    await expect(fetchProjectAssignment('NOPE')).rejects.toBeInstanceOf(WorkflowSchemeApiError)
    await expect(fetchProjectAssignment('NOPE')).rejects.toMatchObject({ status: 404 })
  })

  it('대조군 — 200 이면 배정된 스킴을 그대로 돌려준다', async () => {
    server.use(
      http.get('/api/v1/projects/ATLAS/workflow-scheme', () =>
        HttpResponse.json({
          data: { id: 1, key: 'software-scheme', name: '소프트웨어 스킴', description: null, isStandard: true },
        }),
      ),
    )

    const result = await fetchProjectAssignment('ATLAS')

    expect(result.key).toBe('software-scheme')
  })
})
