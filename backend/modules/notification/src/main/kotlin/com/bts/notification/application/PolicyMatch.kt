// 알림 정책 평가 결과 1건 — 누구(역할)에게 어느 채널로 발송할지를 담는 값 객체

package com.bts.notification.application

import com.bts.notification.domain.Channel
import com.bts.notification.domain.RecipientRole

/**
 * [NotificationPolicyEvaluator.evaluate] 가 반환하는 평가 결과 1건.
 *
 * 단일 정책 행에서 발송에 필요한 두 가지 정보만 추출한다.
 * - [recipientRole]: 알림을 수신할 역할 (예: ASSIGNEE, REPORTER)
 * - [channel]: 발송 수단 (예: EMAIL, IN_APP)
 *
 * 불변 data class 로 선언해 평가 이후 값 변경이 불가하다.
 *
 * @param recipientRole 알림을 받을 역할
 * @param channel 알림 전송 채널
 */
data class PolicyMatch(
    val recipientRole: RecipientRole,
    val channel: Channel,
)
