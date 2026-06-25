// Inbox 알림 단건 조회/변경 시 대상이 없거나 본인 소유가 아닐 때 발생하는 도메인 예외

package com.bts.notification.inbox.application

/**
 * Inbox 알림 단건 조작 시 대상 항목이 존재하지 않거나 본인 소유가 아닐 때 발생하는 예외.
 *
 * Task 6의 [InboxExceptionHandler] 가 HTTP 404 로 매핑한다.
 *
 * 보안 원칙: 리소스 id 등 민감 정보를 메시지에 포함하지 않는다
 * (메모리 fr-pm-04-guard-exception-message-http-leak).
 */
class InboxItemNotFoundException : RuntimeException("알림 항목을 찾을 수 없습니다.")
