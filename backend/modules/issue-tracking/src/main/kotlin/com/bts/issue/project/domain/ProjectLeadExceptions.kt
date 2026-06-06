// 프로젝트 리드 BC 도메인 예외 — sealed 베이스 + 2 서브클래스 (ComponentExceptions 동형)

package com.bts.issue.project.domain

import java.util.UUID

/**
 * 프로젝트 리드 BC 에서 발생하는 모든 도메인 예외의 베이스.
 *
 * sealed 로 선언하여 when 식에서 컴파일러가 완전성(exhaustiveness)을 보장한다.
 * RuntimeException 을 상속하므로 Spring @Transactional 롤백 트리거 대상이다.
 *
 * [com.bts.issue.component.domain.ComponentDomainException] 과 동형 패턴.
 */
sealed class ProjectLeadDomainException(message: String) : RuntimeException(message)

/**
 * 리드로 지정한 사용자가 시스템에 존재하지 않을 때.
 *
 * HTTP 422 매핑은 예외 핸들러에서 처리한다.
 * [com.bts.issue.component.domain.ComponentLeadNotFoundException] 과 동형.
 *
 * @param leadUserId 존재하지 않는 리드 사용자 UUID.
 */
class ProjectLeadNotFoundException(leadUserId: UUID) :
    ProjectLeadDomainException("Lead user not found: $leadUserId")

/**
 * 지정한 projectIdOrKey 에 해당하는 활성 프로젝트가 존재하지 않을 때.
 *
 * HTTP 404 매핑은 예외 핸들러에서 처리한다.
 * [com.bts.issue.component.domain.ComponentProjectNotFoundException] 과 동형.
 *
 * @param projectIdOrKey 조회 대상 프로젝트 식별자 (UUID 또는 projectKey).
 */
class ProjectLeadProjectNotFoundException(projectIdOrKey: String) :
    ProjectLeadDomainException("Project not found: $projectIdOrKey")

/**
 * 행위자가 프로젝트 리드 변경에 필요한 권한을 보유하지 않을 때.
 *
 * HTTP 403 매핑은 예외 핸들러에서 처리한다.
 * [com.bts.issue.component.domain.ComponentAccessDeniedException] 과 동형 — 프로젝트 리드는
 * 컴포넌트 기본담당자 폴백이므로 동일한 컴포넌트 UPDATE(MANAGE_COMPONENTS) 권한으로 게이트한다.
 *
 * **메시지는 내부 식별자(actor/projectId/권한코드)를 포함**하므로 로그에만 사용하고,
 * HTTP 응답 detail 에는 일반 메시지를 쓴다 (메모리: guard-exception-message-http-leak).
 *
 * @param actorId 권한 검사 대상 행위자 UUID.
 * @param permission 요청한 컴포넌트 권한.
 * @param projectId 리드를 변경하려는 프로젝트 UUID.
 */
class ProjectLeadAccessDeniedException(
    actorId: UUID,
    permission: com.bts.shared.permission.ComponentPermission,
    projectId: UUID,
) : ProjectLeadDomainException(
        "Access denied: actor=$actorId, permission=${permission.name}, projectId=$projectId",
    )
