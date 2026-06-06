// issue-tracking BC 도메인 예외 계층 — sealed 베이스 + 7 서브클래스

package com.bts.issue.domain

import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope

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
 * 프로젝트에 대해 적용 가능한 워크플로우가 설정되어 있지 않을 때.
 *
 * project-workflow BC 의 스킴(WorkflowScheme) 에서 기본 매핑이 존재하지 않는 경우
 * issue-tracking BC 경계 내부에서 발생하는 도메인 예외다.
 * HTTP 422 매핑은 IssueExceptionHandler 에서 처리한다 (Task 6 scope).
 *
 * @param projectKey 워크플로우가 미설정된 프로젝트 키
 * @param issueTypeKey 이슈 타입 키. 없으면 null (기본 매핑 탐색 실패 의미)
 */
class IssueWorkflowNotConfiguredException(projectKey: String, issueTypeKey: String?) :
    IssueDomainException("Workflow not configured for project=$projectKey, issueType=${issueTypeKey ?: "<default>"}")

/**
 * assignee 로 지정한 사용자가 시스템에 존재하지 않을 때.
 *
 * HTTP 422 매핑은 IssueExceptionHandler 에서 처리한다.
 *
 * @param assigneeId 존재하지 않는 assignee 의 사용자 ID
 */
class AssigneeNotFoundException(assigneeId: java.util.UUID) :
    IssueDomainException("assignee not found: $assigneeId")

/**
 * 컴포넌트 id 에 해당하는 활성 컴포넌트가 프로젝트 내에 존재하지 않을 때.
 *
 * 소프트 삭제된 컴포넌트나 다른 프로젝트 소속 컴포넌트도 이 예외를 발생시킨다.
 * HTTP 422 매핑은 IssueExceptionHandler 에서 처리한다.
 *
 * @param componentId 존재하지 않는 컴포넌트의 UUID
 */
class IssueComponentNotFoundException(componentId: java.util.UUID) :
    IssueDomainException("component not found: $componentId")

/**
 * 이슈에 지정하려는 보안 등급이 프로젝트 적용 스킴 소속이 아닐 때 (FR-PM-06).
 *
 * 이슈 생성/수정 시 `security_level_id` 가 해당 프로젝트의 적용 스킴에 속하지 않으면 발생한다.
 * HTTP 422 매핑은 IssueExceptionHandler 에서 처리한다.
 *
 * 보안 — 응답 detail 에는 내부 식별자(levelId)를 노출하지 않는다(guard-exception 누출 방지).
 * levelId 는 로그 추적용으로만 보관한다.
 *
 * @param levelId 스킴 미소속 보안 등급 UUID (로그 전용, HTTP 응답 비노출).
 */
class IssueSecurityLevelNotInSchemeException(val levelId: java.util.UUID) :
    IssueDomainException("security level not in project scheme: $levelId")

/**
 * 워크플로우 전이가 허용되지 않을 때.
 *
 * project-workflow BC 의 [TransitionResult] 실패 케이스를 issue-tracking BC 경계 내부에서
 * 변환하는 예외다. 외부 BC 예외/결과가 issue-tracking 어댑터 계층까지 누출되지 않도록 막는다.
 *
 * @param issueKey 전이를 시도한 이슈 키
 * @param fromStatus 전이 전 상태 키
 * @param toStatus 전이 후 상태 키
 * @param reason 전이가 거부된 사유. 사용자에게 노출 가능한 메시지. 없으면 null.
 */
class IssueTransitionNotAllowedException(
    val issueKey: IssueKey,
    val fromStatus: String,
    val toStatus: String,
    val reason: String? = null,
) : RuntimeException(
        buildString {
            append("Transition not allowed: ${issueKey.value} ($fromStatus → $toStatus)")
            if (reason != null) append(" — $reason")
        },
    )
