// issue-tracking BC 도메인 예외 계층 — sealed 베이스 + 5 서브클래스

package com.bts.issue.domain

import com.bts.issue.port.outbound.IssuePermission
import com.bts.issue.port.outbound.IssueScope

/**
 * issue-tracking BC 에서 발생하는 모든 도메인 예외의 베이스.
 *
 * sealed 로 선언하여 when 식에서 컴파일러가 완전성(exhaustiveness)을 보장한다.
 * RuntimeException 을 상속하므로 Spring @Transactional 롤백 트리거 대상이다.
 */
sealed class IssueDomainException(message: String) : RuntimeException(message)

/**
 * 지정한 키에 해당하는 이슈를 찾을 수 없을 때.
 *
 * @param key 조회 대상 이슈 키
 */
class IssueNotFoundException(key: IssueKey) :
    IssueDomainException("Issue not found: ${key.value}")

/**
 * 행위자가 해당 권한·범위에 대한 접근 권한이 없을 때.
 *
 * @param actor 권한 검사 대상 행위자
 * @param permission 요청한 권한
 * @param scope 권한 평가 범위
 */
class IssueAccessDeniedException(
    actor: ActorId,
    permission: IssuePermission,
    scope: IssueScope,
) : IssueDomainException(
    "Access denied: actor=${actor.value}, permission=${permission.name}, scope=$scope",
)

/**
 * 낙관적 잠금(optimistic locking) 충돌 — 읽은 버전과 현재 버전이 다를 때.
 *
 * @param key 충돌이 발생한 이슈 키
 * @param currentVersion DB 에 저장된 현재 버전
 */
class IssueVersionConflictException(
    key: IssueKey,
    currentVersion: Long,
) : IssueDomainException(
    "Version conflict: key=${key.value}, currentVersion=$currentVersion",
)

/**
 * 이슈를 생성하려는 프로젝트가 존재하지 않을 때.
 *
 * @param projectKey 조회 대상 프로젝트 키
 */
class IssueProjectNotFoundException(projectKey: String) :
    IssueDomainException("Project not found: $projectKey")

/**
 * 이슈 키 prefix 로 예약어를 사용하려 할 때.
 *
 * ADR 2026-05-22-issue-key-prefix-policy §예약어 차단 참조.
 *
 * @param prefix 사용을 시도한 예약 prefix
 */
class IssueKeyPrefixReservedException(prefix: String) :
    IssueDomainException("Issue key prefix is reserved: $prefix")

/**
 * 워크플로우 전이가 허용되지 않을 때.
 *
 * project-workflow BC 의 WorkflowValidatorFailureException 을 issue-tracking BC 경계 내부에서
 * 감싸는 wrapper 예외다. 외부 BC 예외가 issue-tracking 어댑터 계층까지 누출되지 않도록 막는다.
 *
 * @param issueKey 전이를 시도한 이슈 키
 * @param fromStatus 전이 전 상태 키
 * @param toStatus 전이 후 상태 키
 * @param cause 원인 예외 (project-workflow BC 에서 발생). 없으면 null.
 */
class IssueTransitionNotAllowedException(
    val issueKey: IssueKey,
    val fromStatus: String,
    val toStatus: String,
    cause: Throwable? = null,
) : RuntimeException("Transition not allowed: ${issueKey.value} ($fromStatus → $toStatus)", cause)
