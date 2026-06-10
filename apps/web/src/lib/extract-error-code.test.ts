// extractErrorCode 공유 유틸 테스트 — 백엔드 `error` 키 정합 (PR #106 B1 회귀 가드)
import { describe, it, expect } from 'vitest'
import { extractErrorCode } from './extract-error-code'

describe('extractErrorCode', () => {
  it('백엔드 표준 형태 { error: code }에서 코드를 읽는다 (식별자-access 정본)', () => {
    expect(extractErrorCode({ error: 'reauth_failed' })).toBe('reauth_failed')
    expect(extractErrorCode({ error: 'step_up_required' })).toBe('step_up_required')
  })

  it('errorCode 키도 읽는다 (ProblemDetail-like, errorCode 우선)', () => {
    expect(extractErrorCode({ errorCode: 'account_already_linked' })).toBe('account_already_linked')
    expect(extractErrorCode({ errorCode: 'a', error: 'b' })).toBe('a')
  })

  it('객체가 아니거나 코드가 문자열이 아니면 null을 반환한다', () => {
    expect(extractErrorCode(null)).toBeNull()
    expect(extractErrorCode('reauth_failed')).toBeNull()
    expect(extractErrorCode({})).toBeNull()
    expect(extractErrorCode({ error: 123 })).toBeNull()
  })
})
