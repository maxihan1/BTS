// 자유 텍스트를 AQL 전문검색 쿼리(text ~ "…")로 감싸는 순수 헬퍼 — FR-UX-12 F4 ADR D-2
//
// ★왜 lib/ 인가 (ADR D-2). 소비처가 둘이다 — `runCommand`(슬래시 `/search` 네비게이션,
// CommandPalette.tsx)와 `use-palette-search`(라이브 검색 훅). 훅을 CommandPalette 가
// import 하므로 이 함수를 컴포넌트 파일에 두면 훅→컴포넌트 순환 import 가 된다.
// 두 소비처가 각자 이스케이프하면 drift 가 확정되므로 여기 한 곳에만 둔다.

/**
 * AQL 문자열 리터럴 안에서 특수문자를 이스케이프한다.
 *
 * ★역슬래시를 **먼저** 치환한다. 큰따옴표를 먼저 치환하면 그때 삽입한 역슬래시가
 * 다음 단계에서 다시 이스케이프돼 `\\"` 가 된다(이중 이스케이프).
 *
 * @param raw 원본 문자열
 * @returns AQL 문자열 리터럴에 안전하게 넣을 수 있는 문자열
 */
export function escapeAqlString(raw: string): string {
  return raw.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
}

/**
 * 자유 텍스트를 AQL 전문검색 쿼리로 변환한다.
 *
 * `text` 는 가상 FTS 필드로 `~` 연산자만 허용한다(FR-SR-04 ADR D4 ·
 * `AqlFields.kt` FIELD_OPERATOR_CONSTRAINTS).
 *
 * @param raw 사용자가 입력한 자유 텍스트
 * @returns AQL 쿼리 문자열. 공백만이거나 비어 있으면 null(검색 호출 금지 신호)
 */
export function buildTextQuery(raw: string): string | null {
  const trimmed = raw.trim()
  if (trimmed === '') return null
  return `text ~ "${escapeAqlString(trimmed)}"`
}
