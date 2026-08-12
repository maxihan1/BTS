// OIDC provider 목록 조회 API 단위 테스트 — fetchOidcProviders Zod 파싱 + 에러 분기 검증
import { http, HttpResponse } from 'msw'
import { fetchOidcProviders } from './oidc'
import { ApiError } from './client'
// 전역 서버를 쓴다 — 로컬 `setupServer` 를 함께 띄우면 인스턴스 2개가 동시에 listen 해
// **같은 요청이 두 번 디스패치**된다(`msw-single-setupserver.test.ts` 참조).
// 생명주기(listen · resetHandlers · close)는 `src/test/setup.ts` 가 전담한다.
import { server } from '@/test/server'

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
