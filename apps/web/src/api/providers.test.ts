// 로그인 화면용 인증 공급자 목록 조회 API 단위 테스트 — fetchProviders Zod 파싱 + 에러 분기 검증
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { fetchProviders } from './providers'
import { ApiError } from './client'

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

describe('fetchProviders', () => {
  it('{providers:[...]} 래퍼를 언래핑해 배열을 반환한다', async () => {
    server.use(
      http.get('/api/v1/auth/providers', () =>
        HttpResponse.json({
          providers: [
            { id: 'ldap', type: 'LDAP', displayName: 'Ldap', priority: 0, available: true },
            { id: 'local', type: 'LOCAL', displayName: 'Local', priority: 1, available: true },
          ],
        }),
      ),
    )

    const result = await fetchProviders()

    expect(result).toHaveLength(2)
    expect(result[0]).toEqual({
      id: 'ldap',
      type: 'LDAP',
      displayName: 'Ldap',
      priority: 0,
      available: true,
    })
    expect(result[1]).toEqual({
      id: 'local',
      type: 'LOCAL',
      displayName: 'Local',
      priority: 1,
      available: true,
    })
  })

  it('필수 필드(available)가 누락된 응답은 ZodError를 throw한다', async () => {
    server.use(
      http.get('/api/v1/auth/providers', () =>
        HttpResponse.json({
          providers: [
            { id: 'ldap', type: 'LDAP', displayName: 'Ldap', priority: 0 },
          ],
        }),
      ),
    )

    await expect(fetchProviders()).rejects.toThrow()
  })

  it('빈 배열 응답을 정상 처리한다', async () => {
    server.use(
      http.get('/api/v1/auth/providers', () =>
        HttpResponse.json({ providers: [] }),
      ),
    )

    const result = await fetchProviders()
    expect(result).toHaveLength(0)
  })

  it('500 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.get('/api/v1/auth/providers', () =>
        HttpResponse.json({ error: 'internal_error' }, { status: 500 }),
      ),
    )

    await expect(fetchProviders()).rejects.toBeInstanceOf(ApiError)
  })
})
