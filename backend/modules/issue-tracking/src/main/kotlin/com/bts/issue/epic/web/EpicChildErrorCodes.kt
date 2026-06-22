// 에픽-자식 연결 BC 에러 코드 상수 — ISSUE_EPIC_ prefix

package com.bts.issue.epic.web

/**
 * 에픽-자식 연결 REST API 에러 코드 상수.
 *
 * [EpicChildExceptionHandler] 가 예외 → HTTP 매핑 시 참조한다.
 * 모든 코드는 `ISSUE_EPIC_` prefix 를 사용한다.
 */
object EpicChildErrorCodes {
    /** 에픽 또는 자식 이슈 미존재 / 소프트 삭제 → 404. */
    const val ISSUE_EPIC_OR_CHILD_NOT_FOUND = "ISSUE_EPIC_OR_CHILD_NOT_FOUND"

    /** child 이슈 유형(hierarchyLevel)이 0 이 아닐 때 → 422. */
    const val ISSUE_EPIC_CHILD_INVALID_TYPE = "ISSUE_EPIC_CHILD_INVALID_TYPE"

    /** epic 자리에 지정한 이슈가 에픽 유형(hierarchyLevel=1)이 아닐 때 → 422. */
    const val ISSUE_EPIC_TARGET_NOT_EPIC = "ISSUE_EPIC_TARGET_NOT_EPIC"

    /** child 와 epic 이 다른 프로젝트에 속할 때 → 422. */
    const val ISSUE_EPIC_CHILD_CROSS_PROJECT = "ISSUE_EPIC_CHILD_CROSS_PROJECT"

    /** 이슈가 자기 자신의 에픽 자식으로 지정될 때 → 422. */
    const val ISSUE_EPIC_CHILD_SELF_REFERENCE = "ISSUE_EPIC_CHILD_SELF_REFERENCE"

    /** child 이슈가 이미 에픽에 연결되어 있을 때 → 409. */
    const val ISSUE_EPIC_CHILD_ALREADY_LINKED = "ISSUE_EPIC_CHILD_ALREADY_LINKED"

    /** 요청 입력 유효성 검증 실패 → 400. */
    const val ISSUE_EPIC_VALIDATION_FAILED = "ISSUE_EPIC_VALIDATION_FAILED"
}
