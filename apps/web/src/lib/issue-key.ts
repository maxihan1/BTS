// 이슈 키 형식의 단일 출처 — 팔레트 2곳 + 전역 검색이 공유한다 (FR-UX-12 F13)
//
// ★왜 lib/ 인가. 이 정규식은 F13 착수 시점에 command-palette/commands.ts 와
// palette-input.ts 두 곳에 복제돼 있었고, 후자의 주석이 "commands.ts 의
// ISSUE_KEY_PATTERN 과 같은 규칙"이라며 스스로 복제임을 자백하고 있었다.
// 전역 검색이 세 번째 사본을 만들면 3중 복제가 되므로 여기 한 곳으로 올린다.
// lib/aql-text-query.ts 가 소비처 둘을 이유로 lib/ 에 놓인 것과 같은 근거(F4 ADR D-2).

/**
 * 이슈 키 형식 — `프로젝트키-번호`. **대문자로 정규화한 뒤** 검사한다.
 *
 * `^…$` 앵커가 있으므로 `ATLAS-42 로그인` 처럼 뒤에 토큰이 붙으면 불일치다
 * (자유 텍스트로 흘러야 한다).
 *
 * ★전역 플래그를 붙이지 말 것 — `test()` 가 `lastIndex` 를 남겨 호출마다 결과가 달라진다.
 */
export const ISSUE_KEY_PATTERN = /^[A-Z][A-Z0-9]*-\d+$/
