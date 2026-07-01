// 아웃바운드 webhook 구독 서비스 예외 계층 — HTTP 매핑은 Task 7 스코프 핸들러가 담당 (FR-API-03 PR2)

package com.bts.search.webhook.application

import java.util.UUID

/**
 * 요청한 webhook 구독이 존재하지 않거나 이미 소프트 삭제됨.
 *
 * HTTP 404 신호. Task 7 예외 핸들러가 매핑한다.
 * message 에는 식별자가 포함되므로 HTTP detail 로 직접 노출하지 않고 핸들러가 일반 메시지로 치환한다.
 *
 * @param id 조회/수정/삭제 대상 구독 식별자.
 */
class WebhookNotFoundException(
    id: UUID,
) : RuntimeException("아웃바운드 webhook 구독을 찾을 수 없습니다: $id")

/**
 * 요청자가 SYSTEM_ADMIN 이 아니어서 webhook 관리 권한이 없음.
 *
 * HTTP 403 신호. 리소스 조회 이전 admin 게이트에서 발생하므로 리소스 존재 여부를 노출하지 않는다
 * (존재 probe 차단, auth-extraction-before-resource-lookup). 메시지에는 대상 식별자나 내부 정보를
 * 포함하지 않는다(일반 메시지, DEVELOPMENT §1.1.2).
 */
class WebhookForbiddenException :
    RuntimeException("아웃바운드 webhook 을 관리할 권한이 없습니다")

/**
 * 입력 검증 실패 — SSRF 차단/형식 오류 URL 또는 도메인 불변식 위반(빈·미지 eventFilter, 이름 규칙 등).
 *
 * HTTP 400 신호. SSRF 차단 사유(내부 host/IP)는 메시지에 포함하지 않는다(서버 보안 로그 전용).
 * 도메인 불변식 위반 메시지는 요청자 자신의 입력 규칙이므로 노출 가능하나 비밀값은 포함하지 않는다.
 *
 * @param message 사람이 읽을 수 있는 오류 설명(비밀값·내부 host 미포함).
 * @param cause 원인 예외(도메인 [IllegalArgumentException] 등). 디버그 추적 보존.
 */
class WebhookValidationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 낙관적 동시성 제어(OCC) 충돌 — 다른 요청이 먼저 구독을 수정함.
 *
 * HTTP 409 신호. `OutboundWebhookRepository.update` 가 `null`(0행)을 반환할 때 발생한다.
 * 클라이언트는 최신 버전을 재조회 후 재시도해야 한다.
 *
 * @param id OCC 충돌이 발생한 구독 식별자.
 */
class WebhookConflictException(
    id: UUID,
) : RuntimeException("아웃바운드 webhook 구독이 다른 요청으로 수정되었습니다. 최신 버전을 재조회 후 재시도하세요: $id")
