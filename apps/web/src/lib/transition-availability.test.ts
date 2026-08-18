// lib/transition-availability.ts 순수 로직 단위 테스트 — 422(미설정) vs 200+빈 배열(종료) 4분기 (FR-UX-11 F9 FR8)
import { describe, it, expect } from 'vitest'
import { ApiError } from '@/api/client'
import { resolveTransitionUnavailableReason } from './transition-availability'

describe('resolveTransitionUnavailableReason', () => {
  it('422 에러면 워크플로우 미설정으로 판정한다', () => {
    expect(
      resolveTransitionUnavailableReason({
        isError: true,
        error: new ApiError(422, { errorCode: 'workflow_not_configured' }),
        transitionCount: 0,
      }),
    ).toBe('no-workflow')
  })

  it('정상 응답 + 전환 0건이면 종료 상태로 판정한다', () => {
    expect(
      resolveTransitionUnavailableReason({ isError: false, error: null, transitionCount: 0 }),
    ).toBe('terminal')
  })

  it('정상 응답 + 전환이 있으면 사유가 없다', () => {
    expect(
      resolveTransitionUnavailableReason({ isError: false, error: null, transitionCount: 2 }),
    ).toBeNull()
  })

  it('422 가 아닌 에러는 사유로 삼지 않는다 — 에러 표시는 호출자 몫이다', () => {
    expect(
      resolveTransitionUnavailableReason({
        isError: true,
        error: new ApiError(500, null),
        transitionCount: 0,
      }),
    ).toBeNull()
  })

  it('ApiError 가 아닌 에러도 사유로 삼지 않는다', () => {
    expect(
      resolveTransitionUnavailableReason({
        isError: true,
        error: new Error('network down'),
        transitionCount: 0,
      }),
    ).toBeNull()
  })
})
