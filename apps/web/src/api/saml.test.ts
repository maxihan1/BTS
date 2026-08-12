// SAML IdP 목록 조회 API 단위 테스트 — fetchSamlIdps Zod 파싱 + 에러 분기 검증
import { http, HttpResponse } from 'msw'
import { fetchSamlIdps } from './saml'
import { ApiError } from './client'
import { server } from '@/test/server'

describe('fetchSamlIdps', () => {
  it('IdP 목록을 정상 반환한다', async () => {
    server.use(
      http.get('/api/v1/auth/saml/idps', () =>
        HttpResponse.json({
          idps: [
            { registrationId: 'okta', displayName: 'Okta SSO' },
            { registrationId: 'azure-ad', displayName: 'Azure AD' },
          ],
        }),
      ),
    )

    const result = await fetchSamlIdps()

    expect(result).toHaveLength(2)
    expect(result[0]).toEqual({ registrationId: 'okta', displayName: 'Okta SSO' })
    expect(result[1]).toEqual({ registrationId: 'azure-ad', displayName: 'Azure AD' })
  })

  it('IdP가 0개인 경우 빈 배열을 반환한다', async () => {
    server.use(
      http.get('/api/v1/auth/saml/idps', () =>
        HttpResponse.json({ idps: [] }),
      ),
    )

    const result = await fetchSamlIdps()
    expect(result).toHaveLength(0)
  })

  it('500 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.get('/api/v1/auth/saml/idps', () =>
        HttpResponse.json({ error: 'internal_error' }, { status: 500 }),
      ),
    )

    await expect(fetchSamlIdps()).rejects.toBeInstanceOf(ApiError)
  })
})
