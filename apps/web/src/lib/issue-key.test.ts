// 이슈 키 정규식 단일 출처 테스트 (FR-UX-12 F13 T1)
import { describe, it, expect } from 'vitest'
import { ISSUE_KEY_PATTERN } from './issue-key'

describe('ISSUE_KEY_PATTERN', () => {
  it('대문자 프로젝트키-숫자 형식에 일치한다', () => {
    expect(ISSUE_KEY_PATTERN.test('ATLAS-42')).toBe(true)
    expect(ISSUE_KEY_PATTERN.test('A1-7')).toBe(true)
  })

  it('소문자·부분일치·여분 토큰은 불일치다', () => {
    expect(ISSUE_KEY_PATTERN.test('atlas-42')).toBe(false)
    expect(ISSUE_KEY_PATTERN.test('ATLAS-42 로그인')).toBe(false)
    expect(ISSUE_KEY_PATTERN.test('ATLAS-')).toBe(false)
    expect(ISSUE_KEY_PATTERN.test('-42')).toBe(false)
  })

  it('전역 플래그가 없다 — lastIndex 상태가 test() 호출 간에 남으면 안 된다', () => {
    // g 뿐 아니라 y(sticky)도 test()에서 lastIndex 를 전진시킨다 — 둘 다 없어야 의도가 성립한다
    expect(ISSUE_KEY_PATTERN.global).toBe(false)
    expect(ISSUE_KEY_PATTERN.sticky).toBe(false)
  })
})
