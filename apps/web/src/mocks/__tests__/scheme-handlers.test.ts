// 워크플로우 스킴 + 이슈 타입 MSW 핸들러 단위 테스트 — happy + error 시나리오 검증
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { schemeHandlers } from '../scheme-handlers'
import { issueTypeHandlers } from '../issue-type-handlers'

const server = setupServer(...schemeHandlers, ...issueTypeHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/workflow-schemes — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/workflow-schemes — 목록 조회', () => {
  it('S1-1 happy: 6개 스킴 배열을 { data: [...] } 형태로 반환한다', async () => {
    const res = await fetch('/api/v1/workflow-schemes')

    expect(res.status).toBe(200)
    const body = await res.json() as { data: unknown[] }
    expect(Array.isArray(body.data)).toBe(true)
    expect((body.data as unknown[]).length).toBeGreaterThanOrEqual(6)
  })

  it('S1-2 happy: 각 스킴은 key, name, usedByProjectsCount, mappingsCount 필드를 가진다', async () => {
    const res = await fetch('/api/v1/workflow-schemes')
    const body = await res.json() as { data: Array<{ key: string; name: string; usedByProjectsCount: number; mappingsCount: number }> }

    for (const scheme of body.data) {
      expect(typeof scheme.key).toBe('string')
      expect(typeof scheme.name).toBe('string')
      expect(typeof scheme.usedByProjectsCount).toBe('number')
      expect(typeof scheme.mappingsCount).toBe('number')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/workflow-schemes/:schemeKey — 단건 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/workflow-schemes/:schemeKey — 단건 조회', () => {
  it('S2-1 happy: 존재하는 schemeKey로 조회하면 200 + mappings 포함 상세를 반환한다', async () => {
    const res = await fetch('/api/v1/workflow-schemes/software-default-scheme')

    expect(res.status).toBe(200)
    const body = await res.json() as { data: { key: string; mappings: unknown[] } }
    expect(body.data.key).toBe('software-default-scheme')
    expect(Array.isArray(body.data.mappings)).toBe(true)
    expect((body.data.mappings as unknown[]).length).toBeGreaterThan(0)
  })

  it('S2-2 error-404: 존재하지 않는 schemeKey는 404를 반환한다', async () => {
    const res = await fetch('/api/v1/workflow-schemes/not-exist-scheme')

    expect(res.status).toBe(404)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('SCHEME_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/workflow-schemes — 스킴 생성
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/workflow-schemes — 스킴 생성', () => {
  it('S3-1 happy: 유효한 body로 생성하면 201 + 생성된 스킴을 반환한다', async () => {
    const res = await fetch('/api/v1/workflow-schemes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: '신규 스킴', description: '테스트용 스킴' }),
    })

    expect(res.status).toBe(201)
    const body = await res.json() as { data: { key: string; name: string } }
    expect(body.data.name).toBe('신규 스킴')
    expect(typeof body.data.key).toBe('string')
  })

  it('S3-2 error-403: X-Mock-Forbidden 헤더가 있으면 403을 반환한다', async () => {
    const res = await fetch('/api/v1/workflow-schemes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Mock-Forbidden': 'true' },
      body: JSON.stringify({ name: '신규 스킴' }),
    })

    expect(res.status).toBe(403)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('FORBIDDEN')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PUT /api/v1/workflow-schemes/:schemeKey — 스킴 수정
// ─────────────────────────────────────────────────────────────────────────────

describe('PUT /api/v1/workflow-schemes/:schemeKey — 스킴 수정', () => {
  it('S4-1 happy: 커스텀 스킴 이름 수정 → 200 + 갱신된 스킴 반환', async () => {
    const res = await fetch('/api/v1/workflow-schemes/custom-scheme-alpha', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: '수정된 이름' }),
    })

    expect(res.status).toBe(200)
    const body = await res.json() as { data: { key: string; name: string } }
    expect(body.data.key).toBe('custom-scheme-alpha')
    expect(body.data.name).toBe('수정된 이름')
  })

  it('S4-2 error-404: 존재하지 않는 스킴 수정 시 404를 반환한다', async () => {
    const res = await fetch('/api/v1/workflow-schemes/not-exist-scheme', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: '수정' }),
    })

    expect(res.status).toBe(404)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('SCHEME_NOT_FOUND')
  })

  it('S4-3 error-409 SCHEME_STANDARD_FIELD_LOCKED: 표준 스킴의 name 수정 시 409를 반환한다', async () => {
    const res = await fetch('/api/v1/workflow-schemes/software-default-scheme', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: '바꾸려는 표준 이름' }),
    })

    expect(res.status).toBe(409)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('SCHEME_STANDARD_FIELD_LOCKED')
  })

  it('S4-4 happy: 표준 스킴의 description 수정은 허용된다 (200)', async () => {
    const res = await fetch('/api/v1/workflow-schemes/software-default-scheme', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ description: '설명만 변경' }),
    })

    expect(res.status).toBe(200)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/workflow-schemes/:schemeKey — 스킴 삭제
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /api/v1/workflow-schemes/:schemeKey — 스킴 삭제', () => {
  it('S5-1 happy: 사용되지 않는 커스텀 스킴 삭제 → 204', async () => {
    const res = await fetch('/api/v1/workflow-schemes/custom-scheme-beta', {
      method: 'DELETE',
    })

    expect(res.status).toBe(204)
  })

  it('S5-2 error-409 SCHEME_IN_USE: usedByProjectsCount > 0 인 스킴 삭제 시 409를 반환한다', async () => {
    const res = await fetch('/api/v1/workflow-schemes/custom-scheme-alpha', {
      method: 'DELETE',
    })

    expect(res.status).toBe(409)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('SCHEME_IN_USE')
  })

  it('S5-3 error-409 SCHEME_STANDARD_NOT_DELETABLE: 표준 스킴 삭제 시 409를 반환한다', async () => {
    const res = await fetch('/api/v1/workflow-schemes/software-default-scheme', {
      method: 'DELETE',
    })

    expect(res.status).toBe(409)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('SCHEME_STANDARD_NOT_DELETABLE')
  })

  it('S5-4 error-404: 존재하지 않는 스킴 삭제 시 404를 반환한다', async () => {
    const res = await fetch('/api/v1/workflow-schemes/not-exist-scheme', {
      method: 'DELETE',
    })

    expect(res.status).toBe(404)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('SCHEME_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/workflow-schemes/:schemeKey/mappings — 매핑 추가
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/workflow-schemes/:schemeKey/mappings — 매핑 추가', () => {
  it('S6-1 happy: 새 매핑 추가 → 200 + 생성 응답(MappingResponse 형태)', async () => {
    const res = await fetch('/api/v1/workflow-schemes/custom-scheme-beta/mappings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ issueTypeKey: 'epic', workflowKey: 'kanban-basic' }),
    })

    // 백엔드 컨트롤러에 @ResponseStatus 가 없어 기본 200 이다(계약 스냅샷 테스트도 200 으로 단정).
    expect(res.status).toBe(200)
    // 생성 응답은 상세(키·이름)가 아니라 내부 PK 형태다 — 두 DTO 를 섞으면 클라이언트 파싱이 터진다.
    const body = await res.json() as { data: { id: number; schemeId: number; issueTypeId: number | null; workflowId: string; createdAt: string } }
    expect(typeof body.data.schemeId).toBe('number')
    expect(body.data.issueTypeId).toBe(1)
    expect(typeof body.data.workflowId).toBe('string')
    expect(body.data).not.toHaveProperty('issueTypeKey')
  })

  it('S6-2 error-409 MAPPING_DUPLICATE: 이미 존재하는 issueTypeKey 매핑 추가 시 409를 반환한다', async () => {
    const res = await fetch('/api/v1/workflow-schemes/software-default-scheme/mappings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ issueTypeKey: 'bug', workflowKey: 'software-default' }),
    })

    expect(res.status).toBe(409)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('MAPPING_DUPLICATE')
  })

  it('S6-3 error-409 MAPPING_DEFAULT_DUPLICATE: default 매핑(issueTypeKey: null)이 이미 있을 때 409를 반환한다', async () => {
    const res = await fetch('/api/v1/workflow-schemes/software-default-scheme/mappings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ issueTypeKey: null, workflowKey: 'software-default' }),
    })

    expect(res.status).toBe(409)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('MAPPING_DEFAULT_DUPLICATE')
  })

  it('S6-4 error-404: 존재하지 않는 스킴에 매핑 추가 시 404를 반환한다', async () => {
    const res = await fetch('/api/v1/workflow-schemes/not-exist-scheme/mappings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ issueTypeKey: 'bug', workflowKey: 'software-default' }),
    })

    expect(res.status).toBe(404)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('SCHEME_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/workflow-schemes/:schemeKey/mappings/:mappingId — 매핑 삭제
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /api/v1/workflow-schemes/:schemeKey/mappings/:mappingId — 매핑 삭제', () => {
  it('S7-1 happy: 존재하는 mappingId 삭제 → 204', async () => {
    const res = await fetch('/api/v1/workflow-schemes/software-default-scheme/mappings/10', {
      method: 'DELETE',
    })

    expect(res.status).toBe(204)
  })

  it('S7-2 error-404 (스킴 미존재): 존재하지 않는 스킴의 매핑 삭제 → 404', async () => {
    const res = await fetch('/api/v1/workflow-schemes/not-exist-scheme/mappings/1', {
      method: 'DELETE',
    })

    expect(res.status).toBe(404)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('SCHEME_NOT_FOUND')
  })

  it('S7-3 error-404 (매핑 미존재): 존재하지 않는 mappingId 삭제 → 404', async () => {
    const res = await fetch('/api/v1/workflow-schemes/software-default-scheme/mappings/9999', {
      method: 'DELETE',
    })

    expect(res.status).toBe(404)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('MAPPING_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectKey/workflow-scheme — 프로젝트 스킴 할당 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/projects/:projectKey/workflow-scheme — 프로젝트 스킴 할당 조회', () => {
  it('S8-1 happy: 할당된 프로젝트 조회 → 200 + 스킴 객체(프로젝트 키는 URL 에만 있다)', async () => {
    const res = await fetch('/api/v1/projects/ATLAS/workflow-scheme')

    expect(res.status).toBe(200)
    const body = await res.json() as { data: { id: number | null; key: string; name: string; description: string | null; isStandard: boolean } }
    // 백엔드 GET 응답은 SchemeResponse 하나다 — projectKey/schemeName 은 본문에 없다.
    expect(body.data.key).toBe('custom-scheme-alpha')
    expect(body.data.name).toBe('사내 개발팀 커스텀 스킴')
    expect(body.data).not.toHaveProperty('projectKey')
  })

  /**
   * 404 의 의미는 「미할당」이 아니라 「프로젝트 없음」이다 — 백엔드가 미배정 프로젝트에는
   * software-scheme 을 자동 배정해 200 을 돌려주므로(EC-1 D10), 이 엔드포인트로 관측되는 404 는
   * `Project not found` 하나뿐이다. 본문 형태도 백엔드 RFC 7807 (`code`/`detail`) 을 따른다.
   */
  it('S8-2 error-404: 존재하지 않는 프로젝트 조회 → 404 PROJECT_NOT_FOUND', async () => {
    const res = await fetch('/api/v1/projects/NO-SUCH-PROJECT/workflow-scheme')

    expect(res.status).toBe(404)
    const body = await res.json() as { code: string }
    expect(body.code).toBe('PROJECT_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PUT /api/v1/projects/:projectKey/workflow-scheme — 프로젝트 스킴 할당 갱신 (UPSERT)
// ─────────────────────────────────────────────────────────────────────────────

describe('PUT /api/v1/projects/:projectKey/workflow-scheme — 프로젝트 스킴 할당 갱신', () => {
  it('S9-1 happy: 유효한 schemeKey로 UPSERT → 200 + 배정 이력(AssignmentResponse)', async () => {
    const res = await fetch('/api/v1/projects/ATLAS/workflow-scheme', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ schemeKey: 'service-management-scheme' }),
    })

    expect(res.status).toBe(200)
    // PUT 응답은 배정 이력이다 — GET(스킴 객체)과 형태가 다르다는 것이 이 PR 이 봉합한 지점이다.
    const body = await res.json() as { data: { projectId: string; workflowSchemeId: number; assignedAt: string; assignedBy: string } }
    expect(typeof body.data.projectId).toBe('string')
    expect(body.data.workflowSchemeId).toBe(2)
    expect(typeof body.data.assignedAt).toBe('string')
  })

  it('S9-2 error-404: 존재하지 않는 schemeKey로 할당 시 404를 반환한다', async () => {
    const res = await fetch('/api/v1/projects/ATLAS/workflow-scheme', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ schemeKey: 'not-exist-scheme' }),
    })

    expect(res.status).toBe(404)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('SCHEME_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/issue-types — 이슈 타입 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/issue-types — 이슈 타입 목록 조회', () => {
  it('S10-1 happy: 5 표준 이슈 타입을 { data: [...] } 형태로 반환한다', async () => {
    const res = await fetch('/api/v1/issue-types')

    expect(res.status).toBe(200)
    const body = await res.json() as { data: Array<{ key: string; name: string }> }
    expect(Array.isArray(body.data)).toBe(true)
    expect(body.data).toHaveLength(5)
  })

  it('S10-2 happy: 각 이슈 타입은 id, key, name, description, iconName 필드를 가진다', async () => {
    const res = await fetch('/api/v1/issue-types')
    const body = await res.json() as { data: Array<{ id: number; key: string; name: string; description: string; iconName: string | null }> }

    for (const issueType of body.data) {
      expect(typeof issueType.id).toBe('number')
      expect(typeof issueType.key).toBe('string')
      expect(typeof issueType.name).toBe('string')
      expect(typeof issueType.description).toBe('string')
      expect(issueType.iconName === null || typeof issueType.iconName === 'string').toBe(true)
    }
  })

  it('S10-3 happy: bug, story, task, epic, subtask key를 모두 포함한다', async () => {
    const res = await fetch('/api/v1/issue-types')
    const body = await res.json() as { data: Array<{ key: string }> }
    const keys = body.data.map((t) => t.key)

    expect(keys).toContain('bug')
    expect(keys).toContain('story')
    expect(keys).toContain('task')
    expect(keys).toContain('epic')
    expect(keys).toContain('subtask')
  })
})
