// 알림 이벤트 유형 enum — DB 저장용 wireValue 와 publishable 메타를 포함한 10종 카탈로그

package com.bts.notification.domain

/**
 * 알림 시스템이 구독하는 도메인 이벤트 유형 카탈로그.
 *
 * 각 상수는 두 가지 메타를 보유한다.
 * - [wireValue]: DB `event_type` 컬럼 저장값이자 pgmq 메시지의 이벤트 식별자.
 *   V401 시드 + issue-tracking 이벤트 발행 값과 일치해야 한다.
 * - [publishable]: `true` 면 외부 웹훅·이메일 등 "발행(publish)" 채널로 전송 가능.
 *   `false` 면 인앱 알림 같은 내부 채널에만 사용한다.
 *
 * @param wireValue DB / 메시지 페이로드에서 사용하는 문자열 식별자
 * @param publishable 외부 발행 채널 허용 여부
 */
enum class NotificationEventType(
    val wireValue: String,
    val publishable: Boolean,
) {
    /** 이슈 생성 — 외부 발행 허용 */
    ISSUE_CREATED("issue.created", true),

    /** 이슈 담당자 지정 */
    ISSUE_ASSIGNED("issue.assigned", false),

    /** 이슈 상태 전이 — 외부 발행 허용 */
    ISSUE_TRANSITIONED("issue.transitioned", true),

    /** 이슈 댓글 작성 */
    ISSUE_COMMENTED("issue.commented", false),

    /**
     * 이슈 댓글 삭제 — FR-CO-02 모더레이션 통지.
     *
     * `publishable = false` (외부 채널 불가). 삭제된 댓글의 존재 자체가 외부 웹훅으로 새면
     * 모더레이션 목적에 반한다. 인앱으로 **작성자에게만** 알린다.
     */
    ISSUE_COMMENT_DELETED("issue.comment_deleted", false),

    /** 이슈 마감 임박 */
    ISSUE_DUE_SOON("issue.due_soon", false),

    /** 이슈 마감 초과 */
    ISSUE_OVERDUE("issue.overdue", false),

    /** 스프린트 시작 */
    SPRINT_STARTED("sprint.started", false),

    /** 스프린트 종료 */
    SPRINT_ENDED("sprint.ended", false),

    /** 자동화 룰 실행 실패 */
    AUTOMATION_FAILED("automation.failed", false),

    /** 이슈 멘션 — 인앱 내부 채널 전용 (publishable=false) */
    ISSUE_MENTIONED("issue.mentioned", false),
    ;

    companion object {
        /**
         * [wireValue] 문자열로부터 [NotificationEventType] 을 찾는다.
         *
         * DB 행의 `event_type` 컬럼을 enum 으로 역매핑할 때 사용한다.
         * 알 수 없는 값이면 예외 대신 `null` 을 반환해 호출자가 graceful 처리를 결정하게 한다.
         *
         * @param value DB / 메시지 페이로드에서 읽은 문자열
         * @return 매핑된 enum 상수, 없으면 `null`
         */
        fun fromWire(value: String): NotificationEventType? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}
