// 전역 검색 입력 3갈래 판별(이슈키/AQL/자유 텍스트) 순수 함수 테스트 (FR-UX-12 F13 T2)
import { describe, it, expect } from 'vitest'
import { resolveGlobalSearchInput } from './aql-natural'

describe('resolveGlobalSearchInput', () => {
  it('E5/S4 — 빈 문자열과 공백만은 empty 다', () => {
    expect(resolveGlobalSearchInput('')).toEqual({ kind: 'empty' })
    expect(resolveGlobalSearchInput('   ')).toEqual({ kind: 'empty' })
  })

  it('S2/E1 — 이슈키는 대문자로 정규화해 issue-key 다', () => {
    expect(resolveGlobalSearchInput('atlas-42')).toEqual({ kind: 'issue-key', issueKey: 'ATLAS-42' })
    expect(resolveGlobalSearchInput('  ATLAS-42  ')).toEqual({ kind: 'issue-key', issueKey: 'ATLAS-42' })
  })

  it('E2 — 이슈키 뒤에 토큰이 붙으면 자유 텍스트다', () => {
    expect(resolveGlobalSearchInput('ATLAS-42 로그인')).toEqual({
      kind: 'text',
      query: 'text ~ "ATLAS-42 로그인"',
    })
  })

  it('S3/E10 — 알려진 필드 + 연산자면 AQL 로 보고 원문을 통과시킨다', () => {
    expect(resolveGlobalSearchInput('status = "열림"')).toEqual({ kind: 'aql', query: 'status = "열림"' })
    expect(resolveGlobalSearchInput('summary ~ 로그인')).toEqual({ kind: 'aql', query: 'summary ~ 로그인' })
    // `!=` 예시는 백엔드가 200 을 주는 조합으로 고른다. `priority != HIGH` 처럼 우선순위에
    // 문자열 값을 주면 백엔드가 500 을 낸다(IssueRepository.kt:2991 asShort() 의
    // IllegalArgumentException 을 SearchExceptionHandler.kt:48 이 안 다룸 — PRE_EXISTING).
    // 이 함수는 분류만 하므로 테스트 자체는 통과하지만, 실제로 깨지는 쿼리를 예시로 못박지 않는다.
    expect(resolveGlobalSearchInput('label != backend')).toEqual({ kind: 'aql', query: 'label != backend' })
    expect(resolveGlobalSearchInput('priority = 1')).toEqual({ kind: 'aql', query: 'priority = 1' })
    expect(resolveGlobalSearchInput('STATUS = "열림"')).toEqual({ kind: 'aql', query: 'STATUS = "열림"' })
  })

  it('E11 — 필드명만 있고 연산자가 없으면 자유 텍스트다', () => {
    expect(resolveGlobalSearchInput('status')).toEqual({ kind: 'text', query: 'text ~ "status"' })
  })

  it('알려지지 않은 필드는 연산자가 있어도 자유 텍스트다', () => {
    expect(resolveGlobalSearchInput('로그인 = 안됨')).toEqual({
      kind: 'text',
      query: 'text ~ "로그인 = 안됨"',
    })
  })

  it('S5/E3/E4 — 자유 텍스트의 역슬래시와 큰따옴표는 이스케이프된다', () => {
    expect(resolveGlobalSearchInput('C:\\Users')).toEqual({ kind: 'text', query: 'text ~ "C:\\\\Users"' })
    expect(resolveGlobalSearchInput('로그인"버그')).toEqual({ kind: 'text', query: 'text ~ "로그인\\"버그"' })
  })
})
