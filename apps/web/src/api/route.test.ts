// 도메인 기반 인증 라우팅 API 클라이언트 단위 테스트 — MSW + Zod 파싱 검증 (FR-AU-07)
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { fetchRoute } from './route'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — 백엔드 RouteResponse DTO와 1:1 정합
// ─────────────────────────────────────────────────────────────────────────────

/** 매칭 성공 fixture — SAML (partner.com) */
const SAML_ROUTE_FIXTURE = {
  matched: true,
  type: 'SAML',
  registrationId: 'partner-saml',
  displayName: 'Partner SSO',
}

/** 매칭 성공 fixture — OIDC (acme.com) */
const OIDC_ROUTE_FIXTURE = {
  matched: true,
  type: 'OIDC',
  registrationId: 'acme-oidc',
  displayName: 'Acme Google SSO',
}

/** 매칭 실패 fixture */
const UNMATCHED_ROUTE_FIXTURE = { matched: false }

// ─────────────────────────────────────────────────────────────────────────────
// MSW 오버라이드 — 기본 route-handlers의 store 대신 테스트별 응답 고정
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(
    http.get('/api/v1/auth/route', ({ request }) => {
      const url = new URL(request.url)
      const domain = url.searchParams.get('domain')
      if (domain === 'partner.com') return HttpResponse.json(SAML_ROUTE_FIXTURE)
      if (domain === 'acme.com') return HttpResponse.json(OIDC_ROUTE_FIXTURE)
      return HttpResponse.json(UNMATCHED_ROUTE_FIXTURE)
    }),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// T-RO-1. fetchRoute — 매칭 성공 케이스
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchRoute — 매칭 성공', () => {
  it('T-RO-1a: partner.com → matched:true, type:SAML, registrationId, displayName 파싱', async () => {
    const result = await fetchRoute('partner.com')
    expect(result.matched).toBe(true)
    if (!result.matched) throw new Error('type guard missed')
    expect(result.type).toBe('SAML')
    expect(result.registrationId).toBe('partner-saml')
    expect(result.displayName).toBe('Partner SSO')
  })

  it('T-RO-1b: acme.com → matched:true, type:OIDC 파싱', async () => {
    const result = await fetchRoute('acme.com')
    expect(result.matched).toBe(true)
    if (!result.matched) throw new Error('type guard missed')
    expect(result.type).toBe('OIDC')
    expect(result.registrationId).toBe('acme-oidc')
    expect(result.displayName).toBe('Acme Google SSO')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-RO-2. fetchRoute — 매칭 실패 케이스
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchRoute — 매칭 실패', () => {
  it('T-RO-2a: gmail.com → matched:false 파싱', async () => {
    const result = await fetchRoute('gmail.com')
    expect(result.matched).toBe(false)
  })

  it('T-RO-2b: 빈 문자열 도메인 → matched:false 파싱', async () => {
    const result = await fetchRoute('')
    expect(result.matched).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-RO-3. fetchRoute — URL 구성 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchRoute — URL 구성', () => {
  it('T-RO-3a: domain 쿼리스트링이 URL에 포함된다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/auth/route', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(UNMATCHED_ROUTE_FIXTURE)
      }),
    )
    await fetchRoute('test.example.com')
    expect(capturedUrl).toContain('domain=test.example.com')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-RO-4. fetchRoute — 에러 처리
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchRoute — 에러 처리', () => {
  it('T-RO-4a: 500 서버 에러 시 ApiError를 throw한다', async () => {
    server.use(
      http.get('/api/v1/auth/route', () =>
        HttpResponse.json({ message: 'Internal Server Error' }, { status: 500 }),
      ),
    )
    await expect(fetchRoute('partner.com')).rejects.toBeInstanceOf(ApiError)
  })
})
