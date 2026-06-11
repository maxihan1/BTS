// notification BC 전용 알림 정책 예외 3종 — 이름 충돌 방지를 위해 NotificationPolicy 접두사 사용

package com.bts.notification.application

import java.util.UUID

/**
 * 알림 정책 관리 권한이 없는 행위자가 조작을 시도할 때 발생하는 예외.
 *
 * HTTP 응답 코드 403에 매핑된다 (Task 6 컨트롤러에서 처리).
 * message에 actorId나 권한 상세 정보를 포함하지 않는다
 * — 내부 권한 구조 누출 방지 (memory: fr-pm-04-guard-exception-message-http-leak 교훈).
 *
 * @param message 사용자에게 전달할 일반화된 메시지
 */
class NotificationPolicyForbiddenException(
    message: String,
) : RuntimeException(message)

/**
 * 요청한 ID에 해당하는 알림 정책이 존재하지 않을 때 발생하는 예외.
 *
 * HTTP 응답 코드 404에 매핑된다 (Task 6 컨트롤러에서 처리).
 *
 * @param id 조회하려 했던 정책 식별자
 */
class NotificationPolicyNotFoundException(
    val id: UUID,
) : RuntimeException("알림 정책을 찾을 수 없습니다. id=$id")

/**
 * 동일한 (projectKey, eventType, recipientRole, channel) 조합의 알림 정책이 이미 존재할 때 발생하는 예외.
 *
 * HTTP 응답 코드 409에 매핑된다 (Task 6 컨트롤러에서 처리).
 * UNIQUE 제약(uq_notification_policy) 위반을 서비스 계층에서 도메인 예외로 변환한 결과다.
 * cause 를 보존하여 원본 스택 트레이스 유실을 방지한다 (SwallowedException 방지).
 *
 * @param message 중복 내용을 설명하는 메시지
 * @param cause 원본 DB 예외 (스택 트레이스 보존용)
 */
class NotificationPolicyDuplicateException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
