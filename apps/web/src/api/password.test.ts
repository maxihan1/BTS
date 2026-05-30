// 비밀번호 변경 API 클라이언트 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 + CSRF 헤더 검증
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from './client'
import { changePassword } from './password'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 초기화 — 각 테스트 전 기본 핸들러 등록
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  // 기본 성공 응답 — 개별 테스트에서 server.use()로 덮어쓸 수 있음
  server.use(
    http.post('/api/v1/users/me/password', () => {
      return HttpResponse.json({ changed: true }, { status: 200 })
    }),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-1. 성공 200 — Zod 파싱 후 { changed: true } 반환
// ─────────────────────────────────────────────────────────────────────────────

describe('changePassword — 성공 200', () => {
  it('T1-1a: 200 { changed: true } 응답을 Zod로 파싱해 반환한다', async () => {
    document.cookie = 'XSRF-TOKEN=test-token'
    const result = await changePassword({
      currentPassword: 'OldPass123!',
      newPassword: 'NewPass456@',
    })
    expect(result.changed).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-2. 실패 400 — 각 code별 ApiError throw + body.code/violations 보존
// ─────────────────────────────────────────────────────────────────────────────

describe('changePassword — 400 CURRENT_PASSWORD_MISMATCH', () => {
  it('T1-2a: 400 CURRENT_PASSWORD_MISMATCH → ApiError(400) throw, body.code 보존', async () => {
    server.use(
      http.post('/api/v1/users/me/password', () => {
        return HttpResponse.json(
          { code: 'CURRENT_PASSWORD_MISMATCH', message: '현재 비밀번호가 일치하지 않습니다.' },
          { status: 400 },
        )
      }),
    )
    document.cookie = 'XSRF-TOKEN=test-token'

    let caught: unknown
    try {
      await changePassword({ currentPassword: 'wrong', newPassword: 'NewPass456@' })
    } catch (e) {
      caught = e
    }

    expect(caught).toBeInstanceOf(ApiError)
    const err = caught as ApiError
    expect(err.status).toBe(400)
    expect((err.body as Record<string, unknown>)['code']).toBe('CURRENT_PASSWORD_MISMATCH')
  })
})

describe('changePassword — 400 POLICY_VIOLATION (violations 보존)', () => {
  it('T1-2b: 400 POLICY_VIOLATION → ApiError(400), violations 배열 보존', async () => {
    server.use(
      http.post('/api/v1/users/me/password', () => {
        return HttpResponse.json(
          {
            code: 'POLICY_VIOLATION',
            message: '비밀번호가 정책을 위반합니다.',
            violations: ['MIN_LENGTH'],
          },
          { status: 400 },
        )
      }),
    )
    document.cookie = 'XSRF-TOKEN=test-token'

    let caught: unknown
    try {
      await changePassword({ currentPassword: 'OldPass123!', newPassword: 'short' })
    } catch (e) {
      caught = e
    }

    expect(caught).toBeInstanceOf(ApiError)
    const err = caught as ApiError
    expect(err.status).toBe(400)
    const body = err.body as Record<string, unknown>
    expect(body['code']).toBe('POLICY_VIOLATION')
    expect(body['violations']).toEqual(['MIN_LENGTH'])
  })
})

describe('changePassword — 400 SAME_AS_CURRENT', () => {
  it('T1-2c: 400 SAME_AS_CURRENT → ApiError(400), body.code 보존', async () => {
    server.use(
      http.post('/api/v1/users/me/password', () => {
        return HttpResponse.json(
          { code: 'SAME_AS_CURRENT', message: '새 비밀번호가 현재 비밀번호와 같습니다.' },
          { status: 400 },
        )
      }),
    )
    document.cookie = 'XSRF-TOKEN=test-token'

    let caught: unknown
    try {
      await changePassword({ currentPassword: 'SamePass123!', newPassword: 'SamePass123!' })
    } catch (e) {
      caught = e
    }

    expect(caught).toBeInstanceOf(ApiError)
    const err = caught as ApiError
    expect(err.status).toBe(400)
    expect((err.body as Record<string, unknown>)['code']).toBe('SAME_AS_CURRENT')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-3. CSRF — X-XSRF-TOKEN 헤더에 쿠키 값이 그대로 포함되는지 검증
// (double submit cookie 패턴 — Spring Security CSRF 방어)
// ─────────────────────────────────────────────────────────────────────────────

describe('changePassword — X-XSRF-TOKEN 헤더 포함', () => {
  it('T1-3a: XSRF-TOKEN 쿠키가 있으면 X-XSRF-TOKEN 헤더 값과 일치한다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/users/me/password', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json({ changed: true }, { status: 200 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=my-csrf-secret'

    await changePassword({ currentPassword: 'OldPass123!', newPassword: 'NewPass456@' })

    expect(capturedXsrf).toBe('my-csrf-secret')
  })

  it('T1-3b: XSRF-TOKEN 쿠키가 없으면 X-XSRF-TOKEN 헤더는 빈 문자열이다 (EC-6)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/users/me/password', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json({ changed: true }, { status: 200 })
      }),
    )
    // 쿠키 제거: jsdom에서는 만료 날짜를 과거로 설정해 삭제
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'

    await changePassword({ currentPassword: 'OldPass123!', newPassword: 'NewPass456@' })

    // 빈 문자열 또는 헤더 자체가 전송되었는지 검증 (EC-6 스펙)
    expect(capturedXsrf === '' || capturedXsrf === null).toBe(true)
  })
})
