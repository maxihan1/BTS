// 팔레트 비-슬래시 입력의 2계층 판별 — 이슈키 / 자유텍스트 / 빈 (FR-UX-12 F4 ADR D-1·D-5)
//
// ★이 파일은 순수하다(ADR D-5). 활성 프로젝트·네트워크·훅에 의존하지 않는다 —
// 조회는 use-palette-search 가 맡는다.
// ★commands.ts 를 import 하지 않는다(ADR D-1 경계). 슬래시 판별은 parseCommand 가
// 이미 끝냈고, 이 함수는 그 결과가 'not-command' 일 때만 불린다.
import { ISSUE_KEY_PATTERN } from '@/lib/issue-key'

/**
 * 비-슬래시 팔레트 입력의 판별 결과 — 판별 유니온.
 *
 * - `issue-key`: 이슈 키 형식. 대문자로 정규화된 키를 싣는다
 * - `free-text` : 그 외 검색 가능한 텍스트
 * - `empty`    : 공백만 또는 빈 문자열 — 검색을 호출하지 않는다(E12)
 */
export type PaletteInput =
  | { kind: 'issue-key'; issueKey: string }
  | { kind: 'free-text'; query: string }
  | { kind: 'empty' }

/**
 * `parseCommand` 가 `not-command` 를 반환한 입력을 2계층으로 판별한다.
 *
 * **호출 순서가 계약이다(FR2).** 반드시 `parseCommand` 뒤에 부른다. 먼저 부르면
 * `/goto ATLAS-1` 이 자유 텍스트로 새어 슬래시 명령이 죽는다.
 *
 * @param input 팔레트 입력창의 원본 문자열
 * @returns 판별 유니온
 */
export function resolveNonCommandInput(input: string): PaletteInput {
  const trimmed = input.trim()
  if (trimmed === '') return { kind: 'empty' }

  const upper = trimmed.toUpperCase()
  if (ISSUE_KEY_PATTERN.test(upper)) {
    return { kind: 'issue-key', issueKey: upper }
  }

  return { kind: 'free-text', query: trimmed }
}
