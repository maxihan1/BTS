// 프로젝트 키 형식 판정 순수 함수 테스트 — 생성 화면과 이동 화면이 같은 입력에 같은 판정을 내는 것을 고정한다

import { describe, expect, it } from 'vitest'
import { isValidProjectKey } from './project-key'

describe('isValidProjectKey', () => {
  it.each(['AB', 'A1', 'INFRA', 'ABCDEFGHIJ'])('형식에 맞는 키 %s 를 통과시킨다', (key) => {
    expect(isValidProjectKey(key)).toBe(true)
  })

  it.each([
    ['infra', '전부 소문자'],
    ['Infra', '첫 글자만 대문자'],
    ['A', '1자 — 최소 2자'],
    ['ABCDEFGHIJK', '11자 — 최대 10자'],
    ['1ABC', '숫자로 시작'],
    ['AB-1', '하이픈'],
    ['AB 1', '중간 공백'],
    [' INFRA', '앞 공백 — 호출자가 trim 한 값을 넘겨야 한다는 계약'],
    ['', '빈 값'],
  ])('형식에 어긋난 키 %s 를 거절한다 (%s)', (key) => {
    expect(isValidProjectKey(key)).toBe(false)
  })

  // 정규식에 `g` 플래그가 붙으면 `lastIndex` 가 호출 간에 남아 같은 입력이 번갈아 true/false 가 된다.
  // 상수를 다른 파일로 옮기는 이 PR 에서 특히 조용히 깨질 수 있는 지점이라 못 박아 둔다.
  it('같은 입력을 연속 호출해도 판정이 흔들리지 않는다 (정규식 상태 누수 차단)', () => {
    expect(isValidProjectKey('INFRA')).toBe(true)
    expect(isValidProjectKey('INFRA')).toBe(true)
    expect(isValidProjectKey('INFRA')).toBe(true)
  })
})
