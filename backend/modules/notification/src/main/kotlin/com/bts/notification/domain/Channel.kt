// 알림 채널 enum — 지원하는 5종 전송 채널 카탈로그

package com.bts.notification.domain

/**
 * 알림 정책에서 전송 수단을 지정하는 채널 카탈로그.
 *
 * DB `channel` 컬럼에는 enum 이름(name) 그대로 저장한다.
 * 역매핑은 [fromWire] 를 통해 수행한다.
 */
enum class Channel {
    /** 이메일 전송 */
    EMAIL,

    /** 서비스 내 알림함 */
    IN_APP,

    /** Slack 메시지 */
    SLACK,

    /** Microsoft Teams 메시지 */
    TEAMS,

    /** 외부 웹훅 HTTP 호출 */
    WEBHOOK,
    ;

    companion object {
        /**
         * enum 이름 문자열로부터 [Channel] 을 찾는다.
         *
         * 알 수 없는 값이면 예외 대신 `null` 을 반환한다.
         *
         * @param name DB에 저장된 enum 이름 문자열
         * @return 매핑된 enum 상수, 없으면 `null`
         */
        fun fromWire(name: String): Channel? =
            runCatching { valueOf(name) }.getOrNull()
    }
}
