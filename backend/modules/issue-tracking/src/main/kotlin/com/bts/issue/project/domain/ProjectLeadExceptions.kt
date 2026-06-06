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
