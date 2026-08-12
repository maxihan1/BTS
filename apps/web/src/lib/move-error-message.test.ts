// preview 실패 오류를 상태별 사용자 문구로 가르는 순수 함수 테스트 — 500·단절이 「키를 확인하라」로 새지 않는 것을 고정한다

import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/client'
import { issueMoveStrings as s } from '@/i18n/ko'
import { resolvePreviewErrorMessage } from './move-error-message'

describe('resolvePreviewErrorMessage', () => {
  it('403 이면 권한 전용 문구를 낸다', () => {
    expect(resolvePreviewErrorMessage(new ApiError(403, {}))).toBe(s.errorPreviewForbidden)
  })

  it.each([500, 502, 503, 504])('%i 면 일시적 문제 문구를 낸다', (status) => {
    expect(resolvePreviewErrorMessage(new ApiError(status, {}))).toBe(s.errorPreviewTemporary)
  })

  it('ApiError 가 아닌 오류(네트워크 단절·응답 해석 실패)도 일시적 문제 문구를 낸다', () => {
    // fetch 가 거부하면 ApiError 가 아니라 TypeError 가 온다 — 상태 코드 자체가 없다.
    expect(resolvePreviewErrorMessage(new TypeError('Failed to fetch'))).toBe(
      s.errorPreviewTemporary,
    )
  })

  it.each([400, 401, 404, 409, 422])('%i 면 그 외 4xx 문구를 낸다', (status) => {
    expect(resolvePreviewErrorMessage(new ApiError(status, {}))).toBe(s.errorPreview)
  })

  // ─── 비-공허 짝 ───────────────────────────────────────────────────────────
  // 위 단언들은 **세 문구가 실은 같은 문자열이어도 전부 통과한다.** 아래 둘이 그것을 배제한다.

  it('★세 문구가 서로 다르다', () => {
    const all = [s.errorPreviewForbidden, s.errorPreviewTemporary, s.errorPreview]
    expect(new Set(all).size).toBe(3)
  })

  it('★403 이 아닌 두 문구는 사용자에게 키를 의심하게 만들지 않는다', () => {
    // 이 부채가 정확히 그 결함이었다 — 서버 500 과 네트워크 단절에도 「대상 프로젝트 키를
    // 확인해 주세요」라고 말해, 멀쩡한 키를 몇 번씩 고쳐 쓰다가 포기하게 만들었다.
    expect(s.errorPreviewTemporary).not.toContain('키')
    expect(s.errorPreview).not.toContain('키')
  })
})
