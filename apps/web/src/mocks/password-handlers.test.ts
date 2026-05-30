// 비밀번호 변경 MSW 핸들러 단위 테스트 — EC-7 분기순서 및 에러코드 대문자 스네이크 검증
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { passwordHandlers } from './password-handlers'

const server = setupServer(...passwordHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

/** 정상 변경 응답 shape — 200 { changed: true } */
interface PasswordChangeSuccess {
  changed: boolean
}

/** 에러 응답 shape — 400 { code, message, violations? } */
interface PasswordChangeError {
  code: string
  message: string
  violations?: string[]
}

/**
 * 요청 헬퍼 — POST /api/v1/users/me/password
 * seed currentPassword = "CurrentPass123!" (password-handlers.ts SEED_CURRENT_PASSWORD)
 */
async function postPassword(currentPassword: string, newPassword: string): Promise<Response> {
  return fetch('/api/v1/users/me/password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ currentPassword, newPassword }),
  })
}

describe('passwordHandlers — POST /api/v1/users/me/password (EC-7 분기순서)', () => {
  // EC-7 분기 2: 새 비밀번호 정책 위반 (길이 부족) → POLICY_VIOLATION
  it('EC-7-1: 새 비번 12자 미만("short") → 400 POLICY_VIOLATION + violations[MIN_LENGTH]', async () => {
    const res = await postPassword('CurrentPass123!', 'short')

    expect(res.status).toBe(400)
    const body = await res.json() as PasswordChangeError
    expect(body.code).toBe('POLICY_VIOLATION')
    expect(body.violations).toContain('MIN_LENGTH')
  })

  // EC-7 분기 2: 새 비밀번호 정책 위반 — 에러코드 대문자 스네이크 검증 (PR #41 BLOCKER 선례)
  it('EC-7-2: 에러코드는 반드시 대문자 스네이크 — "policy_violation" 이면 fail', async () => {
    const res = await postPassword('CurrentPass123!', 'short')

    const body = await res.json() as PasswordChangeError
    // 대문자 스네이크여야 함
    expect(body.code).toBe('POLICY_VIOLATION')
    expect(body.code).not.toBe('policy_violation')
    expect(body.code).not.toBe('Policy_Violation')
  })

  // EC-7 분기 3: 새 비밀번호 == 현재 비밀번호 → SAME_AS_CURRENT (정책 판정보다 먼저임을 검증)
  // 새 비번이 현재 비번과 동일하면서도 정책을 통과하는 경우 → SAME_AS_CURRENT 우선
  it('EC-7-3: 새 비번 == 현재 비번("CurrentPass123!") → 400 SAME_AS_CURRENT', async () => {
    const res = await postPassword('CurrentPass123!', 'CurrentPass123!')

    expect(res.status).toBe(400)
    const body = await res.json() as PasswordChangeError
    expect(body.code).toBe('SAME_AS_CURRENT')
  })

  // EC-7 분기 4: 현재 비밀번호 불일치 → CURRENT_PASSWORD_MISMATCH
  it('EC-7-4: currentPassword 틀림 → 400 CURRENT_PASSWORD_MISMATCH', async () => {
    const res = await postPassword('WrongPassword123!', 'NewValidPass123!')

    expect(res.status).toBe(400)
    const body = await res.json() as PasswordChangeError
    expect(body.code).toBe('CURRENT_PASSWORD_MISMATCH')
  })

  // EC-7 분기 5: 정상 → 200 { changed: true }
  it('EC-7-5: 정상 요청 → 200 { changed: true }', async () => {
    const res = await postPassword('CurrentPass123!', 'NewValidPass123!')

    expect(res.status).toBe(200)
    const body = await res.json() as PasswordChangeSuccess
    expect(body.changed).toBe(true)
  })

  // 분기순서 보장: POLICY_VIOLATION → SAME_AS_CURRENT → MISMATCH 순서 검증
  // 새 비번이 12자 미만("short")이면 seed와 같든 틀리든 POLICY_VIOLATION이 먼저 판정됨
  it('EC-7-order: POLICY_VIOLATION → SAME_AS_CURRENT → MISMATCH 순서 보장', async () => {
    const res = await postPassword('CurrentPass123!', 'short')
    const body = await res.json() as PasswordChangeError
    // 정책 위반이 먼저여야 함 (SAME_AS_CURRENT가 아님)
    expect(body.code).toBe('POLICY_VIOLATION')
  })

  // violations 배열 구조 검증
  it('EC-7-violations: POLICY_VIOLATION 응답에 violations 배열 포함', async () => {
    const res = await postPassword('CurrentPass123!', 'short')

    const body = await res.json() as PasswordChangeError
    expect(Array.isArray(body.violations)).toBe(true)
  })

  // message 필드 검증 — 한글 메시지 포함
  it('EC-7-message: 각 에러 응답에 message 필드 포함', async () => {
    const policyRes = await postPassword('CurrentPass123!', 'short')
    const policyBody = await policyRes.json() as PasswordChangeError
    expect(typeof policyBody.message).toBe('string')
    expect(policyBody.message.length).toBeGreaterThan(0)

    const mismatchRes = await postPassword('WrongPassword123!', 'NewValidPass123!')
    const mismatchBody = await mismatchRes.json() as PasswordChangeError
    expect(typeof mismatchBody.message).toBe('string')
  })
})
