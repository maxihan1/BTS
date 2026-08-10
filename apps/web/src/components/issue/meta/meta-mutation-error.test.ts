// 메타 필드 저장 실패 분류 판별식 — 「재시도해도 안 되는 실패」에 재시도를 권하지 않는다
import { describe, it, expect } from 'vitest'
import { ApiError } from '@/api/client'
import { classifyMetaMutationError, extractErrorCode } from './meta-mutation-error'

describe('classifyMetaMutationError', () => {
  it('409 는 version-conflict 다 (재조회가 필요한 유일한 경우)', () => {
    expect(classifyMetaMutationError(new ApiError(409, { errorCode: 'VERSION_CONFLICT' }))).toBe(
      'version-conflict',
    )
  })

  it('★422 CUSTOM_FIELD_VALIDATION_FAILED 는 폴백으로 떨어지지 않는다', () => {
    // 폴백 문구는 「잠시 후 다시 시도해 주세요」다 — 입력을 고치지 않으면 몇 번을 눌러도
    // 같은 결과라 사용자를 무한 재시도에 가둔다.
    expect(
      classifyMetaMutationError(new ApiError(422, { errorCode: 'CUSTOM_FIELD_VALIDATION_FAILED' })),
    ).toBe('custom-field-invalid')
  })

  it('409 가 CUSTOM_FIELD_VALIDATION_FAILED 를 달고 와도 version-conflict 가 이긴다', () => {
    // 순서가 중요하다. 409 는 **재조회**라는 고유 부수효과를 갖는다 — 그걸 놓치면
    // 사용자가 낡은 버전으로 계속 저장을 시도하게 된다.
    expect(
      classifyMetaMutationError(new ApiError(409, { errorCode: 'CUSTOM_FIELD_VALIDATION_FAILED' })),
    ).toBe('version-conflict')
  })

  it('다른 errorCode 는 폴백이다 (분류가 과하게 넓지 않다)', () => {
    expect(classifyMetaMutationError(new ApiError(422, { errorCode: 'SOMETHING_ELSE' }))).toBe(
      'fallback',
    )
    expect(classifyMetaMutationError(new ApiError(500, {}))).toBe('fallback')
  })

  it('ApiError 가 아니면 폴백이다', () => {
    expect(classifyMetaMutationError(new Error('네트워크 단절'))).toBe('fallback')
    expect(classifyMetaMutationError(undefined)).toBe('fallback')
    expect(classifyMetaMutationError(null)).toBe('fallback')
  })

  it('세 분류가 전부 실제로 나온다 (비-공허 짝)', () => {
    // 한 값으로 굳어 있으면 위 단언 절반이 공허해진다.
    const kinds = new Set([
      classifyMetaMutationError(new ApiError(409, {})),
      classifyMetaMutationError(new ApiError(422, { errorCode: 'CUSTOM_FIELD_VALIDATION_FAILED' })),
      classifyMetaMutationError(new ApiError(500, {})),
    ])
    expect(kinds.size).toBe(3)
  })
})

describe('extractErrorCode — 본문 형태를 신뢰하지 않는다', () => {
  it('정상 본문에서 errorCode 를 뽑는다', () => {
    expect(extractErrorCode(new ApiError(422, { errorCode: 'X' }))).toBe('X')
  })

  it('본문이 객체가 아니어도 던지지 않는다 (프록시가 문자열·HTML 을 줄 수 있다)', () => {
    // 여기서 던지면 onError 안에서 새 예외가 나 원래 실패가 통째로 가려진다.
    for (const body of ['<html>502</html>', 42, null, undefined, []]) {
      expect(() => extractErrorCode(new ApiError(502, body))).not.toThrow()
      expect(extractErrorCode(new ApiError(502, body))).toBe('')
    }
  })

  it('errorCode 가 문자열이 아니면 빈 문자열이다', () => {
    expect(extractErrorCode(new ApiError(422, { errorCode: 123 }))).toBe('')
    expect(extractErrorCode(new ApiError(422, { errorCode: null }))).toBe('')
  })
})
