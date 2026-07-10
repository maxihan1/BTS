// prod 권한 어댑터 — workflow PermissionResolver 를 shared-kernel 이슈/시스템 권한 포트에 위임(fail-closed)

package com.bts.workflow.adapter

import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.SystemPermissionResolver
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.port.outbound.PermissionResolver
import com.bts.workflow.port.outbound.Scope
import com.bts.workflow.port.outbound.toUuid
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 운영(prod) 전용 [PermissionResolver] 어댑터.
 *
 * project-workflow BC 의 권한 판정을 shared-kernel 의 공용 권한 포트에 위임한다. 실제 권한 판정
 * 구현체는 identity-access BC 가 `@Profile("prod")` 로 제공하며(조립 앱에 모두 존재), BC 격리
 * 원칙상 이 어댑터에서 identity-access 를 직접 import 하지 않는다.
 *
 * 이 형태는 `com.bts.workflow.scheme.web.*Controller` 가 shared-kernel
 * [com.bts.shared.permission.WorkflowSchemePermissionResolver] 에 위임하는 선례와 동형이다.
 *
 * ## profile 배타
 * 개발/테스트/스테이징에서는 [AlwaysAllowPermissionResolver](`@Profile("!prod")`) 가 활성화되고,
 * 운영에서는 이 어댑터(`@Profile("prod")`)가 활성화된다. 두 구현은 상호 배타적이다.
 *
 * ## fail-closed 매핑
 * workflow 는 권한을 String 으로 표현하지만 identity 권한 카탈로그는 [IssuePermission] enum 이다.
 * [toIssuePermissionOrNull] 로 알려진 7종만 매핑하고, 카탈로그에 없는 문자열은 위임 없이 무조건
 * 거부(false)한다. 권한 판정 경로의 불명은 항상 "거부"로 수렴한다.
 *
 * ## 정책 상속
 * - TRANSITION 은 현재 identity 에서 전이 매트릭스를 별도 위임하지 않고 프로젝트 멤버면 통과한다.
 *   이 어댑터는 위임이므로 현행 정책을 그대로 상속하며, 향후 전이 매트릭스 도입 시 자동 반영된다.
 * - [Scope.Global] 은 현재 `PermissionValidator`(ValidatorScope 는 ISSUE/PROJECT 뿐)가 생성하지
 *   않으나, sealed 완전성을 위해 [SystemPermissionResolver.isSystemAdmin] 으로 처리한다.
 *
 * ## 이름 확정
 * [PermissionResolver] 포트 KDoc 이 예고한 이름 "IdentityAccessPermissionResolver" 는 어댑터가
 * identity-access 에 위치한다는 가정이었다. BC 격리(ArchUnit 이 identity 의 `com.bts.workflow..`
 * import 금지)상 어댑터를 project-workflow 에 두고 shared 포트에 위임하는 형태로 확정되었다.
 *
 * 참조. FR-WF-03, docs/sdd/ §12-permissions, identity-access.md
 */
@Component
@Profile("prod")
class DelegatingPermissionResolver(
    private val issuePermissionResolver: IssuePermissionResolver,
    private val systemPermissionResolver: SystemPermissionResolver,
) : PermissionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun hasPermission(
        actorId: ActorId,
        permission: String,
        scope: Scope,
    ): Boolean {
        val uuid = actorId.toUuid()
        return when (scope) {
            is Scope.Global -> systemPermissionResolver.isSystemAdmin(uuid)
            is Scope.Project -> checkIssuePermission(uuid, permission, IssueScope.Project(scope.key))
            is Scope.Issue -> checkIssuePermission(uuid, permission, IssueScope.Issue(scope.key))
        }
    }

    /**
     * workflow 권한 문자열을 [IssuePermission] 으로 매핑해 [IssuePermissionResolver] 에 위임한다.
     *
     * 매핑 실패(미등록 문자열)는 위임하지 않고 false 를 반환한다(fail-closed).
     */
    private fun checkIssuePermission(
        uuid: UUID,
        permission: String,
        issueScope: IssueScope,
    ): Boolean {
        val mapped = permission.toIssuePermissionOrNull()
        if (mapped == null) {
            log.warn(
                "unknown workflow permission '{}' at scope {} → deny (fail-closed)",
                permission,
                issueScope,
            )
            return false
        }
        return issuePermissionResolver.hasPermission(uuid, mapped, issueScope)
    }
}

/**
 * workflow 권한 문자열을 identity 권한 카탈로그([IssuePermission])로 매핑한다.
 *
 * 알려진 7종만 명시적으로 매핑하고 그 외 모든 문자열은 null 을 반환한다(호출부가 거부로 처리).
 * String → enum 변환이므로 `else` 분기가 불가피하나, 알려진 코드는 전부 명시해 카탈로그 drift 를
 * 최소화한다.
 */
private fun String.toIssuePermissionOrNull(): IssuePermission? =
    when (this) {
        "BROWSE_PROJECT" -> IssuePermission.BROWSE
        "VIEW_ISSUE" -> IssuePermission.VIEW
        "CREATE_ISSUE" -> IssuePermission.CREATE
        "EDIT_ISSUE" -> IssuePermission.UPDATE
        "TRANSITION_ISSUE" -> IssuePermission.TRANSITION
        "DELETE_ISSUE" -> IssuePermission.SOFT_DELETE
        "SET_ISSUE_SECURITY" -> IssuePermission.SET_SECURITY
        else -> null // fail-closed: 미등록 권한 문자열은 거부
    }
