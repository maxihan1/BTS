// 자유 텍스트를 AQL 전문검색 쿼리로 감싸는 순수 함수 테스트 (FR-UX-12 F4 T1)
import { describe, it, expect } from 'vitest'
import { buildTextQuery, escapeAqlString } from './aql-text-query'

describe('escapeAqlString', () => {
  it('큰따옴표를 이스케이프한다', () => {
    expect(escapeAqlString('로그인"버그')).toBe('로그인\\"버그')
  })

  it('역슬래시를 먼저 이스케이프한다 (이중 이스케이프 방지)', () => {
    expect(escapeAqlString('a\\b')).toBe('a\\\\b')
  })

  it('역슬래시와 큰따옴표가 같이 있어도 순서가 어긋나지 않는다', () => {
    expect(escapeAqlString('a\\"b')).toBe('a\\\\\\"b')
  })

  it('특수문자가 없으면 원문 그대로다', () => {
    expect(escapeAqlString('로그인 버그')).toBe('로그인 버그')
  })
})

describe('buildTextQuery', () => {
  it('자유 텍스트를 text ~ "…" 로 감싼다', () => {
    expect(buildTextQuery('로그인 버그')).toBe('text ~ "로그인 버그"')
  })

  it('앞뒤 공백을 제거한 뒤 감싼다', () => {
    expect(buildTextQuery('  로그인  ')).toBe('text ~ "로그인"')
  })

  it('따옴표가 든 질의도 유효한 AQL 이 된다', () => {
    expect(buildTextQuery('로그인"버그')).toBe('text ~ "로그인\\"버그"')
  })

  it('공백만 있으면 null 을 반환한다 (검색 호출 금지 신호 — E12)', () => {
    expect(buildTextQuery('   ')).toBeNull()
  })

  it('빈 문자열이면 null 을 반환한다', () => {
    expect(buildTextQuery('')).toBeNull()
  })
})
