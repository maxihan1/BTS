// 팔레트 비-슬래시 입력의 2계층 판별 테스트 (FR-UX-12 F4 T2 · FR1)
import { describe, it, expect } from 'vitest'
import { resolveNonCommandInput } from './palette-input'

describe('resolveNonCommandInput', () => {
  it('이슈키 형식이면 issue-key 로 판별하고 대문자로 정규화한다 (S1·S2)', () => {
    expect(resolveNonCommandInput('ATLAS-12')).toEqual({ kind: 'issue-key', issueKey: 'ATLAS-12' })
    expect(resolveNonCommandInput('atlas-12')).toEqual({ kind: 'issue-key', issueKey: 'ATLAS-12' })
  })

  it('앞뒤 공백이 있어도 이슈키로 판별한다', () => {
    expect(resolveNonCommandInput('  ATLAS-12  ')).toEqual({
      kind: 'issue-key',
      issueKey: 'ATLAS-12',
    })
  })

  it('프로젝트 키만 있으면 자유 텍스트다 (E2)', () => {
    expect(resolveNonCommandInput('ATLAS-')).toEqual({ kind: 'free-text', query: 'ATLAS-' })
  })

  it('숫자만 있으면 자유 텍스트다 (E3 — DC 의 번호 점프는 미채택)', () => {
    expect(resolveNonCommandInput('12')).toEqual({ kind: 'free-text', query: '12' })
  })

  it('일반 텍스트는 free-text 다 (S4)', () => {
    expect(resolveNonCommandInput('로그인')).toEqual({ kind: 'free-text', query: '로그인' })
  })

  it('공백만이면 empty 다 (E12 — 검색 호출 0)', () => {
    expect(resolveNonCommandInput('   ')).toEqual({ kind: 'empty' })
  })

  it('빈 문자열이면 empty 다', () => {
    expect(resolveNonCommandInput('')).toEqual({ kind: 'empty' })
  })

  it('★슬래시로 시작해도 이 함수는 판단하지 않는다 — 호출부가 순서를 지킨다 (FR2)', () => {
    // 이 함수는 not-command 일 때만 불린다는 계약이다. 방어적으로 슬래시를 되돌려보내지
    // 않는다 — 그러면 판별이 두 곳에 생겨 ADR D-1 의 경계가 무너진다.
    expect(resolveNonCommandInput('/goto ATLAS-1')).toEqual({
      kind: 'free-text',
      query: '/goto ATLAS-1',
    })
  })
})
