// preview 실패 오류를 상태별 사용자 문구로 가르는 순수 함수 테스트 — 500·단절이 「키를 확인하라」로 새지 않는 것을 고정한다

import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/client'
import { issueMoveStrings as s } from '@/i18n/ko'
import { resolvePreviewErrorMessage } from './move-error-message'

// ★문자열을 하드코딩하지 않고 `issueMoveStrings` 와 **동일성**으로 비교하는 것은 의도다.
//   이 파일이 재는 것은 **어느 상태가 어느 문구로 가는가(라우팅)** 이지 문구의 내용이 아니다.
//   내용 계약은 `MoveIssueDialog.test.tsx` 가 리터럴을 하드코딩해 지킨다(같은 PR, 그 파일의
//   「사용자 문구 정본」 블록). 여기서 리터럴을 또 적으면 같은 문장이 **세 벌**이 된다.
//   ⚠️ 그래서 이 파일만으로는 「문구가 바뀌었다」를 못 잡는다 — 아래 비-공허 짝 2종이
//   그 사각지대를 좁힌다(세 문구가 서로 다름 · 403 아닌 둘에 「키」 없음).
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

  it.each([400, 401, 404, 409])('%i 면 그 외 4xx 문구를 낸다', (status) => {
    expect(resolvePreviewErrorMessage(new ApiError(status, {}))).toBe(s.errorPreview)
  })

  it('404 PROJECT_NOT_FOUND 면 대상 프로젝트 미존재 문구를 낸다 — 새로고침하라고 하지 않는다', () => {
    // 상태 코드만 보면 이 404 도 「그 외 4xx」로 떨어져 「페이지를 새로고침한 뒤 다시
    // 시도해 주세요」가 된다. **새로고침해도 아무 일도 안 일어난다 — 틀린 것은 키다.**
    // 실행 단계(`MoveIssueDialog` onError)는 이미 errorCode 로 이 경우를 갈라내고 있어,
    // preview 만 다르게 답하면 같은 다이얼로그가 단계마다 다른 설명을 하게 된다.
    // ★운영에서는 403 이 먼저 걸려 도달하지 않는다. 비-prod 개발 편의 경로다.
    const err = new ApiError(404, { errorCode: 'PROJECT_NOT_FOUND' })
    expect(resolvePreviewErrorMessage(err)).toBe(s.errorProjectNotFound)
  })

  it('★비-공허 짝. errorCode 가 없는 404 는 그대로 그 외 4xx 문구다', () => {
    // 위 단언이 「404 면 무조건 미존재 문구」로 퇴화하지 않았음을 배제한다.
    expect(resolvePreviewErrorMessage(new ApiError(404, { errorCode: 'ISSUE_NOT_FOUND' }))).toBe(
      s.errorPreview,
    )
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
