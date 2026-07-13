// 이벤트 유형별 알림 제목을 생성하는 빌더 — NotificationWorker와 SlackChannelBroadcaster가 공유

package com.bts.notification.channel

import com.bts.notification.domain.NotificationEventType
import com.bts.notification.recipient.NotificationSourceEvent
import org.springframework.stereotype.Component

/**
 * 이벤트 유형에 따라 알림 제목을 생성하는 빌더.
 *
 * actor 이름 조회 없이 간단 문자열로 구성한다 (FR10 설계 제약).
 * [com.bts.notification.worker.NotificationWorker] 의 알림(title) 생성과
 * [SlackChannelBroadcaster] 의 채널 브로드캐스트 제목 생성이 이 로직을 공유한다 (FR-SL-06 PR-B).
 */
@Component
class NotificationTitleBuilder {
    /**
     * 이벤트 유형과 이슈 키를 기반으로 알림 제목을 생성한다.
     *
     * @param event 원본 이벤트
     * @return 생성된 제목 문자열
     */
    fun buildTitle(event: NotificationSourceEvent): String {
        val issueRef = event.issueKey ?: ""
        return when (event.eventType) {
            NotificationEventType.ISSUE_MENTIONED -> "$issueRef 에서 멘션되었습니다"
            NotificationEventType.ISSUE_CREATED -> "$issueRef 이슈가 생성되었습니다"
            NotificationEventType.ISSUE_TRANSITIONED -> "$issueRef 상태가 변경되었습니다"
            NotificationEventType.ISSUE_ASSIGNED -> "$issueRef 이슈가 할당되었습니다"
            NotificationEventType.ISSUE_COMMENTED -> "$issueRef 에 댓글이 작성되었습니다"
            NotificationEventType.ISSUE_DUE_SOON -> "$issueRef 마감이 임박했습니다"
            NotificationEventType.ISSUE_OVERDUE -> "$issueRef 마감이 초과되었습니다"
            NotificationEventType.SPRINT_STARTED -> "스프린트가 시작되었습니다"
            NotificationEventType.SPRINT_ENDED -> "스프린트가 종료되었습니다"
            NotificationEventType.AUTOMATION_FAILED -> "자동화 룰 실행에 실패했습니다"
        }
    }
}
