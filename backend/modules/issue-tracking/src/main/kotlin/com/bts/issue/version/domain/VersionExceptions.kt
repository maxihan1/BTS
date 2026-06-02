// 버전 BC 도메인 예외 계층 — sealed 베이스 + 4 서브클래스

package com.bts.issue.version.domain

import java.util.UUID

/**
 * 버전 BC 에서 발생하는 모든 도메인 예외의 베이스.
 *
 * sealed 로 선언하여 when 식에서 컴파일러가 완전성(exhaustiveness)을 보장한다.
 * RuntimeException 을 상속하므로 Spring @Transactional 롤백 트리거 대상이다.
 */
sealed class VersionDomainException(message: String) : RuntimeException(message)

/**
 * 지정한 projectIdOrKey 에 해당하는 활성 프로젝트가 존재하지 않을 때.
 *
 * @param projectIdOrKey 조회 대상 프로젝트 식별자 (UUID 또는 projectKey).
 */
class VersionProjectNotFoundException(projectIdOrKey: String) :
    VersionDomainException("Project not found: $projectIdOrKey")

/**
 * 지정한 versionId 에 해당하는 활성 버전이 존재하지 않을 때.
 *
 * @param versionId 조회 대상 버전 UUID.
 */
class VersionNotFoundException(versionId: UUID) :
    VersionDomainException("Version not found: $versionId")

/**
 * 같은 프로젝트 내에서 동일한 이름의 활성 버전이 이미 존재할 때.
 *
 * 부분 유니크 인덱스(`ux_versions_project_id_name_active`)가 23505(unique_violation)를 던지면
 * ApplicationService 에서 이 예외로 변환한다.
 *
 * @param name 중복이 발생한 버전 이름.
 */
class DuplicateVersionNameException(name: String) :
    VersionDomainException("Version name already exists in this project: $name")

/**
 * 행위자가 버전 작업에 필요한 권한을 보유하지 않을 때.
 *
 * HTTP 403 매핑은 예외 핸들러에서 처리한다.
 *
 * @param actorId 권한 검사 대상 행위자 UUID.
 * @param permission 요청한 권한.
 * @param projectId 버전이 속한 프로젝트 UUID.
 */
class VersionAccessDeniedException(
    actorId: UUID,
    permission: com.bts.shared.permission.VersionPermission,
    projectId: UUID,
) : VersionDomainException(
        "Access denied: actor=$actorId, permission=${permission.name}, projectId=$projectId",
    )
