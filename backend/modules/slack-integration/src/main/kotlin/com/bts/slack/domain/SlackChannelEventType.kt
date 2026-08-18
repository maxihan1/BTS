// notification NotificationEventType.wireValue 를 문자열로 미러한 이벤트 카탈로그 (FR-SL-06 Task 2)

package com.bts.slack.domain

/**
 * slack-integration BC 가 채널 매핑 이벤트 필터([ChannelProjectMapping.eventTypes])에서 허용하는
 * wire 문자열 카탈로그.
 *
 * ## BC 격리 미러 사유
 * notification 모듈의 `com.bts.notification.domain.NotificationEventType` enum 을 직접 import 하면
 * BC 경계(CLAUDE.md §핵심 패턴 — BC 격리, "다른 BC 호출은 이벤트 발행만, 직접 import 금지")를 위반한다.
 * 대신 notification 이 실제 발행하는 `wireValue` 문자열 10종을 이 카탈로그로 미러한다
 * (`SlackDeliveryWorker.ASSIGNED_EVENT_TYPE` 선례와 동일 패턴). notification 쪽 카탈로그가 바뀌면
 * 이 파일도 함께 갱신해야 한다.
 */
object SlackChannelEventType {
    /** 이슈 생성. */
    const val ISSUE_CREATED = "issue.created"

    /** 이슈 담당자 지정. */
    const val ISSUE_ASSIGNED = "issue.assigned"

    /** 이슈 상태 전환. */
    const val ISSUE_TRANSITIONED = "issue.transitioned"

    /** 이슈 댓글 작성. */
    const val ISSUE_COMMENTED = "issue.commented"

    /** 이슈 마감 임박. */
    const val ISSUE_DUE_SOON = "issue.due_soon"

    /** 이슈 마감 초과. */
    const val ISSUE_OVERDUE = "issue.overdue"

    /** 스프린트 시작. */
    const val SPRINT_STARTED = "sprint.started"

    /** 스프린트 종료. */
    const val SPRINT_ENDED = "sprint.ended"

    /** 자동화 룰 실행 실패. */
    const val AUTOMATION_FAILED = "automation.failed"

    /** 이슈 멘션. */
    const val ISSUE_MENTIONED = "issue.mentioned"

    private val KNOWN_VALUES: Set<String> =
        setOf(
            ISSUE_CREATED,
            ISSUE_ASSIGNED,
            ISSUE_TRANSITIONED,
            ISSUE_COMMENTED,
            ISSUE_DUE_SOON,
            ISSUE_OVERDUE,
            SPRINT_STARTED,
            SPRINT_ENDED,
            AUTOMATION_FAILED,
            ISSUE_MENTIONED,
        )

    /**
     * [wire] 문자열이 이 카탈로그에 알려진 이벤트 유형인지 판정한다.
     *
     * @param wire 검증할 wire 문자열(예: `"issue.created"`).
     * @return 카탈로그에 존재하면 `true`, 그 외 `false`.
     */
    fun isKnown(wire: String): Boolean = wire in KNOWN_VALUES
}
