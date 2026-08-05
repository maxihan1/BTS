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
 * ★긴 연산자부터 정렬하는 것은 **현재 동작에 영향이 없는 방어**다(실측). 정규식 교대는
 * 역추적으로 모든 가지를 시도하므로 `test()` 결과는 순서와 무관하고, 현 3종(`=` `!=` `~`)은
 * 서로 접두사 관계가 아니라 `exec()` 로 바꿔도 결과가 같다 — `!=` 는 `!` 로 시작해 `=` 에
 * 먹힐 수 없다. 정렬이 실제로 갈리는 시점은 `>` 와 `>=` 처럼 **접두사를 공유하는** 연산자가
 * 백엔드에 추가되고, 이 파일이 매치 문자열까지 읽을 때다. 그때를 위해 미리 세워 둔다.
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

  // ★`as string` 근거. buildTextQuery 는 「공백만 또는 빈 문자열」일 때만 null 을 주는데
  // (aql-text-query.ts line 31~32), 이 함수 첫머리에서 `trimmed === ''` 를 이미 empty 로
  // 걸러 반환했으므로 여기 도달한 trimmed 는 비어 있을 수 없다. 즉 null 이 불가능한 지점이라
  // 좁히는 단언이지, null 을 덮는 `!` 단언이 아니다(DEVELOPMENT.md §1.3-12).
  return { kind: 'text', query: buildTextQuery(trimmed) as string }
}
