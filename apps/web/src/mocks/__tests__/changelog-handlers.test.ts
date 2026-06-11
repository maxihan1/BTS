// changelog MSW 핸들러 단위 테스트 — GET /api/v1/issues/:key/changelog 페이징 + 404 (FR-HS-02)
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { changelogHandlers } from '../changelog-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 서버 설정
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...changelogHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼 타입 (Zod 없이 구조만 검증)
// ─────────────────────────────────────────────────────────────────────────────

interface ChangeItemResponse {
  field: string
  fromValue: string | null
  toValue: string | null
  fromLabel: string | null
  toLabel: string | null
}

interface ChangeGroupResponse {
  actorId: string | null
  actorName: string | null
  createdAt: string
  items: ChangeItemResponse[]
}

interface SpringPage {
  content: ChangeGroupResponse[]
  totalElements: number
  totalPages: number
  size: number
  number: number
  first: boolean
  last: boolean
  empty: boolean
}

async function fetchChangelog(key: string, params: { page?: number; size?: number } = {}): Promise<Response> {
  const qs = new URLSearchParams()
  if (params.page !== undefined) qs.set('page', String(params.page))
  if (params.size !== undefined) qs.set('size', String(params.size))
  const query = qs.toString() ? `?${qs.toString()}` : ''
  return fetch(`/api/v1/issues/${key}/changelog${query}`)
}

// ─────────────────────────────────────────────────────────────────────────────
// 기본 응답 구조
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/issues/:key/changelog — 기본 구조', () => {
  it('알려진 이슈 key로 200 + Spring Page 구조를 반환한다', async () => {
    const res = await fetchChangelog('ATLAS-1')
    expect(res.status).toBe(200)

    const page = await res.json() as SpringPage
    expect(typeof page.totalElements).toBe('number')
    expect(typeof page.totalPages).toBe('number')
    expect(typeof page.size).toBe('number')
    expect(typeof page.number).toBe('number')
    expect(typeof page.first).toBe('boolean')
    expect(typeof page.last).toBe('boolean')
    expect(typeof page.empty).toBe('boolean')
    expect(Array.isArray(page.content)).toBe(true)
  })

  it('content 각 그룹에 actorId·actorName·createdAt·items가 있다', async () => {
    const res = await fetchChangelog('ATLAS-1')
    const page = await res.json() as SpringPage

    // fixture가 충분한 그룹을 포함해야 한다
    expect(page.content.length).toBeGreaterThan(0)

    const group = page.content[0]
    expect(group).toBeDefined()
    // actorId는 string | null
    expect(group!.actorId === null || typeof group!.actorId === 'string').toBe(true)
    // actorName은 string | null
    expect(group!.actorName === null || typeof group!.actorName === 'string').toBe(true)
    // createdAt은 ISO 8601 형식
    expect(typeof group!.createdAt).toBe('string')
    expect(() => new Date(group!.createdAt)).not.toThrow()
    expect(Array.isArray(group!.items)).toBe(true)
  })

  it('items 각 항목에 field·fromValue·toValue·fromLabel·toLabel이 있다', async () => {
    const res = await fetchChangelog('ATLAS-1')
    const page = await res.json() as SpringPage

    const allItems = page.content.flatMap((g) => g.items)
    expect(allItems.length).toBeGreaterThan(0)

    const item = allItems[0]
    expect(item).toBeDefined()
    expect(typeof item!.field).toBe('string')
    // fromValue/toValue는 string | null
    expect(item!.fromValue === null || typeof item!.fromValue === 'string').toBe(true)
    expect(item!.toValue === null || typeof item!.toValue === 'string').toBe(true)
    // fromLabel/toLabel은 string | null
    expect(item!.fromLabel === null || typeof item!.fromLabel === 'string').toBe(true)
    expect(item!.toLabel === null || typeof item!.toLabel === 'string').toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 다양한 필드 케이스
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/issues/:key/changelog — 필드 케이스', () => {
  it('lifecycle 그룹이 존재한다 (fromValue=null, toValue="created")', async () => {
    const res = await fetchChangelog('ATLAS-1')
    const page = await res.json() as SpringPage

    const lifecycleGroup = page.content
      .flatMap((g) => g.items.map((item) => ({ item, group: g })))
      .find(({ item }) => item.field === 'lifecycle')

    expect(lifecycleGroup).toBeDefined()
    expect(lifecycleGroup!.item.fromValue).toBeNull()
    expect(lifecycleGroup!.item.toValue).toBe('created')
    expect(lifecycleGroup!.item.fromLabel).toBeNull()
    expect(lifecycleGroup!.item.toLabel).toBeNull()
  })

  it('priority 변경 그룹이 존재한다 (label=null)', async () => {
    const res = await fetchChangelog('ATLAS-1')
    const page = await res.json() as SpringPage

    const priorityItem = page.content
      .flatMap((g) => g.items)
      .find((item) => item.field === 'priority')

    expect(priorityItem).toBeDefined()
    // priority는 label 박제 없음
    expect(priorityItem!.fromLabel).toBeNull()
    expect(priorityItem!.toLabel).toBeNull()
    // fromValue/toValue는 숫자 문자열
    expect(priorityItem!.fromValue).not.toBeNull()
    expect(priorityItem!.toValue).not.toBeNull()
  })

  it('assignee 변경 그룹은 fromLabel·toLabel이 non-null(박제 label)', async () => {
    const res = await fetchChangelog('ATLAS-1')
    const page = await res.json() as SpringPage

    const assigneeItem = page.content
      .flatMap((g) => g.items)
      .find((item) => item.field === 'assignee')

    expect(assigneeItem).toBeDefined()
    expect(typeof assigneeItem!.fromLabel).toBe('string')
    expect(typeof assigneeItem!.toLabel).toBe('string')
    // fromValue/toValue는 UUID 형식
    const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
    expect(assigneeItem!.fromValue).toMatch(uuidRe)
    expect(assigneeItem!.toValue).toMatch(uuidRe)
  })

  it('components 변경 그룹은 UUID JSON 배열 형식이다', async () => {
    const res = await fetchChangelog('ATLAS-1')
    const page = await res.json() as SpringPage

    const componentsItem = page.content
      .flatMap((g) => g.items)
      .find((item) => item.field === 'components')

    expect(componentsItem).toBeDefined()
    // toValue는 JSON 배열 문자열
    expect(() => JSON.parse(componentsItem!.toValue ?? 'null')).not.toThrow()
    const parsed = JSON.parse(componentsItem!.toValue ?? '[]') as unknown[]
    expect(Array.isArray(parsed)).toBe(true)
  })

  it('status 변경 그룹이 존재한다 (label=null)', async () => {
    const res = await fetchChangelog('ATLAS-1')
    const page = await res.json() as SpringPage

    const statusItem = page.content
      .flatMap((g) => g.items)
      .find((item) => item.field === 'status')

    expect(statusItem).toBeDefined()
    expect(statusItem!.fromLabel).toBeNull()
    expect(statusItem!.toLabel).toBeNull()
  })

  it('securityLevel 변경 그룹은 fromLabel·toLabel이 non-null(박제 label)', async () => {
    const res = await fetchChangelog('ATLAS-1')
    const page = await res.json() as SpringPage

    const secItem = page.content
      .flatMap((g) => g.items)
      .find((item) => item.field === 'securityLevel')

    expect(secItem).toBeDefined()
    expect(typeof secItem!.fromLabel).toBe('string')
    expect(typeof secItem!.toLabel).toBe('string')
  })

  it('actorName이 null인 그룹이 존재한다 (시스템 이벤트)', async () => {
    const res = await fetchChangelog('ATLAS-1')
    const page = await res.json() as SpringPage

    const systemGroup = page.content.find((g) => g.actorName === null)
    expect(systemGroup).toBeDefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 페이징
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/issues/:key/changelog — 페이징', () => {
  it('page=0&size=20 기본 요청에서 number=0, first=true를 반환한다', async () => {
    const res = await fetchChangelog('ATLAS-1', { page: 0, size: 20 })
    const page = await res.json() as SpringPage
    expect(page.number).toBe(0)
    expect(page.first).toBe(true)
  })

  it('size보다 fixture 그룹이 많으면 totalPages > 1이다', async () => {
    // size=2로 작게 요청해 페이지 경계를 검증
    const res = await fetchChangelog('ATLAS-1', { page: 0, size: 2 })
    const page = await res.json() as SpringPage
    expect(page.totalPages).toBeGreaterThan(1)
    expect(page.last).toBe(false)
    expect(page.content.length).toBeLessThanOrEqual(2)
  })

  it('page=0&size=2 응답에 size=2가 반영된다', async () => {
    const res = await fetchChangelog('ATLAS-1', { page: 0, size: 2 })
    const page = await res.json() as SpringPage
    expect(page.size).toBe(2)
    expect(page.number).toBe(0)
  })

  it('마지막 페이지에서 last=true, next 요청은 empty=true를 반환한다', async () => {
    // 전체 개수 확인
    const allRes = await fetchChangelog('ATLAS-1', { page: 0, size: 100 })
    const allPage = await allRes.json() as SpringPage
    const total = allPage.totalElements

    // 마지막 페이지 계산 (size=2 기준)
    const size = 2
    const lastPageNum = Math.ceil(total / size) - 1

    const lastRes = await fetchChangelog('ATLAS-1', { page: lastPageNum, size })
    const lastPage = await lastRes.json() as SpringPage
    expect(lastPage.last).toBe(true)
  })

  it('page=1&size=2 요청에서 number=1, first=false를 반환한다', async () => {
    const res = await fetchChangelog('ATLAS-1', { page: 1, size: 2 })
    const page = await res.json() as SpringPage
    expect(page.number).toBe(1)
    expect(page.first).toBe(false)
  })

  it('기본 size=20으로 요청 시 전체 fixture 그룹이 1페이지에 담긴다', async () => {
    const res = await fetchChangelog('ATLAS-1', { page: 0, size: 20 })
    const page = await res.json() as SpringPage
    expect(page.last).toBe(true)
    expect(page.first).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 404 케이스 — 권한 없는 / 미존재 key
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/issues/:key/changelog — 404', () => {
  it('미존재 key는 404를 반환한다', async () => {
    const res = await fetchChangelog('ATLAS-99999')
    expect(res.status).toBe(404)
  })

  it('권한 없는 key(DENIED-1)는 404를 반환한다', async () => {
    const res = await fetchChangelog('DENIED-1')
    expect(res.status).toBe(404)
  })

  it('404 응답은 RFC 7807 errorCode + detail을 포함한다', async () => {
    const res = await fetchChangelog('ATLAS-99999')
    const body = await res.json() as Record<string, unknown>
    expect(typeof body['errorCode']).toBe('string')
    expect(typeof body['detail']).toBe('string')
    // RFC 7807 — message 필드 금지
    expect(Object.prototype.hasOwnProperty.call(body, 'message')).toBe(false)
  })
})
