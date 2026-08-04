// ADR D-1/D-5 경계를 기계로 강제하는 가드 — 경계는 코드에 흔적을 남기지 않는다 (FR-UX-12 F4 T8)
//
// ★이 파일이 막는 것. 「슬래시 판별은 commands.ts, 이슈키/자유텍스트 판별은 palette-input.ts,
// 둘은 서로를 import 하지 않는다」는 ADR D-1 의 경계는 구현 코드 어디에도 흔적을 남기지 않는다.
// 다음 편집자가 자유 텍스트 판별을 commands.ts 로 되돌려도 타입 검사도 테스트도 막지 못한다.
// 그래서 원문(source)을 읽어 경계를 단언한다.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'
import { parseCommand } from './commands'
import { resolveNonCommandInput } from './palette-input'

/** 경계 단언 대상은 commands.ts **원문 하나뿐**이다 — palette-input.ts 의 kind 가 섞이면 단언이 무의미해진다 */
const COMMANDS_SRC = readFileSync(resolve(import.meta.dirname, 'commands.ts'), 'utf-8')

/** ParsedCommand 가 동결된 6갈래 (FR-UX-04 ADR D3 레지스트리 범위, 사전순) */
const FROZEN_KINDS = ['goto', 'incomplete', 'issue', 'not-command', 'search', 'unknown'] as const

/** 원문에서 `kind: 'xxx'` 표기를 뽑아 중복 없는 갈래 이름 배열(사전순)로 만든다 */
function kindsIn(source: string): string[] {
  const matches = source.match(/kind:\s*'[a-z-]+'/g) ?? []
  return [...new Set(matches.map((k) => k.replace(/kind:\s*'|'/g, '')))].sort()
}

/**
 * `export type ParsedCommand =` 아래에서 `|` 로 시작하는 선언 줄만 모은다.
 *
 * **파일 전체를 긁지 않는 이유.** 주석에 `kind: 'issue-key'` 라고 한 줄 적기만 해도
 * 단언이 깨지는 취약한 가드가 되기 때문이다(가짜 red). 6갈래 동결은 선언 블록에서 재고,
 * 선언 밖 유출은 아래 두 번째 테스트가 주석 줄을 제외하고 따로 잰다.
 */
function parsedCommandUnionBlock(): string {
  const afterEquals = COMMANDS_SRC.split('export type ParsedCommand =')[1] ?? ''
  const lines: string[] = []
  for (const line of afterEquals.split('\n')) {
    const trimmed = line.trim()
    if (trimmed === '') {
      if (lines.length > 0) break
      continue
    }
    if (!trimmed.startsWith('|')) break
    lines.push(trimmed)
  }
  return lines.join('\n')
}

/** 주석 줄(`//` · JSDoc `*` · `/*`)을 걷어낸 원문 — 주석 한 줄로 red 가 나는 것을 막는다 */
function codeLinesOf(source: string): string {
  return source
    .split('\n')
    .filter((line) => {
      const trimmed = line.trim()
      return !trimmed.startsWith('//') && !trimmed.startsWith('*') && !trimmed.startsWith('/*')
    })
    .join('\n')
}

describe('경계 가드 1 — commands.ts 는 신규 판별 레이어를 import 하지 않는다', () => {
  it('★역방향 의존이 생기면 ADR D-1 의 경계가 무너진다', () => {
    // 모듈 지정자 자체를 막는다 — `from './x'` · 부수효과 `import './x'` · 동적 `import('./x')` 전부.
    // `from` 만 보면 부수효과 import 를 놓쳐 가드가 공허해진다(뮤테이션 1 로 실측).
    expect(COMMANDS_SRC).not.toMatch(/['"]\.\/palette-input['"]/)
    expect(COMMANDS_SRC).not.toMatch(/['"]\.\/use-palette-search['"]/)
  })

  it('commands.ts 는 aql-text-query 도 직접 쓰지 않는다 (래핑은 실행부 책임)', () => {
    expect(COMMANDS_SRC).not.toMatch(/aql-text-query/)
  })
})

describe('경계 가드 2 — ParsedCommand 유니온 6갈래 동결', () => {
  it('★FR-UX-04 ADR D3 이 못박은 레지스트리 범위다. 갈래가 늘면 F4 가 경계를 넘은 것이다', () => {
    const unionBlock = parsedCommandUnionBlock()
    // 추출 실패(빈 블록)를 통과로 오독하지 않도록 블록 자체를 먼저 증인으로 세운다
    expect(unionBlock).not.toBe('')
    expect(kindsIn(unionBlock)).toEqual([...FROZEN_KINDS])
  })

  it('선언 밖에도 6갈래 밖 kind 는 없다 — 주석 줄은 제외하고 잰다', () => {
    expect(kindsIn(codeLinesOf(COMMANDS_SRC))).toEqual([...FROZEN_KINDS])
  })
})

describe('경계 가드 3 — 호출 순서 계약 (FR2)', () => {
  it('★슬래시 입력은 슬래시 경로가 이긴다 — 이슈키로 새면 /goto 가 죽는다', () => {
    const parsed = parseCommand('/goto ATLAS-1')
    expect(parsed.kind).toBe('goto')
    // 순서를 어겨 먼저 부르면 자유 텍스트가 되어버린다는 사실을 명시적으로 고정한다
    expect(resolveNonCommandInput('/goto ATLAS-1').kind).toBe('free-text')
  })

  it('비-슬래시 이슈키만 issue-key 로 판별된다', () => {
    expect(parseCommand('ATLAS-1').kind).toBe('not-command')
    expect(resolveNonCommandInput('ATLAS-1')).toEqual({ kind: 'issue-key', issueKey: 'ATLAS-1' })
  })
})
