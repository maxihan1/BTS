// 상단바 전역 검색 입력의 3갈래 판별 — 이슈키 / AQL / 자유 텍스트 (FR-UX-12 F13 FR5~FR8)
//
// ★이 파일은 순수하다. 활성 프로젝트·네트워크·라우터에 의존하지 않는다
// (F4 의 palette-input.ts ADR D-5 와 같은 규율). 목적지 결정은 TopBar 가 한다.
// ★필드·연산자·이스케이프를 새로 선언하지 않는다 — 전부 기존 lib/ 자산을 재사용한다.
//   목록을 복제하면 백엔드 AqlFields.MVP_FIELDS 와의 drift 를 아무도 못 본다.
import { AQL_FIELDS, AQL_OPERATORS } from './aql-tokenizer'
import { buildTextQuery } from './aql-text-query'
import { ISSUE_KEY_PATTERN } from './issue-key'

/**
 * 전역 검색 입력의 판별 결과 — 판별 유니온.
 *
 * - `issue-key`: 이슈 키 형식. 대문자로 정규화된 키를 싣는다 → `/issues/$key`
 * - `aql`      : AQL 문법으로 보이는 입력. **원문 그대로** 싣는다 → `/search?q=<원문>`
 * - `text`     : 그 외. `text ~ "…"` 로 감싼 쿼리를 싣는다 → `/search?q=<래핑>`
 * - `empty`    : 공백만 또는 빈 문자열 — 아무 이동도 하지 않는다
 */
export type GlobalSearchIntent =
  | { kind: 'issue-key'; issueKey: string }
  | { kind: 'aql'; query: string }
  | { kind: 'text'; query: string }
  | { kind: 'empty' }

/**
 * AQL 판별 정규식 — `<알려진 필드> <연산자>` 로 **시작**하는지만 본다.
 *
 * ★판정 정본은 백엔드 렉서다. 여기는 「AQL 을 치려던 것인가」를 가르는 최소 휴리스틱이라
 * 보수적으로 잡는다 — 애매하면 자유 텍스트로 보내는 편이 안전하다(전문검색은 항상 성립하지만
 * 잘못 통과시킨 AQL 은 400 이 된다).
 * ★연산자는 긴 것부터 정렬해야 `!=` 가 `=` 에 먼저 먹히지 않는다.
 */
const AQL_OPERATOR_ALTERNATION = [...AQL_OPERATORS]
  .sort((a, b) => b.length - a.length)
  .map((op) => op.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  .join('|')

const AQL_PREFIX_PATTERN = new RegExp(
  `^(?:${AQL_FIELDS.join('|')})\\s*(?:${AQL_OPERATOR_ALTERNATION})`,
  'i',
)

/**
 * 상단바 전역 검색 입력을 3갈래로 판별한다.
 *
 * **순서가 계약이다.** 빈 값 → 이슈키 → AQL → 자유 텍스트. 이슈키를 AQL 보다 먼저 보는 이유는
 * `ATLAS-42` 가 어떤 필드명으로도 시작하지 않아 충돌하지 않지만, 순서를 뒤집으면 나중에 필드가
 * 늘었을 때 조용히 갈래가 바뀔 수 있기 때문이다.
 *
 * @param input 입력창의 원본 문자열
 * @returns 판별 유니온
 */
export function resolveGlobalSearchInput(input: string): GlobalSearchIntent {
  const trimmed = input.trim()
  if (trimmed === '') return { kind: 'empty' }

  const upper = trimmed.toUpperCase()
  if (ISSUE_KEY_PATTERN.test(upper)) {
    return { kind: 'issue-key', issueKey: upper }
  }

  if (AQL_PREFIX_PATTERN.test(trimmed)) {
    return { kind: 'aql', query: trimmed }
  }

  // buildTextQuery 는 공백만일 때 null 을 주지만 위에서 이미 걸렀다
  return { kind: 'text', query: buildTextQuery(trimmed) as string }
}
