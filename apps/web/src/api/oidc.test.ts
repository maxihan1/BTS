// OIDC provider 목록 조회 API 단위 테스트 — fetchOidcProviders Zod 파싱 + 에러 분기 검증
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { fetchOidcProviders } from './oidc'
import { ApiError } from './client'

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

describe('fetchOidcProviders', () => {
  it('provider 목록을 정상 반환한다', async () => {
    server.use(
      http.get('/api/v1/auth/oidc/providers', () =>
        HttpResponse.json({
          providers: [
            { registrationId: 'google', displayName: 'Google' },
            { registrationId: 'github', displayName: 'GitHub' },
          ],
        }),
      ),
    )

    const result = await fetchOidcProviders()

    expect(result).toHaveLength(2)
    expect(result[0]).toEqual({ registrationId: 'google', displayName: 'Google' })
    expect(result[1]).toEqual({ registrationId: 'github', displayName: 'GitHub' })
  })

  it('provider가 0개인 경우 빈 배열을 반환한다', async () => {
    server.use(
      http.get('/api/v1/auth/oidc/providers', () =>
        HttpResponse.json({ providers: [] }),
      ),
    )

    const result = await fetchOidcProviders()
    expect(result).toHaveLength(0)
  })

  it('500 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.get('/api/v1/auth/oidc/providers', () =>
        HttpResponse.json({ error: 'internal_error' }, { status: 500 }),
      ),
    )

    await expect(fetchOidcProviders()).rejects.toBeInstanceOf(ApiError)
  })
})
