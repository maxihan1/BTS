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

/**
 * 정규식 메타문자를 이스케이프한다.
 *
 * ★필드와 연산자 **양쪽**에 쓴다. 한쪽만 이스케이프하면 비대칭이 남는데, 두 목록 다
 * 백엔드 정본의 미러(`AqlFields.MVP_FIELDS` · `AqlTokenType`)라 어느 쪽에 메타문자가
 * 먼저 들어올지 여기서 알 수 없다. 지금은 필드가 전부 `[a-z]+` 라 무해하지만, 그 무해함은
 * 백엔드가 지키는 성질이지 이 파일이 지키는 성질이 아니다.
 */
const escapeRegExp = (raw: string) => raw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

const AQL_OPERATOR_ALTERNATION = [...AQL_OPERATORS]
  .sort((a, b) => b.length - a.length)
  .map(escapeRegExp)
  .join('|')

const AQL_FIELD_ALTERNATION = AQL_FIELDS.map(escapeRegExp).join('|')

const AQL_PREFIX_PATTERN = new RegExp(
  `^(?:${AQL_FIELD_ALTERNATION})\\s*(?:${AQL_OPERATOR_ALTERNATION})`,
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

  // ★null 이면 empty 로 떨어뜨린다. 지금 buildTextQuery 는 「공백만 또는 빈 문자열」일 때만
  // null 을 주고(aql-text-query.ts line 31~32) 이 함수 첫머리가 그 경우를 이미 걸렀으므로
  // 이 가지는 실제로 도달하지 않는다. 그래도 단언(`as string`)으로 좁히지 않는 이유는,
  // 나중에 buildTextQuery 에 길이 상한 같은 새 null 반환 조건이 붙으면 단언이 `null` 을
  // `string` 으로 위장시켜 **빈 검색이 조용히 나가기** 때문이다 — 컴파일러가 못 잡는다.
  // 분기로 두면 그때 자동으로 「아무 데도 가지 않는다」로 안전하게 접힌다.
  const query = buildTextQuery(trimmed)
  if (query === null) return { kind: 'empty' }
  return { kind: 'text', query }
}
