// 이슈 라벨 제약의 단일 출처 — 도메인 불변식과 REST 검증이 같은 값을 본다

package com.bts.issue.domain

/**
 * 이슈 라벨 제약 상수.
 *
 * ## 왜 별도 파일인가
 *
 * 2026-08-10 이전에는 같은 값이 **세 곳**에 사본으로 있었다.
 *
 * | 층 | 위치 |
 * |---|---|
 * | 도메인 불변식 | `Issue.kt` 의 파일 private `const` |
 * | REST 생성 검증 | `CreateIssueRequest` companion |
 * | REST 수정 검증 | `UpdateIssueRequest` companion |
 *
 * 한 곳만 바꾸면 나머지 둘이 **조용히 어긋난다** — 이 저장소가 이름 붙인
 * `two-lists-never-check-each-other` 양식이다. 실제 위험은 방향에 따라 다르다.
 *
 * - REST 값만 키우면 → REST 는 통과시키는데 도메인이 `IllegalArgumentException` 을 던지고,
 *   `IssueExceptionHandler` 에 그 핸들러가 없어 **사용자 입력 오류가 500 이 된다.**
 * - REST 값만 줄이면 → 도메인이 허용하는 입력을 REST 가 400 으로 막아 **기존 데이터를
 *   수정할 수 없게 된다**(이미 저장된 51자 라벨을 가진 이슈를 열면 저장이 영영 실패).
 *
 * ## ★값을 바꿀 때
 *
 * 여기 한 곳만 고치면 세 층이 함께 움직인다. 다만 **DB 컬럼 길이는 별개**다 —
 * `MAX_LENGTH` 를 늘릴 때는 마이그레이션이 필요한지 `DATA.md` 를 함께 확인할 것.
 *
 * 세 층이 같은 경계에서 동작하는지는 `IssueLabelConstraintsAlignmentTest` 가 강제한다.
 * 그 테스트는 여기 적힌 숫자를 **다시 적지 않고** 이 상수에서 파생시킨다.
 */
object IssueLabelConstraints {
    /** 라벨 한 개의 최대 글자 수. */
    const val MAX_LENGTH = 50

    /** 이슈당 허용되는 라벨 최대 개수 (중복 제거 후 기준). */
    const val MAX_COUNT = 20
}
