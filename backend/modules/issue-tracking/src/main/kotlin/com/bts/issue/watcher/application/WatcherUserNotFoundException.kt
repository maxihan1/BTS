// 워처로 추가하려는 대상 사용자가 시스템에 존재하지 않을 때 발생하는 예외 (FR-WT-01).

package com.bts.issue.watcher.application

import java.util.UUID

/**
 * 워처로 추가하려는 대상 사용자가 시스템에 존재하지 않을 때.
 *
 * 타인을 워처로 추가할 때만 발생한다. 본인(actor)은 인증 단계에서 이미 실재가 확인되므로 검증하지 않는다.
 * HTTP 422 매핑은 IssueExceptionHandler 에서 처리한다.
 *
 * 보안 — 응답 메시지에 내부 식별자(userId)를 노출하지 않는다.
 * userId 는 로그 추적용으로만 보관한다.
 *
 * @param userId 존재하지 않는 사용자 UUID (로그 전용, HTTP 응답 비노출).
 */
class WatcherUserNotFoundException(val userId: UUID) :
    RuntimeException("watcher target user not found")
