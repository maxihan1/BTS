// identity-access 사용자 환경설정(테마/언어/날짜포맷) API client 단위 테스트 — MSW + Zod 파싱 검증 (FR-PF-01)
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { preferencesSchema } from './preferences'
import { getPreferences, patchPreferences } from './preferences'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// XSRF 쿠키 설정 / 해제 — PATCH X-XSRF-TOKEN 검증용 (status.test.ts 선례)
// ─────────────────────────────────────────────────────────────────────────────
const XSRF_COOKIE_VALUE = 'test-xsrf-token'

beforeEach(() => {
  document.cookie = `XSRF-TOKEN=${XSRF_COOKIE_VALUE}; path=/`
})

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
})

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — PreferencesResponse (백엔드 PreferencesResponse DTO 1:1)
// ─────────────────────────────────────────────────────────────────────────────
const PREFERENCES_FIXTURE_DEFAULT = {
  theme: 'system',
  locale: 'ko',
  dateFormat: 'iso',
  startPage: 'dashboards',
}

const PREFERENCES_FIXTURE_CUSTOM = {
  theme: 'dark',
  locale: 'en',
  dateFormat: 'us',
  startPage: 'my_issues',
}

// ─────────────────────────────────────────────────────────────────────────────
// T-PF-S. preferencesSchema — Zod enum 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('preferencesSchema', () => {
  it('T-PF-S-1: 기본값 조합(system/ko/iso)을 파싱한다', () => {
    const result = preferencesSchema.parse(PREFERENCES_FIXTURE_DEFAULT)
    expect(result.theme).toBe('system')
    expect(result.locale).toBe('ko')
    expect(result.dateFormat).toBe('iso')
  })

  it('T-PF-S-2: 허용된 다른 조합(dark/en/us)도 파싱한다', () => {
    const result = preferencesSchema.parse(PREFERENCES_FIXTURE_CUSTOM)
    expect(result.theme).toBe('dark')
    expect(result.locale).toBe('en')
    expect(result.dateFormat).toBe('us')
  })

  it.each(['light', 'dark', 'system'])('T-PF-S-3: theme=%s → safeParse success', (theme) => {
    const result = preferencesSchema.safeParse({ ...PREFERENCES_FIXTURE_DEFAULT, theme })
    expect(result.success).toBe(true)
  })

  it.each(['ko', 'en'])('T-PF-S-4: locale=%s → safeParse success', (locale) => {
    const result = preferencesSchema.safeParse({ ...PREFERENCES_FIXTURE_DEFAULT, locale })
    expect(result.success).toBe(true)
  })

  it.each(['iso', 'kr', 'us', 'eu'])('T-PF-S-5: dateFormat=%s → safeParse success', (dateFormat) => {
    const result = preferencesSchema.safeParse({ ...PREFERENCES_FIXTURE_DEFAULT, dateFormat })
    expect(result.success).toBe(true)
  })

  it('T-PF-S-6: theme이 허용 외 값(blue)이면 safeParse fail', () => {
    const result = preferencesSchema.safeParse({ ...PREFERENCES_FIXTURE_DEFAULT, theme: 'blue' })
    expect(result.success).toBe(false)
  })

  it('T-PF-S-7: locale이 허용 외 값(fr)이면 safeParse fail', () => {
    const result = preferencesSchema.safeParse({ ...PREFERENCES_FIXTURE_DEFAULT, locale: 'fr' })
    expect(result.success).toBe(false)
  })

  it('T-PF-S-8: dateFormat이 허용 외 값(jp)이면 safeParse fail', () => {
    const result = preferencesSchema.safeParse({ ...PREFERENCES_FIXTURE_DEFAULT, dateFormat: 'jp' })
    expect(result.success).toBe(false)
  })

  it('T-PF-S-9: 필드 누락 시 safeParse fail (enum은 default 없이 필수)', () => {
    const result = preferencesSchema.safeParse({ theme: 'system', locale: 'ko' })
    expect(result.success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PF-1. getPreferences — GET /api/v1/users/me/preferences
// ─────────────────────────────────────────────────────────────────────────────
describe('getPreferences', () => {
  it('T-PF-1-1: 200 응답을 PreferencesResponse로 파싱해 반환한다', async () => {
    server.use(
      http.get('/api/v1/users/me/preferences', () => HttpResponse.json(PREFERENCES_FIXTURE_CUSTOM)),
    )
    const result = await getPreferences()
    expect(result.theme).toBe('dark')
    expect(result.locale).toBe('en')
    expect(result.dateFormat).toBe('us')
  })

  it('T-PF-1-2: 기본값(system/ko/iso) 응답도 파싱해 반환한다(EC1, preferences 행 없음)', async () => {
    server.use(
      http.get('/api/v1/users/me/preferences', () => HttpResponse.json(PREFERENCES_FIXTURE_DEFAULT)),
    )
    const result = await getPreferences()
    expect(result.theme).toBe('system')
    expect(result.locale).toBe('ko')
    expect(result.dateFormat).toBe('iso')
  })

  it('T-PF-1-3: 401 응답 → ApiError(401) throw', async () => {
    server.use(
      http.get('/api/v1/users/me/preferences', () =>
        HttpResponse.json({ code: 'UNAUTHORIZED' }, { status: 401 }),
      ),
    )
    await expect(getPreferences()).rejects.toBeInstanceOf(ApiError)
    await expect(getPreferences()).rejects.toMatchObject({ status: 401 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PF-2. patchPreferences — PATCH /api/v1/users/me/preferences (부분 수정, 2-state)
// ─────────────────────────────────────────────────────────────────────────────
describe('patchPreferences', () => {
  it('T-PF-2-1: PATCH 후 갱신된 PreferencesResponse를 반환한다', async () => {
    server.use(
      http.patch('/api/v1/users/me/preferences', () => HttpResponse.json(PREFERENCES_FIXTURE_CUSTOM)),
    )
    const result = await patchPreferences({ theme: 'dark' })
    expect(result.theme).toBe('dark')
    expect(result.locale).toBe('en')
    expect(result.dateFormat).toBe('us')
  })

  it('T-PF-2-2: X-XSRF-TOKEN 헤더가 요청에 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.patch('/api/v1/users/me/preferences', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(PREFERENCES_FIXTURE_CUSTOM)
      }),
    )
    await patchPreferences({ theme: 'dark' })
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-PF-2-3: 제공된 필드만 요청 바디에 실린다(2-state, 부재=미변경)', async () => {
    let capturedBody: unknown
    server.use(
      http.patch('/api/v1/users/me/preferences', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(PREFERENCES_FIXTURE_DEFAULT)
      }),
    )
    await patchPreferences({ dateFormat: 'iso' })
    expect(capturedBody).toEqual({ dateFormat: 'iso' })
  })

  it('T-PF-2-4: 400 잘못된 enum 값 → ApiError(400) throw', async () => {
    server.use(
      http.patch('/api/v1/users/me/preferences', () =>
        HttpResponse.json(
          { code: 'PREFERENCES_VALIDATION_FAILED', message: '지원하지 않는 테마입니다.' },
          { status: 400 },
        ),
      ),
    )
    let thrown: unknown
    try {
      await patchPreferences({ theme: 'dark' })
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(400)
    expect((thrown.body as { code?: string } | null)?.code).toBe('PREFERENCES_VALIDATION_FAILED')
  })
})
