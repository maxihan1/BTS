// 컴포넌트 BC 도메인 예외 계층 — sealed 베이스 + 4 서브클래스

package com.bts.issue.component.domain

import java.util.UUID

/**
 * 컴포넌트 BC 에서 발생하는 모든 도메인 예외의 베이스.
 *
 * sealed 로 선언하여 when 식에서 컴파일러가 완전성(exhaustiveness)을 보장한다.
 * RuntimeException 을 상속하므로 Spring @Transactional 롤백 트리거 대상이다.
 */
sealed class ComponentDomainException(message: String) : RuntimeException(message)

/**
 * 지정한 projectIdOrKey 에 해당하는 활성 프로젝트가 존재하지 않을 때.
 *
 * @param projectIdOrKey 조회 대상 프로젝트 식별자 (UUID 또는 projectKey).
 */
class ComponentProjectNotFoundException(projectIdOrKey: String) :
    ComponentDomainException("Project not found: $projectIdOrKey")

/**
 * 지정한 componentId 에 해당하는 활성 컴포넌트가 존재하지 않을 때.
 *
 * @param componentId 조회 대상 컴포넌트 UUID.
 */
class ComponentNotFoundException(componentId: UUID) :
    ComponentDomainException("Component not found: $componentId")

/**
 * 같은 프로젝트 내에서 동일한 이름의 활성 컴포넌트가 이미 존재할 때.
 *
 * 부분 유니크 인덱스(`ux_components_project_id_name_active`)가 23505(unique_violation)를 던지면
 * ApplicationService 에서 이 예외로 변환한다.
 *
 * @param name 중복이 발생한 컴포넌트 이름.
 */
class DuplicateComponentNameException(name: String) :
    ComponentDomainException("Component name already exists in this project: $name")

/**
 * 행위자가 컴포넌트 작업에 필요한 권한을 보유하지 않을 때.
 *
 * HTTP 403 매핑은 예외 핸들러에서 처리한다.
 *
 * @param actorId 권한 검사 대상 행위자 UUID.
 * @param permission 요청한 권한.
 * @param projectId 컴포넌트가 속한 프로젝트 UUID.
 */
class ComponentAccessDeniedException(
    actorId: java.util.UUID,
    permission: com.bts.shared.permission.ComponentPermission,
    projectId: java.util.UUID,
) : ComponentDomainException(
        "Access denied: actor=$actorId, permission=${permission.name}, projectId=$projectId",
    )

/**
 * 리드로 지정한 사용자가 시스템에 존재하지 않을 때.
 *
 * HTTP 422 매핑은 예외 핸들러에서 처리한다.
 *
 * @param leadUserId 존재하지 않는 리드 사용자 UUID.
 */
class ComponentLeadNotFoundException(leadUserId: UUID) :
    ComponentDomainException("Lead user not found: $leadUserId")
