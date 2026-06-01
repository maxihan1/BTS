// identity-access BC 사용자 목록 API client 단위 테스트 — MSW + Zod 파싱 검증
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { userSummarySchema, fetchUsers } from './users'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — UserSummary (displayName/email nullable 케이스 포함)
// ─────────────────────────────────────────────────────────────────────────────
const userFixtureFull = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  username: 'alice',
  displayName: '김앨리스',
  email: 'alice@example.com',
}

const userFixtureMinimal = {
  id: 'b2c3d4e5-f6a7-4891-bcde-ef2345678901',
  username: 'bob',
  displayName: null,
  email: null,
}

beforeEach(() => {
  server.use(
    http.get('/api/v1/users', ({ request }) => {
      const url = new URL(request.url)
      const query = url.searchParams.get('query') ?? ''
      if (query === 'alice') {
        return HttpResponse.json([userFixtureFull])
      }
      return HttpResponse.json([userFixtureFull, userFixtureMinimal])
    }),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// T-US-1. userSummarySchema — Zod 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('userSummarySchema', () => {
  it('T-US-1a: 모든 필드가 있는 UserSummary를 파싱한다', () => {
    const result = userSummarySchema.parse(userFixtureFull)
    expect(result.id).toBe('a1b2c3d4-e5f6-4890-abcd-ef1234567890')
    expect(result.username).toBe('alice')
    expect(result.displayName).toBe('김앨리스')
    expect(result.email).toBe('alice@example.com')
  })

  it('T-US-1b: displayName/email이 null이어도 파싱 성공한다', () => {
    const result = userSummarySchema.parse(userFixtureMinimal)
    expect(result.displayName).toBeNull()
    expect(result.email).toBeNull()
  })

  it('T-US-1c: id가 UUID 형식이 아니면 ZodError를 throw한다', () => {
    expect(() => userSummarySchema.parse({ ...userFixtureFull, id: 'not-a-uuid' })).toThrow()
  })

  it('T-US-1d: username이 빈 문자열이면 ZodError를 throw한다', () => {
    expect(() => userSummarySchema.parse({ ...userFixtureFull, username: '' })).toThrow()
  })

  it('T-US-1e: id 필드 누락 시 ZodError를 throw한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { id: _id, ...without } = userFixtureFull
    expect(() => userSummarySchema.parse(without)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-US-2. fetchUsers — GET /api/v1/users, 배열 직접 응답 (래퍼 없음)
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchUsers', () => {
  it('T-US-2a: query 없이 호출 시 전체 사용자 배열을 반환한다', async () => {
    const result = await fetchUsers()
    expect(result).toHaveLength(2)
    expect(result[0]?.username).toBe('alice')
    expect(result[1]?.username).toBe('bob')
  })

  it('T-US-2b: query 전달 시 query 쿼리스트링이 URL에 포함된다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/users', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json([userFixtureFull])
      }),
    )
    await fetchUsers('alice')
    expect(capturedUrl).toContain('query=alice')
  })

  it('T-US-2c: 응답이 UserSummary[] 타입으로 파싱된다', async () => {
    const result = await fetchUsers()
    for (const user of result) {
      expect(typeof user.id).toBe('string')
      expect(typeof user.username).toBe('string')
    }
  })

  it('T-US-2d: 500 서버 에러 시 ApiError를 throw한다', async () => {
    server.use(
      http.get('/api/v1/users', () =>
        HttpResponse.json({ message: 'Internal Server Error' }, { status: 500 }),
      ),
    )
    await expect(fetchUsers()).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-US-3. fetchUsersByIds — GET /api/v1/users?ids=, id 다건 조회
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchUsersByIds', () => {
  it('T-US-3a: ids 배열 전달 시 ids 쿼리스트링이 쉼표로 결합돼 URL에 포함된다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/users', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json([userFixtureFull])
      }),
    )
    const { fetchUsersByIds } = await import('./users')
    await fetchUsersByIds([
      'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
      'b2c3d4e5-f6a7-4891-bcde-ef2345678901',
    ])
    expect(capturedUrl).toContain('ids=')
    expect(capturedUrl).toContain('a1b2c3d4-e5f6-4890-abcd-ef1234567890')
    expect(capturedUrl).toContain('b2c3d4e5-f6a7-4891-bcde-ef2345678901')
  })

  it('T-US-3b: ids가 빈 배열이면 네트워크 호출 없이 [] 반환한다', async () => {
    let fetchCalled = false
    server.use(
      http.get('/api/v1/users', () => {
        fetchCalled = true
        return HttpResponse.json([userFixtureFull])
      }),
    )
    const { fetchUsersByIds } = await import('./users')
    const result = await fetchUsersByIds([])
    expect(fetchCalled).toBe(false)
    expect(result).toEqual([])
  })

  it('T-US-3c: 응답을 UserSummary[] 타입으로 파싱한다', async () => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json([userFixtureFull])),
    )
    const { fetchUsersByIds } = await import('./users')
    const result = await fetchUsersByIds(['a1b2c3d4-e5f6-4890-abcd-ef1234567890'])
    expect(result).toHaveLength(1)
    expect(result[0]?.username).toBe('alice')
  })
})
