// 커스텀 필드 MSW 핸들러 단위 테스트 — stateful CRUD + RFC 7807 에러 + 이슈 customFields 병합 (FR-IS-10)
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { customFieldHandlers, resetCustomFieldStore } from '../custom-field-handlers'
import { issueHandlers, resetIssueState } from '../issue-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 서버 설정
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...customFieldHandlers, ...issueHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetCustomFieldStore()
  resetIssueState()
  localStorage.clear()
})
afterAll(() => server.close())

const BASE_URL = '/api/v1/projects/ATLAS/custom-fields'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 픽스처 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function createTextField(overrides: Record<string, unknown> = {}): Promise<Record<string, unknown>> {
  const res = await fetch(BASE_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      key: 'cf-text',
      name: '텍스트 필드',
      fieldType: 'SHORT_TEXT',
      ...overrides,
    }),
  })
  const body = await res.json() as { data: Record<string, unknown> }
  return body.data
}

// ─────────────────────────────────────────────────────────────────────────────
// GET 목록
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/projects/:projectIdOrKey/custom-fields', () => {
  it('빈 저장소에서 200 + 빈 배열을 반환한다', async () => {
    const res = await fetch(BASE_URL)
    expect(res.status).toBe(200)
    const body = await res.json() as { data: unknown[] }
    expect(body.data).toEqual([])
  })

  it('활성 필드만 반환하며 displayOrder 오름차순으로 정렬된다', async () => {
    await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: 'cf-b', name: 'B 필드', fieldType: 'SHORT_TEXT', displayOrder: 20 }),
    })
    await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: 'cf-a', name: 'A 필드', fieldType: 'NUMBER', displayOrder: 10 }),
    })

    const res = await fetch(BASE_URL)
    const body = await res.json() as { data: Array<{ key: string; displayOrder: number }> }
    expect(body.data).toHaveLength(2)
    expect(body.data[0]?.key).toBe('cf-a')
    expect(body.data[1]?.key).toBe('cf-b')
  })

  it('X-MSW-Seed-CustomFields 헤더로 저장소를 초기화할 수 있다', async () => {
    const seeds = [
      {
        id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
        key: 'seeded-field',
        name: '시드 필드',
        fieldType: 'SHORT_TEXT',
        required: false,
        displayOrder: 1,
        options: [],
        description: null,
      },
    ]
    const res = await fetch(BASE_URL, {
      headers: { 'X-MSW-Seed-CustomFields': encodeURIComponent(JSON.stringify(seeds)) },
    })
    const body = await res.json() as { data: Array<{ key: string }> }
    expect(body.data).toHaveLength(1)
    expect(body.data[0]?.key).toBe('seeded-field')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET 단건
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/projects/:projectIdOrKey/custom-fields/:id', () => {
  it('존재하는 필드를 200으로 반환한다', async () => {
    const created = await createTextField()
    const id = created['id'] as string

    const res = await fetch(`${BASE_URL}/${id}`)
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { id: string; key: string } }
    expect(body.data.id).toBe(id)
    expect(body.data.key).toBe('cf-text')
  })

  it('존재하지 않는 id는 404 + CUSTOM_FIELD_NOT_FOUND errorCode를 반환한다', async () => {
    const res = await fetch(`${BASE_URL}/00000000-0000-4000-8000-000000000001`)
    expect(res.status).toBe(404)
    const body = await res.json() as Record<string, unknown>
    expect(body['errorCode']).toBe('CUSTOM_FIELD_NOT_FOUND')
    // RFC 7807 — message 금지, detail 사용
    expect(Object.prototype.hasOwnProperty.call(body, 'message')).toBe(false)
    expect(typeof body['detail']).toBe('string')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST 생성
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/projects/:projectIdOrKey/custom-fields', () => {
  it('필드를 성공적으로 생성하면 201을 반환한다', async () => {
    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        key: 'priority-level',
        name: '우선순위 레벨',
        fieldType: 'NUMBER',
        required: true,
        displayOrder: 5,
      }),
    })
    expect(res.status).toBe(201)
    const body = await res.json() as { data: Record<string, unknown> }
    expect(body.data['key']).toBe('priority-level')
    expect(body.data['name']).toBe('우선순위 레벨')
    expect(body.data['fieldType']).toBe('NUMBER')
    expect(body.data['required']).toBe(true)
    expect(body.data['displayOrder']).toBe(5)
    expect(typeof body.data['id']).toBe('string')
  })

  it('같은 프로젝트 내 key 중복이면 409 + CUSTOM_FIELD_KEY_DUPLICATE를 반환한다', async () => {
    await createTextField()

    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: 'cf-text', name: '다른 이름', fieldType: 'NUMBER' }),
    })
    expect(res.status).toBe(409)
    const body = await res.json() as Record<string, unknown>
    expect(body['errorCode']).toBe('CUSTOM_FIELD_KEY_DUPLICATE')
    expect(Object.prototype.hasOwnProperty.call(body, 'message')).toBe(false)
    expect(typeof body['detail']).toBe('string')
  })

  it('선택형 fieldType에 options가 0개이면 422 + CUSTOM_FIELD_INVALID_DEFINITION을 반환한다', async () => {
    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        key: 'cf-select',
        name: '선택 필드',
        fieldType: 'SINGLE_SELECT',
        options: [],
      }),
    })
    expect(res.status).toBe(422)
    const body = await res.json() as Record<string, unknown>
    expect(body['errorCode']).toBe('CUSTOM_FIELD_INVALID_DEFINITION')
    expect(Object.prototype.hasOwnProperty.call(body, 'message')).toBe(false)
  })

  it('MULTI_SELECT에 options가 0개이면 422를 반환한다', async () => {
    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: 'cf-multi', name: '다중 선택', fieldType: 'MULTI_SELECT', options: [] }),
    })
    expect(res.status).toBe(422)
  })

  it('RADIO에 options가 0개이면 422를 반환한다', async () => {
    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: 'cf-radio', name: '라디오', fieldType: 'RADIO', options: [] }),
    })
    expect(res.status).toBe(422)
  })

  it('SHORT_TEXT는 options 없이도 정상 생성된다', async () => {
    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: 'cf-short', name: '단문 텍스트', fieldType: 'SHORT_TEXT' }),
    })
    expect(res.status).toBe(201)
  })

  it('권한 403 플래그가 켜진 상태에서 POST는 403 + CUSTOM_FIELD_ACCESS_DENIED를 반환한다', async () => {
    localStorage.setItem('msw-custom-field-403', 'true')
    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: 'cf-denied', name: '거부 필드', fieldType: 'SHORT_TEXT' }),
    })
    expect(res.status).toBe(403)
    const body = await res.json() as Record<string, unknown>
    expect(body['errorCode']).toBe('CUSTOM_FIELD_ACCESS_DENIED')
  })

  it('생성 후 GET 목록에 포함된다 (stateful)', async () => {
    await createTextField()
    const res = await fetch(BASE_URL)
    const body = await res.json() as { data: Array<{ key: string }> }
    expect(body.data.some((f) => f.key === 'cf-text')).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH 수정
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/projects/:projectIdOrKey/custom-fields/:id', () => {
  it('name과 description을 수정하면 200 + 갱신된 필드를 반환한다', async () => {
    const created = await createTextField()
    const id = created['id'] as string

    const res = await fetch(`${BASE_URL}/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: '수정된 이름', description: '새 설명' }),
    })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { name: string; description: string | null } }
    expect(body.data.name).toBe('수정된 이름')
    expect(body.data.description).toBe('새 설명')
  })

  it('fieldType 변경 시도 시 422 + CUSTOM_FIELD_IMMUTABLE_CHANGE를 반환한다', async () => {
    const created = await createTextField()
    const id = created['id'] as string

    const res = await fetch(`${BASE_URL}/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fieldType: 'NUMBER' }),
    })
    expect(res.status).toBe(422)
    const body = await res.json() as Record<string, unknown>
    expect(body['errorCode']).toBe('CUSTOM_FIELD_IMMUTABLE_CHANGE')
    expect(Object.prototype.hasOwnProperty.call(body, 'message')).toBe(false)
  })

  it('key 변경 시도 시 422 + CUSTOM_FIELD_IMMUTABLE_CHANGE를 반환한다', async () => {
    const created = await createTextField()
    const id = created['id'] as string

    const res = await fetch(`${BASE_URL}/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: 'new-key' }),
    })
    expect(res.status).toBe(422)
    const body = await res.json() as Record<string, unknown>
    expect(body['errorCode']).toBe('CUSTOM_FIELD_IMMUTABLE_CHANGE')
  })

  it('존재하지 않는 id는 404를 반환한다', async () => {
    const res = await fetch(`${BASE_URL}/00000000-0000-4000-8000-000000000001`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: '없는 필드' }),
    })
    expect(res.status).toBe(404)
  })

  it('수정 후 GET 단건이 최신값을 반환한다 (stateful)', async () => {
    const created = await createTextField()
    const id = created['id'] as string

    await fetch(`${BASE_URL}/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: '영속 수정' }),
    })

    const res = await fetch(`${BASE_URL}/${id}`)
    const body = await res.json() as { data: { name: string } }
    expect(body.data.name).toBe('영속 수정')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE (소프트 삭제)
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /api/v1/projects/:projectIdOrKey/custom-fields/:id', () => {
  it('존재하는 필드를 삭제하면 204 No Content를 반환한다', async () => {
    const created = await createTextField()
    const id = created['id'] as string

    const res = await fetch(`${BASE_URL}/${id}`, { method: 'DELETE' })
    expect(res.status).toBe(204)
  })

  it('삭제 후 목록에서 제외된다 (소프트 삭제 stateful)', async () => {
    const created = await createTextField()
    const id = created['id'] as string

    await fetch(`${BASE_URL}/${id}`, { method: 'DELETE' })

    const listRes = await fetch(BASE_URL)
    const listBody = await listRes.json() as { data: Array<{ id: string }> }
    expect(listBody.data.some((f) => f.id === id)).toBe(false)
  })

  it('삭제 후 단건 GET은 404를 반환한다', async () => {
    const created = await createTextField()
    const id = created['id'] as string

    await fetch(`${BASE_URL}/${id}`, { method: 'DELETE' })

    const res = await fetch(`${BASE_URL}/${id}`)
    expect(res.status).toBe(404)
  })

  it('존재하지 않는 id를 삭제하면 404를 반환한다', async () => {
    const res = await fetch(`${BASE_URL}/00000000-0000-4000-8000-000000000001`, { method: 'DELETE' })
    expect(res.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 이슈 customFields 병합 — PATCH /api/v1/issues/:key
// ─────────────────────────────────────────────────────────────────────────────

describe('이슈 PATCH customFields 병합', () => {
  it('이슈 생성 시 customFields가 에코된다', async () => {
    const res = await fetch('/api/v1/issues', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectKey: 'ATLAS',
        summary: '커스텀 필드 테스트',
        customFields: { 'cf-text': 'hello' },
      }),
    })
    expect(res.status).toBe(201)
    const body = await res.json() as { data: { customFields: Record<string, unknown> } }
    expect(body.data.customFields['cf-text']).toBe('hello')
  })

  it('PATCH 시 customFields를 키 단위로 병합한다', async () => {
    // 이슈 생성 — customFields 초기값 설정
    const createRes = await fetch('/api/v1/issues', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectKey: 'ATLAS',
        summary: '병합 테스트',
        customFields: { 'cf-a': 'value-a', 'cf-b': 'value-b' },
      }),
    })
    const createBody = await createRes.json() as { data: { key: string } }
    const issueKey = createBody.data.key

    // PATCH — cf-a 수정, cf-b 유지
    const patchRes = await fetch(`/api/v1/issues/${issueKey}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ customFields: { 'cf-a': 'updated-a' } }),
    })
    expect(patchRes.status).toBe(200)
    const patchBody = await patchRes.json() as { data: { customFields: Record<string, unknown> } }
    // cf-a 갱신, cf-b 보존
    expect(patchBody.data.customFields['cf-a']).toBe('updated-a')
    expect(patchBody.data.customFields['cf-b']).toBe('value-b')
  })

  it('PATCH customFields 값이 null이면 해당 키를 삭제한다', async () => {
    const createRes = await fetch('/api/v1/issues', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectKey: 'ATLAS',
        summary: '키 삭제 테스트',
        customFields: { 'cf-keep': 'keep', 'cf-remove': 'remove' },
      }),
    })
    const createBody = await createRes.json() as { data: { key: string } }
    const issueKey = createBody.data.key

    const patchRes = await fetch(`/api/v1/issues/${issueKey}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ customFields: { 'cf-remove': null } }),
    })
    const patchBody = await patchRes.json() as { data: { customFields: Record<string, unknown> } }
    expect(Object.prototype.hasOwnProperty.call(patchBody.data.customFields, 'cf-remove')).toBe(false)
    expect(patchBody.data.customFields['cf-keep']).toBe('keep')
  })

  it('PATCH customFields가 {} (빈 객체)이면 전체를 {} 로 초기화한다', async () => {
    const createRes = await fetch('/api/v1/issues', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectKey: 'ATLAS',
        summary: '전체 초기화 테스트',
        customFields: { 'cf-x': 'x', 'cf-y': 'y' },
      }),
    })
    const createBody = await createRes.json() as { data: { key: string } }
    const issueKey = createBody.data.key

    const patchRes = await fetch(`/api/v1/issues/${issueKey}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ customFields: {} }),
    })
    const patchBody = await patchRes.json() as { data: { customFields: Record<string, unknown> } }
    expect(patchBody.data.customFields).toEqual({})
  })

  it('PATCH에 customFields가 없으면(undefined) 기존값을 유지한다', async () => {
    const createRes = await fetch('/api/v1/issues', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectKey: 'ATLAS',
        summary: '무변경 테스트',
        customFields: { 'cf-z': 'z-value' },
      }),
    })
    const createBody = await createRes.json() as { data: { key: string } }
    const issueKey = createBody.data.key

    // customFields 없이 summary만 수정
    const patchRes = await fetch(`/api/v1/issues/${issueKey}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ summary: '요약만 수정' }),
    })
    const patchBody = await patchRes.json() as { data: { customFields: Record<string, unknown> } }
    expect(patchBody.data.customFields['cf-z']).toBe('z-value')
  })

  it('PATCH 후 GET 단건에서도 customFields가 반영된다 (stateful refetch)', async () => {
    const createRes = await fetch('/api/v1/issues', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectKey: 'ATLAS',
        summary: '리페치 테스트',
        customFields: { 'cf-refetch': 'before' },
      }),
    })
    const createBody = await createRes.json() as { data: { key: string } }
    const issueKey = createBody.data.key

    await fetch(`/api/v1/issues/${issueKey}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ customFields: { 'cf-refetch': 'after' } }),
    })

    const getRes = await fetch(`/api/v1/issues/${issueKey}`)
    const getBody = await getRes.json() as { data: { customFields: Record<string, unknown> } }
    expect(getBody.data.customFields['cf-refetch']).toBe('after')
  })
})
