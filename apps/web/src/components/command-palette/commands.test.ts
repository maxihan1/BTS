// 명령 팔레트 슬래시 명령 파서 + 레지스트리 단위 테스트 — FR-UX-04 Task-1
import { describe, it, expect } from 'vitest'
import { parseCommand, COMMANDS, QUICK_LINKS } from './commands'

describe('parseCommand', () => {
  it('goto 명령 + 정상 이슈 키를 파싱한다', () => {
    expect(parseCommand('/goto PROJ-12')).toEqual({
      kind: 'goto',
      issueKey: 'PROJ-12',
    })
  })

  it('goto 이슈 키를 대문자로 정규화한다 (E4)', () => {
    expect(parseCommand('/goto proj-12')).toEqual({
      kind: 'goto',
      issueKey: 'PROJ-12',
    })
  })

  it('search 명령 + 질의를 파싱한다', () => {
    expect(parseCommand('/search 로그인 버그')).toEqual({
      kind: 'search',
      query: '로그인 버그',
    })
  })

  it('issue 명령 + 제목을 파싱한다', () => {
    expect(parseCommand('/issue 결제 실패')).toEqual({
      kind: 'issue',
      summary: '결제 실패',
    })
  })

  it('알 수 없는 명령은 unknown을 반환한다 (E1)', () => {
    expect(parseCommand('/foo x')).toEqual({
      kind: 'unknown',
      name: 'foo',
    })
  })

  it('goto 인자가 없으면 incomplete를 반환한다 (E2)', () => {
    expect(parseCommand('/goto')).toEqual({
      kind: 'incomplete',
      name: 'goto',
    })
  })

  it('goto 인자가 이슈 키 형식이 아니면 invalid-key 사유를 반환한다 (E3)', () => {
    expect(parseCommand('/goto 안녕')).toEqual({
      kind: 'incomplete',
      name: 'goto',
      reason: 'invalid-key',
    })
  })

  it('search 인자가 없으면 incomplete를 반환한다', () => {
    expect(parseCommand('/search')).toEqual({
      kind: 'incomplete',
      name: 'search',
    })
  })

  it('issue 인자가 없으면 incomplete를 반환한다', () => {
    expect(parseCommand('/issue')).toEqual({
      kind: 'incomplete',
      name: 'issue',
    })
  })

  it('슬래시로 시작하지 않으면 not-command를 반환한다', () => {
    expect(parseCommand('hello')).toEqual({ kind: 'not-command' })
  })

  it('빈 입력은 not-command를 반환한다', () => {
    expect(parseCommand('')).toEqual({ kind: 'not-command' })
  })
})

describe('COMMANDS', () => {
  it('goto/search/issue 3종 명령 힌트 메타를 제공한다', () => {
    expect(COMMANDS).toHaveLength(3)
    expect(COMMANDS.map((command) => command.name)).toEqual([
      'goto',
      'search',
      'issue',
    ])
    for (const command of COMMANDS) {
      expect(command.prefix.startsWith('/')).toBe(true)
      expect(command.description.length).toBeGreaterThan(0)
    }
  })
})

describe('QUICK_LINKS', () => {
  it('내 이슈/검색/대시보드/받은 편지함 4종 정적 바로가기를 제공한다', () => {
    expect(QUICK_LINKS).toHaveLength(4)
    expect(QUICK_LINKS).toEqual([
      { label: '내 이슈', to: '/issues' },
      { label: '검색', to: '/search' },
      { label: '대시보드', to: '/dashboards' },
      { label: '받은 편지함', to: '/inbox' },
    ])
  })
})
