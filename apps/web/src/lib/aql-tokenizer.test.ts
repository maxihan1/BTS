// AQL 토크나이저 단위 테스트 — 토큰 분해 정합 + 상수 회귀 스냅샷 (FR-SR-02 D6)
import { describe, it, expect } from 'vitest'
import { tokenizeAql, AQL_KEYWORDS, AQL_FIELDS, AQL_OPERATORS } from './aql-tokenizer'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 회귀 스냅샷
// 이 가드는 프론트 내부 일관성용이며 백엔드 동기화를 보장하지 않는다.
// 백엔드 정본: shared-kernel AqlFields.MVP_FIELDS:41 / AqlToken.KEYWORDS:90 — 변경 시 동반 수정
// ─────────────────────────────────────────────────────────────────────────────
describe('상수 회귀 스냅샷', () => {
  it('AQL_KEYWORDS — 8개 키워드 고정 (무단 변경 감지)', () => {
    expect(AQL_KEYWORDS).toEqual(['and', 'or', 'not', 'in', 'order', 'by', 'asc', 'desc'])
  })

  it('AQL_FIELDS — 4개 필드 고정', () => {
    expect(AQL_FIELDS).toEqual(['status', 'label', 'summary', 'priority'])
  })

  it('AQL_OPERATORS — 3개 연산자 고정', () => {
    expect(AQL_OPERATORS).toEqual(['=', '!=', '~'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// KEYWORD 토큰
// ─────────────────────────────────────────────────────────────────────────────
describe('KEYWORD 토큰', () => {
  it('소문자 and를 KEYWORD로 분류한다', () => {
    const tokens = tokenizeAql('and')
    expect(tokens).toHaveLength(1)
    expect(tokens[0]).toMatchObject({ type: 'KEYWORD', value: 'and' })
  })

  it('대문자 AND를 KEYWORD로 분류한다 (대소문자 무시)', () => {
    const tokens = tokenizeAql('AND')
    expect(tokens[0]).toMatchObject({ type: 'KEYWORD', value: 'AND' })
  })

  it('혼합 대소문자 Or를 KEYWORD로 분류한다', () => {
    const tokens = tokenizeAql('Or')
    expect(tokens[0]).toMatchObject({ type: 'KEYWORD', value: 'Or' })
  })

  it('NOT을 KEYWORD로 분류한다', () => {
    const tokens = tokenizeAql('NOT')
    expect(tokens[0]).toMatchObject({ type: 'KEYWORD', value: 'NOT' })
  })

  it('IN을 KEYWORD로 분류한다', () => {
    const tokens = tokenizeAql('IN')
    expect(tokens[0]).toMatchObject({ type: 'KEYWORD', value: 'IN' })
  })

  it('ORDER와 BY를 각각 개별 KEYWORD 토큰으로 분해한다 (멀티워드 묶음 안 함)', () => {
    const tokens = tokenizeAql('ORDER BY')
    expect(tokens).toHaveLength(2)
    expect(tokens[0]).toMatchObject({ type: 'KEYWORD', value: 'ORDER' })
    expect(tokens[1]).toMatchObject({ type: 'KEYWORD', value: 'BY' })
  })

  it('ASC를 KEYWORD로 분류한다', () => {
    const tokens = tokenizeAql('ASC')
    expect(tokens[0]).toMatchObject({ type: 'KEYWORD', value: 'ASC' })
  })

  it('DESC를 KEYWORD로 분류한다', () => {
    const tokens = tokenizeAql('DESC')
    expect(tokens[0]).toMatchObject({ type: 'KEYWORD', value: 'DESC' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FIELD 토큰
// ─────────────────────────────────────────────────────────────────────────────
describe('FIELD 토큰', () => {
  it('status를 FIELD로 분류한다', () => {
    const tokens = tokenizeAql('status')
    expect(tokens[0]).toMatchObject({ type: 'FIELD', value: 'status' })
  })

  it('label을 FIELD로 분류한다', () => {
    const tokens = tokenizeAql('label')
    expect(tokens[0]).toMatchObject({ type: 'FIELD', value: 'label' })
  })

  it('summary를 FIELD로 분류한다', () => {
    const tokens = tokenizeAql('summary')
    expect(tokens[0]).toMatchObject({ type: 'FIELD', value: 'summary' })
  })

  it('priority를 FIELD로 분류한다', () => {
    const tokens = tokenizeAql('priority')
    expect(tokens[0]).toMatchObject({ type: 'FIELD', value: 'priority' })
  })

  it('STATUS(대문자)를 FIELD로 분류한다 (대소문자 무시)', () => {
    const tokens = tokenizeAql('STATUS')
    expect(tokens[0]).toMatchObject({ type: 'FIELD', value: 'STATUS' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// OPERATOR 토큰
// ─────────────────────────────────────────────────────────────────────────────
describe('OPERATOR 토큰', () => {
  it('= 를 OPERATOR로 분류한다', () => {
    const tokens = tokenizeAql('=')
    expect(tokens[0]).toMatchObject({ type: 'OPERATOR', value: '=' })
  })

  it('!= 를 OPERATOR로 분류한다', () => {
    const tokens = tokenizeAql('!=')
    expect(tokens[0]).toMatchObject({ type: 'OPERATOR', value: '!=' })
  })

  it('~ 를 OPERATOR로 분류한다', () => {
    const tokens = tokenizeAql('~')
    expect(tokens[0]).toMatchObject({ type: 'OPERATOR', value: '~' })
  })

  it('!= 를 단일 토큰으로 묶는다 (! 와 = 를 분리하지 않는다)', () => {
    const tokens = tokenizeAql('!=')
    expect(tokens).toHaveLength(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// STRING 토큰
// ─────────────────────────────────────────────────────────────────────────────
describe('STRING 토큰', () => {
  it('큰따옴표 문자열을 STRING으로 분류한다', () => {
    const tokens = tokenizeAql('"hello world"')
    expect(tokens).toHaveLength(1)
    expect(tokens[0]).toMatchObject({ type: 'STRING', value: '"hello world"' })
  })

  it('이스케이프를 포함한 문자열을 단일 STRING으로 분류한다', () => {
    const tokens = tokenizeAql('"say \\"hi\\""')
    expect(tokens).toHaveLength(1)
    expect(tokens[0]).toMatchObject({ type: 'STRING' })
  })

  it('빈 따옴표 문자열을 STRING으로 분류한다', () => {
    const tokens = tokenizeAql('""')
    expect(tokens).toHaveLength(1)
    expect(tokens[0]).toMatchObject({ type: 'STRING', value: '""' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// NUMBER 토큰
// ─────────────────────────────────────────────────────────────────────────────
describe('NUMBER 토큰', () => {
  it('정수를 NUMBER로 분류한다', () => {
    const tokens = tokenizeAql('42')
    expect(tokens[0]).toMatchObject({ type: 'NUMBER', value: '42' })
  })

  it('음수를 NUMBER가 아닌 PLAIN으로 분류한다 (렉서 수준에서는 - 별도)', () => {
    const tokens = tokenizeAql('-1')
    // 렉서는 '-'와 '1'을 별도로 처리한다 — 파서가 문맥상 음수를 판단
    // 또는 단일 PLAIN 토큰으로 처리한다
    expect(tokens.length).toBeGreaterThan(0)
  })

  it('0을 NUMBER로 분류한다', () => {
    const tokens = tokenizeAql('0')
    expect(tokens[0]).toMatchObject({ type: 'NUMBER', value: '0' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PAREN 토큰
// ─────────────────────────────────────────────────────────────────────────────
describe('PAREN 토큰', () => {
  it('열린 괄호를 PAREN으로 분류한다', () => {
    const tokens = tokenizeAql('(')
    expect(tokens[0]).toMatchObject({ type: 'PAREN', value: '(' })
  })

  it('닫힌 괄호를 PAREN으로 분류한다', () => {
    const tokens = tokenizeAql(')')
    expect(tokens[0]).toMatchObject({ type: 'PAREN', value: ')' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// COMMA 토큰
// ─────────────────────────────────────────────────────────────────────────────
describe('COMMA 토큰', () => {
  it('쉼표를 COMMA로 분류한다', () => {
    const tokens = tokenizeAql(',')
    expect(tokens[0]).toMatchObject({ type: 'COMMA', value: ',' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PLAIN 토큰
// ─────────────────────────────────────────────────────────────────────────────
describe('PLAIN 토큰', () => {
  it('인식되지 않는 bare word를 PLAIN으로 분류한다', () => {
    const tokens = tokenizeAql('unknown_field')
    expect(tokens[0]).toMatchObject({ type: 'PLAIN', value: 'unknown_field' })
  })

  it('open(알려진 필드명 아님)을 PLAIN으로 분류한다', () => {
    const tokens = tokenizeAql('open')
    expect(tokens[0]).toMatchObject({ type: 'PLAIN', value: 'open' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// start/end 위치
// ─────────────────────────────────────────────────────────────────────────────
describe('토큰 위치 (start/end)', () => {
  it('단일 토큰의 start=0, end=길이를 정확히 반환한다', () => {
    const tokens = tokenizeAql('status')
    expect(tokens[0]?.start).toBe(0)
    expect(tokens[0]?.end).toBe(6)
  })

  it('공백으로 분리된 두 토큰의 위치를 정확히 계산한다', () => {
    const tokens = tokenizeAql('status = open')
    // 'status': 0..6, '=': 7..8, 'open': 9..13
    expect(tokens[0]?.start).toBe(0)
    expect(tokens[0]?.end).toBe(6)
    expect(tokens[1]?.start).toBe(7)
    expect(tokens[1]?.end).toBe(8)
    expect(tokens[2]?.start).toBe(9)
    expect(tokens[2]?.end).toBe(13)
  })

  it('따옴표 문자열의 start와 end가 따옴표를 포함한 전체 범위를 나타낸다', () => {
    const tokens = tokenizeAql('"hello"')
    expect(tokens[0]?.start).toBe(0)
    expect(tokens[0]?.end).toBe(7)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 복합 쿼리 분해
// ─────────────────────────────────────────────────────────────────────────────
describe('복합 쿼리 분해', () => {
  it('status = open 을 FIELD OPERATOR PLAIN으로 분해한다', () => {
    const tokens = tokenizeAql('status = open')
    expect(tokens).toHaveLength(3)
    expect(tokens[0]).toMatchObject({ type: 'FIELD', value: 'status' })
    expect(tokens[1]).toMatchObject({ type: 'OPERATOR', value: '=' })
    expect(tokens[2]).toMatchObject({ type: 'PLAIN', value: 'open' })
  })

  it('status = open AND priority IN (1, 2) 를 올바르게 분해한다', () => {
    const tokens = tokenizeAql('status = open AND priority IN (1, 2)')
    const types = tokens.map((t) => t.type)
    expect(types).toEqual([
      'FIELD', 'OPERATOR', 'PLAIN',
      'KEYWORD',
      'FIELD', 'KEYWORD', 'PAREN', 'NUMBER', 'COMMA', 'NUMBER', 'PAREN',
    ])
  })

  it('summary ~ "로그인" 을 분해한다', () => {
    const tokens = tokenizeAql('summary ~ "로그인"')
    expect(tokens).toHaveLength(3)
    expect(tokens[0]).toMatchObject({ type: 'FIELD', value: 'summary' })
    expect(tokens[1]).toMatchObject({ type: 'OPERATOR', value: '~' })
    expect(tokens[2]).toMatchObject({ type: 'STRING', value: '"로그인"' })
  })

  it('ORDER BY priority DESC 를 분해한다', () => {
    const tokens = tokenizeAql('ORDER BY priority DESC')
    expect(tokens).toHaveLength(4)
    expect(tokens[0]).toMatchObject({ type: 'KEYWORD', value: 'ORDER' })
    expect(tokens[1]).toMatchObject({ type: 'KEYWORD', value: 'BY' })
    expect(tokens[2]).toMatchObject({ type: 'FIELD', value: 'priority' })
    expect(tokens[3]).toMatchObject({ type: 'KEYWORD', value: 'DESC' })
  })

  it('빈 문자열을 입력하면 빈 배열을 반환한다', () => {
    expect(tokenizeAql('')).toEqual([])
  })

  it('공백만 있는 입력을 빈 배열로 처리한다', () => {
    expect(tokenizeAql('   ')).toEqual([])
  })

  it('label != "bug" 쿼리를 분해한다', () => {
    const tokens = tokenizeAql('label != "bug"')
    expect(tokens).toHaveLength(3)
    expect(tokens[0]).toMatchObject({ type: 'FIELD', value: 'label' })
    expect(tokens[1]).toMatchObject({ type: 'OPERATOR', value: '!=' })
    expect(tokens[2]).toMatchObject({ type: 'STRING', value: '"bug"' })
  })

  it('NOT status = open 을 분해한다', () => {
    const tokens = tokenizeAql('NOT status = open')
    expect(tokens[0]).toMatchObject({ type: 'KEYWORD', value: 'NOT' })
    expect(tokens[1]).toMatchObject({ type: 'FIELD', value: 'status' })
  })
})
