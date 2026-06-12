// require_2fa 토글 BC 도메인 예외 (FR-MF-04 Task 3)

package com.bts.issue.project.domain

import java.util.UUID

/**
 * require_2fa 토글 BC 에서 발생하는 모든 도메인 예외의 베이스.
 *
 * [ProjectLeadDomainException] 과 동형 패턴.
 * RuntimeException 을 상속하므로 Spring @Transactional 롤백 트리거 대상이다.
 */
sealed class Require2faDomainException(message: String) : RuntimeException(message)

/**
 * 행위자가 SYSTEM_ADMIN 이 아닐 때.
 *
 * HTTP 403 매핑은 예외 핸들러에서 처리한다.
 * message 에 actorId 를 포함하므로 로그에만 사용하고, HTTP 응답 detail 에는 일반 메시지만 노출한다
 * (memory: guard-exception-message-http-leak).
 *
 * @param actorId 권한 검사 대상 행위자 UUID.
 */
class Require2faForbiddenException(actorId: UUID) :
    Require2faDomainException("require_2fa toggle forbidden: actor=$actorId")

/**
 * 지정한 projectIdOrKey 에 해당하는 활성 프로젝트가 존재하지 않을 때.
 *
 * HTTP 404 매핑은 예외 핸들러에서 처리한다.
 *
 * @param projectIdOrKey 조회 대상 프로젝트 식별자 (UUID 또는 projectKey).
 */
class Require2faProjectNotFoundException(projectIdOrKey: String) :
    Require2faDomainException("Project not found for require_2fa toggle: $projectIdOrKey")
