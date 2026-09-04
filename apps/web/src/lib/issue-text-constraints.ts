// 이슈 제목·본문·댓글 길이 제약의 프론트 단일 출처 — 백엔드 IssueTextConstraints 의 거울
/**
 * 이슈 텍스트 길이 제약.
 *
 * ## 왜 이 파일이 생겼나
 *
 * 2026-09-04 실측 — 서버는 본문·댓글을 32767자로 막는데 **프론트에는 상수도 노출도 없었다.**
 * 32767 은 `api/issues.ts` 주석에만 있었고, 본문이 contenteditable 이라 `maxLength` 속성조차
 * 걸 수 없다. 사용자는 상한을 넘길 때까지 아무 신호를 못 받고 **저장 시 서버 400 으로 처음
 * 안다** — 32767자를 쓴 뒤에야 알게 되는 경로였다.
 *
 * ## 백엔드 정본
 *
 * `backend/modules/issue-tracking/.../domain/IssueTextConstraints.kt`. 그쪽이 DB 컬럼·도메인
 * `require`·REST `@Size` 를 함께 묶는 단일 출처이고, 이 파일은 그 값의 **사본**이다.
 *
 * 사본이므로 두 목록이 서로를 검사하지 않는 지배 결함 양식에 그대로 노출된다
 * (`two-lists-never-check-each-other`). 그래서 짝 판별식을 함께 둔다 —
 * `scripts/workflow/issue-text-constraints-alignment.test.ts` 가 Kotlin 파일을 텍스트로 읽어
 * 이 파일의 값과 대조하고, 어긋나면 red 다. 값을 고칠 때는 **양쪽을 같은 PR 에서** 고친다.
 *
 * ## ★무엇을 재는가 — 필드마다 다르다
 *
 * | 상수 | 서버가 재는 문자열 | 근거 |
 * |---|---|---|
 * | [SUMMARY_MAX_LENGTH] | 제목 평문 | `@Size` on `summary` |
 * | [DESCRIPTION_MAX_LENGTH] | **정화된 HTML** | `@Size` on `description` **과** `descriptionHtml` |
 * | [COMMENT_BODY_MAX_LENGTH] | **평문** | `CommentApplicationService.validateBody(body)` — body 는 raw 텍스트 |
 *
 * 본문은 HTML 을, 댓글은 평문을 재야 한다. 섞으면 카운터가 거짓말을 한다 — 서식이 많은 본문은
 * 보이는 글자가 8,000자라도 HTML 이 32767자를 넘을 수 있다. 그래서 재는 문자열은 이 모듈이
 * 정하지 않고 **호출부가 넘긴다**.
 */

/** 이슈 제목 최대 글자 수. 백엔드 `IssueTextConstraints.SUMMARY_MAX`. */
export const SUMMARY_MAX_LENGTH = 255

/** 이슈 본문 최대 글자 수 — 정화된 HTML 기준. 백엔드 `IssueTextConstraints.DESCRIPTION_MAX`. */
export const DESCRIPTION_MAX_LENGTH = 32767

/** 댓글 본문 최대 글자 수 — 평문 기준. 백엔드 `IssueTextConstraints.COMMENT_BODY_MAX`. */
export const COMMENT_BODY_MAX_LENGTH = 32767

/**
 * 카운터를 화면에 띄우기 시작하는 비율.
 *
 * 32767 은 일상적인 글쓰기가 닿지 않는 값이다. 늘 띄우면 모든 에디터 아래에 쓸모없는 숫자가
 * 하나씩 붙고, 정작 필요한 순간의 신호가 배경 소음에 묻힌다. 임계에 다가갈 때만 나타나게 해
 * **숫자가 보인다는 것 자체가 경고**가 되도록 한다.
 */
export const LENGTH_COUNTER_VISIBLE_RATIO = 0.9

/**
 * 지금 카운터를 보여야 하나.
 *
 * @param length 현재 길이 (호출부가 재는 문자열의 길이)
 * @param max 상한
 * @returns 임계(90%)에 닿았거나 넘었으면 true
 */
export function shouldShowLengthCounter(length: number, max: number): boolean {
  return length >= max * LENGTH_COUNTER_VISIBLE_RATIO
}
