// 사용자 검색 API 클라이언트 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { ZodError } from 'zod'
import { server } from '@/test/server'
import { userSummarySchema, searchUsers } from '../users'
import type { UserSummary } from '../users'

// ─────────────────────────────────────────────────────────────────────────────
// Fixtures — backend UserSummaryResponse: id/username/displayName/email
// ─────────────────────────────────────────────────────────────────────────────

const userFixture = {
  id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
  username: 'maxi.han',
  displayName: 'Maxi Han',
  email: 'maxi@example.com',
}

const userFixtureNullable = {
  id: 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff',
  username: 'john.doe',
  displayName: null,
  email: null,
}

beforeEach(() => {
  server.use(
    // GET /api/v1/users?query= → 래핑 없는 배열
    http.get('/api/v1/users', ({ request }) => {
      const url = new URL(request.url)
      const query = url.searchParams.get('query') ?? ''
      if (query === 'empty') {
        return HttpResponse.json([])
      }
      return HttpResponse.json([userFixture, userFixtureNullable])
    }),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// T-U-1. userSummarySchema — id/username/displayName/email
// ─────────────────────────────────────────────────────────────────────────────
describe('userSummarySchema', () => {
  it('T-U-1a: 4 필드 모두 있는 사용자 요약을 파싱한다', () => {
    const result = userSummarySchema.parse(userFixture)

    expect(result.id).toBe('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')
    expect(result.username).toBe('maxi.han')
    expect(result.displayName).toBe('Maxi Han')
    expect(result.email).toBe('maxi@example.com')
  })

  it('T-U-1b: displayName/email이 null이어도 파싱 성공한다', () => {
    const result = userSummarySchema.parse(userFixtureNullable)

    expect(result.displayName).toBeNull()
    expect(result.email).toBeNull()
  })

  it('T-U-1c: 필수 필드(id) 누락 시 ZodError를 throw한다', () => {
    expect(() => userSummarySchema.parse({ username: 'test' })).toThrow(ZodError)
  })

  it('T-U-1d: 필수 필드(username) 누락 시 ZodError를 throw한다', () => {
    expect(() => userSummarySchema.parse({ id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee' })).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-U-2. searchUsers — GET /api/v1/users?query=
// ─────────────────────────────────────────────────────────────────────────────
describe('searchUsers', () => {
  it('T-U-2a: UserSummary 배열을 반환하며 id/username/displayName/email을 포함한다', async () => {
    const result = await searchUsers('maxi')

    expect(result).toHaveLength(2)
    expect(result[0]?.id).toBe('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')
    expect(result[0]?.username).toBe('maxi.han')
    expect(result[0]?.displayName).toBe('Maxi Han')
    expect(result[1]?.displayName).toBeNull()
    expect(result[1]?.email).toBeNull()
  })

  it('T-U-2b: 빈 배열도 반환한다', async () => {
    const result = await searchUsers('empty')
    expect(result).toHaveLength(0)
  })

  it('T-U-2c: query 파라미터가 URL에 포함된다', async () => {
    let capturedQuery: string | null = null
    server.use(
      http.get('/api/v1/users', ({ request }) => {
        const url = new URL(request.url)
        capturedQuery = url.searchParams.get('query')
        return HttpResponse.json([])
      }),
    )
    await searchUsers('hello')
    expect(capturedQuery).toBe('hello')
  })

  it('T-U-2d: 서버 401 응답 시 에러를 throw한다', async () => {
    server.use(
      http.get('/api/v1/users', () => {
        return HttpResponse.json({ error: 'unauthorized' }, { status: 401 })
      }),
    )
    await expect(searchUsers('test')).rejects.toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-U-3. 타입 컴파일 가드
// ─────────────────────────────────────────────────────────────────────────────
describe('타입 컴파일 가드', () => {
  it('T-U-3a: UserSummary 타입이 4 필드를 가진다', () => {
    const user: UserSummary = userFixture
    expect(user.id).toBe('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')
  })

  it('T-U-3b: UserSummary 타입에서 displayName/email은 nullable이다', () => {
    const user: UserSummary = userFixtureNullable
    expect(user.displayName).toBeNull()
    expect(user.email).toBeNull()
  })
})
