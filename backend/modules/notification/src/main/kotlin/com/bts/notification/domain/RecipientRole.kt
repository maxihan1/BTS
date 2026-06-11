// 알림 수신 역할 enum — DB 저장값은 enum 명 그대로, fromWire 역매핑 포함

package com.bts.notification.domain

/**
 * 알림 정책에서 수신 대상을 지정하는 역할 카탈로그.
 *
 * DB `recipient_role` 컬럼에는 enum 이름(name) 그대로 저장한다.
 * 역매핑은 [fromWire] 를 통해 수행한다.
 */
enum class RecipientRole {
    /** 이슈 보고자 */
    REPORTER,

    /** 이슈 담당자 */
    ASSIGNEE,

    /** 이전 담당자 */
    PREVIOUS_ASSIGNEE,

    /** 이슈 구독자 */
    WATCHER,

    /** 컴포넌트 담당자 */
    COMPONENT_LEAD,

    /** 본문/댓글에서 @멘션된 사용자 */
    MENTIONED,

    /** 프로젝트 전체 구성원 */
    PROJECT_MEMBER,

    /** 자동화 룰 소유자 */
    RULE_OWNER,

    /** 프로젝트 관리자 */
    PROJECT_ADMIN,
    ;

    companion object {
        /**
         * enum 이름 문자열로부터 [RecipientRole] 을 찾는다.
         *
         * 알 수 없는 값이면 예외 대신 `null` 을 반환한다.
         *
         * @param name DB에 저장된 enum 이름 문자열
         * @return 매핑된 enum 상수, 없으면 `null`
         */
        fun fromWire(name: String): RecipientRole? {
            return runCatching { valueOf(name) }.getOrNull()
        }
    }
}
