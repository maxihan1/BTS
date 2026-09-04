// 이슈 제목·본문 길이 제약의 단일 출처 — 도메인 불변식과 REST 검증이 같은 값을 본다

package com.bts.issue.domain

/**
 * 이슈 텍스트 필드 길이 제약 상수.
 *
 * ## 왜 별도 파일인가 — [IssueLabelConstraints] 와 같은 이유, 같은 사고
 *
 * 2026-09-04 이전에 `summary` 상한이 **네 곳**에 사본으로 있었고 실제로 어긋나 있었다.
 *
 * | 층 | 위치 | 값 |
 * |---|---|---|
 * | DB 컬럼 | `V001__issues_initial.sql` `VARCHAR(255)` | 255 |
 * | 도메인 불변식 | `Issue.kt` `require(summary.length <= 255)` | 255 |
 * | REST 생성·수정 | `CreateIssueRequest` · `UpdateIssueRequest` | **200** |
 * | REST 클론 | `CloneIssueRequest.summaryOverride` | 255 |
 *
 * 200 은 근거가 없는 값이었다. 프론트가 500 을 허용하던 시절 201~500자가 프론트를 통과해
 * 백엔드 400 을 맞았고, FR-UX-09 F2 가 **프론트를 백엔드(200)에 맞춰** 봉합했다. 그때 아무도
 * 백엔드의 200 자체가 DB·도메인(255)보다 좁다는 것을 되재지 않았다. 결과는 「클론으로는 255자
 * 제목을 만들 수 있는데 그 이슈를 열어 저장하면 400」이라는 비대칭이다.
 *
 * ## 값의 근거 — Jira Cloud (2026-09-04 조회)
 *
 * - [SUMMARY_MAX] 255 — "The 255 character limit is for single-line text fields, like Summary.
 *   You can not change it." DB·도메인이 이미 255라 마이그레이션이 필요 없다.
 * - [DESCRIPTION_MAX] 32767 — "Description is a long text that defaults to max 32767 characters."
 *   기존 65535 는 Jira 보다 넓었다. 좁히는 방향이라 **기존 데이터가 저장 불가가 될 수 있다** —
 *   32767 초과 본문이 실재하면 그 이슈는 수정이 막힌다. 현재 그런 행은 없다고 보고 적용하되,
 *   운영 데이터가 쌓인 뒤 이 값을 더 좁히려면 사전 실측이 필요하다.
 * - [COMMENT_BODY_MAX] 32767 — 본문과 같은 상한. 기존 32000 은 근거 없이 조금 좁았다.
 *
 * ## ★값을 바꿀 때
 *
 * 여기 한 곳만 고치면 REST 층이 함께 움직인다. **도메인 `require` 와 DB 컬럼 길이는 별개**다 —
 * [SUMMARY_MAX] 를 255 위로 올리려면 `Issue.kt` 와 마이그레이션이 함께 가야 한다.
 * 세 층 정렬은 `IssueTextConstraintsAlignmentTest` 가 강제한다.
 */
object IssueTextConstraints {
    /** 이슈 제목 최대 글자 수. DB `VARCHAR(255)` · 도메인 `require` 와 같은 값이어야 한다. */
    const val SUMMARY_MAX = 255

    /** 이슈 본문 최대 글자 수 (마크다운 원문 · 정화된 HTML 공통). */
    const val DESCRIPTION_MAX = 32767

    /** 댓글 본문 최대 글자 수. */
    const val COMMENT_BODY_MAX = 32767
}
