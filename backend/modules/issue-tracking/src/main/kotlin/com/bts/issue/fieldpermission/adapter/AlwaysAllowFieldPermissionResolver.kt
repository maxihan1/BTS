// AlwaysAllow stub — FR-PM-07 prod 구현(IdentityAccessFieldPermissionResolver) 주입 전 기본값 폴백. @Profile("!prod") 로 비prod 빈 등록.

package com.bts.issue.fieldpermission.adapter

import com.bts.shared.permission.FieldPermissionResolver
import com.bts.shared.permission.FieldRef
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * issue-tracking BC 필드 수준 권한 allow-all 폴백 resolver.
 *
 * ## 보안 모델
 * 이 구현체는 [visibleFields] 와 [editableFields] 가 [candidates] 를 그대로 반환한다.
 * `@Profile("!prod")` 로 비prod(개발/테스트/스테이징) 환경에서만 Bean 으로 등록된다.
 *
 * ## 기본값 폴백 + 빈 주입 시 대체 (securityDirectory 패턴 동형)
 * [IssueApplicationService] 생성자 기본값으로 사용된다.
 * Spring 컨텍스트에서는 profile 에 맞는 실제 Bean 이 주입돼 이 기본값을 대체한다.
 * - `AlwaysAllowFieldPermissionResolver` — `@Profile("!prod")` (개발/테스트/스테이징)
 * - `IdentityAccessFieldPermissionResolver` — `@Profile("prod")` (운영, cross-BC 조립 시 주입)
 *
 * Spring 이 관리하지 않는 단위 테스트 컨텍스트에서는 기본값(이 클래스 인스턴스)이 유지된다.
 * 이 경우 마스킹/편집게이트 코드 경로는 항상 실행되되 모든 필드를 허용한다(null-skip 없음).
 *
 * ## prod 실판정
 * prod 구현 `IdentityAccessFieldPermissionResolver` 는 FR-PM-07 PR-A 에 이미 존재한다.
 * cross-BC 조립 시점에 issue-tracking 에 주입되어 이 폴백을 대체한다.
 *
 * ## 실차단 검증 위임
 * 실제 필드 수준 차단이 올바르게 동작하는지 검증하는 통합테스트는
 * identity-access prod 통합테스트(`FR-PM-07 PR-B`)가 담당한다.
 * 이 폴백은 issue-tracking 개발/테스트 환경의 부팅 보장만 책임진다.
 *
 * @see FieldPermissionResolver
 * @see FieldRef
 * @see com.bts.shared.permission.FieldKind
 */
@Component
@Profile("!prod")
class AlwaysAllowFieldPermissionResolver : FieldPermissionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [candidates] 를 그대로 반환한다.
     *
     * 개발/테스트 환경에서 필드 권한 검사 없이 이슈를 테스트할 수 있도록
     * 모든 필드의 열람을 허용한다. prod 환경에서는 [IssueApplicationService] 에
     * `IdentityAccessFieldPermissionResolver` Bean 이 주입돼 이 메서드가 호출되지 않는다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID.
     * @param projectId 필드가 속한 프로젝트의 UUID.
     * @param candidates 판정 대상 필드 집합.
     * @return [candidates] 전체 (제한 없음).
     */
    override fun visibleFields(
        actorId: UUID,
        projectId: UUID,
        candidates: Set<FieldRef>,
    ): Set<FieldRef> {
        log.warn(
            "AlwaysAllowFieldPermissionResolver: granting all {} field(s) visibility" +
                " on project {} to actor {} — allow-all fallback (non-prod)",
            candidates.size,
            projectId,
            actorId,
        )
        return candidates
    }

    /**
     * [candidates] 를 그대로 반환한다.
     *
     * 개발/테스트 환경에서 필드 권한 검사 없이 이슈를 테스트할 수 있도록
     * 모든 필드의 편집을 허용한다. prod 환경에서는 [IssueApplicationService] 에
     * `IdentityAccessFieldPermissionResolver` Bean 이 주입돼 이 메서드가 호출되지 않는다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID.
     * @param projectId 필드가 속한 프로젝트의 UUID.
     * @param candidates 판정 대상 필드 집합.
     * @return [candidates] 전체 (제한 없음).
     */
    override fun editableFields(
        actorId: UUID,
        projectId: UUID,
        candidates: Set<FieldRef>,
    ): Set<FieldRef> {
        log.warn(
            "AlwaysAllowFieldPermissionResolver: granting all {} field(s) editability" +
                " on project {} to actor {} — allow-all fallback (non-prod)",
            candidates.size,
            projectId,
            actorId,
        )
        return candidates
    }
}
