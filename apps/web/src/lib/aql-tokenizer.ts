// AQL(Atlas Query Language) 입력 문자열을 토큰 배열로 분해하는 단일 패스 렉서 (FR-SR-02 D6)

/**
 * AQL 토큰 타입.
 *
 * - KEYWORD: 예약 키워드 (and/or/not/in/order/by/asc/desc, 대소문자 무시)
 * - FIELD:   지원 필드명 (status/label/summary/priority/text, 대소문자 무시)
 * - OPERATOR: 비교 연산자 (= / != / ~)
 * - STRING:  큰따옴표 문자열 리터럴 (이스케이프 포함)
 * - NUMBER:  정수 리터럴
 * - PAREN:   괄호 ( )
 * - COMMA:   쉼표 ,
 * - PLAIN:   위 분류에 해당하지 않는 bare word
 */
export type AqlTokenType =
  | 'KEYWORD'
  | 'FIELD'
  | 'OPERATOR'
  | 'STRING'
  | 'NUMBER'
  | 'PAREN'
  | 'COMMA'
  | 'PLAIN'

/** 단일 AQL 토큰. start/end는 입력 문자열 내 0-base 인덱스(end는 exclusive). */
export interface AqlToken {
  /** 토큰 타입 */
  type: AqlTokenType
  /** 원본 텍스트 값 (STRING 타입은 따옴표 포함) */
  value: string
  /** 입력 문자열 내 시작 인덱스 (0-base, inclusive) */
  start: number
  /** 입력 문자열 내 끝 인덱스 (0-base, exclusive) */
  end: number
}

// ─────────────────────────────────────────────────────────────────────────────
// 상수 정본
// 백엔드 정본: shared-kernel AqlFields.MVP_FIELDS / AqlToken.KEYWORDS — 변경 시 동반 수정
// ─────────────────────────────────────────────────────────────────────────────

/**
 * AQL 예약 키워드 목록 (소문자).
 *
 * 백엔드 정본: `com.bts.search.aql.AqlToken` KEYWORDS 맵(line 90).
 * 대소문자 무시 비교를 위해 소문자로 저장한다.
 */
export const AQL_KEYWORDS: readonly string[] = [
  'and',
  'or',
  'not',
  'in',
  'order',
  'by',
  'asc',
  'desc',
] as const

/**
 * AQL 지원 필드명 목록 (소문자).
 *
 * 백엔드 정본: `com.bts.shared.search.AqlFields` MVP_FIELDS(line 41).
 * 하이라이트 best-effort용 — 판정 정본은 백엔드 렉서.
 */
export const AQL_FIELDS: readonly string[] = [
  'status',
  'label',
  'summary',
  'priority',
  'text',
] as const

/**
 * AQL 연산자 목록.
 *
 * 백엔드 정본: `com.bts.search.aql.AqlTokenType` EQ/NEQ/TILDE.
 */
export const AQL_OPERATORS: readonly string[] = ['=', '!=', '~'] as const

// ─────────────────────────────────────────────────────────────────────────────
// 내부 상수 (Set 변환 — O(1) 조회)
// ─────────────────────────────────────────────────────────────────────────────
const KEYWORD_SET = new Set(AQL_KEYWORDS)
const FIELD_SET = new Set(AQL_FIELDS)

/** 공백 문자 정규식 */
const WHITESPACE_RE = /\s/

/** 숫자 문자 정규식 */
const DIGIT_RE = /[0-9]/

// ─────────────────────────────────────────────────────────────────────────────
// 토크나이저
// ─────────────────────────────────────────────────────────────────────────────

/**
 * AQL 입력 문자열을 토큰 배열로 분해한다.
 *
 * 단일 패스 문자 순회 방식. 공백을 구분자로 사용하며 연산자·괄호·쉼표는
 * 공백 없이도 분리된다.
 *
 * 하이라이트 용도 best-effort 렉서다. 판정 정본은 백엔드 AQL 파서이므로
 * 이 함수의 결과와 백엔드 파싱 결과가 일부 다를 수 있다.
 *
 * @param query AQL 입력 문자열
 * @returns 분해된 토큰 배열 (공백 토큰 제외)
 */
export function tokenizeAql(query: string): AqlToken[] {
  const tokens: AqlToken[] = []
  let pos = 0

  while (pos < query.length) {
    // 1. 공백 스킵
    if (WHITESPACE_RE.test(query[pos] ?? '')) {
      pos++
      continue
    }

    // 2. 따옴표 문자열
    if (query[pos] === '"') {
      const start = pos
      pos++ // 여는 따옴표 소비
      while (pos < query.length) {
        if (query[pos] === '\\') {
          pos += 2 // 이스케이프 문자 건너뜀
        } else if (query[pos] === '"') {
          pos++ // 닫는 따옴표 소비
          break
        } else {
          pos++
        }
      }
      tokens.push({ type: 'STRING', value: query.slice(start, pos), start, end: pos })
      continue
    }

    // 3. != 연산자 (! 뒤에 = 가 오는 경우만 — 단독 ! 는 PLAIN)
    if (query[pos] === '!' && query[pos + 1] === '=') {
      tokens.push({ type: 'OPERATOR', value: '!=', start: pos, end: pos + 2 })
      pos += 2
      continue
    }

    // 4. 단일 문자 연산자/구분자
    const ch = query[pos] ?? ''
    if (ch === '=') {
      tokens.push({ type: 'OPERATOR', value: '=', start: pos, end: pos + 1 })
      pos++
      continue
    }
    if (ch === '~') {
      tokens.push({ type: 'OPERATOR', value: '~', start: pos, end: pos + 1 })
      pos++
      continue
    }
    if (ch === '(' || ch === ')') {
      tokens.push({ type: 'PAREN', value: ch, start: pos, end: pos + 1 })
      pos++
      continue
    }
    if (ch === ',') {
      tokens.push({ type: 'COMMA', value: ',', start: pos, end: pos + 1 })
      pos++
      continue
    }

    // 5. 숫자 (연속된 자릿수)
    if (DIGIT_RE.test(ch)) {
      const start = pos
      while (pos < query.length && DIGIT_RE.test(query[pos] ?? '')) {
        pos++
      }
      tokens.push({ type: 'NUMBER', value: query.slice(start, pos), start, end: pos })
      continue
    }

    // 6. 식별자 bare word — 공백·연산자·괄호·쉼표까지 읽음
    const wordStart = pos
    while (pos < query.length) {
      const c = query[pos] ?? ''
      if (
        WHITESPACE_RE.test(c) ||
        c === '=' ||
        c === '!' ||
        c === '~' ||
        c === '(' ||
        c === ')' ||
        c === ',' ||
        c === '"'
      ) {
        break
      }
      pos++
    }
    const word = query.slice(wordStart, pos)
    const wordLower = word.toLowerCase()

    if (KEYWORD_SET.has(wordLower)) {
      tokens.push({ type: 'KEYWORD', value: word, start: wordStart, end: pos })
    } else if (FIELD_SET.has(wordLower)) {
      tokens.push({ type: 'FIELD', value: word, start: wordStart, end: pos })
    } else {
      tokens.push({ type: 'PLAIN', value: word, start: wordStart, end: pos })
    }
  }

  return tokens
}
