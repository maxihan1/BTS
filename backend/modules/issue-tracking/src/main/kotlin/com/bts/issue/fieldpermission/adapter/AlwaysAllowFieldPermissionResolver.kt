// AlwaysAllow stub — FR-PM-07 prod 구현(identity-access PR-B) 전까지 dev/staging 활성. @Profile("!prod") 로 운영 차단.

package com.bts.issue.fieldpermission.adapter

import com.bts.shared.permission.FieldPermissionResolver
import com.bts.shared.permission.FieldRef
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * issue-tracking BC 필드 수준 권한 stub resolver.
 *
 * ## 보안 모델
 * 이 구현체는 [visibleFields] 와 [editableFields] 가 [candidates] 를 그대로 반환한다.
 * `@Profile("!prod")` 로 운영(prod) profile 에서 Bean 등록이 차단되므로,
 * 운영 환경에서는 이 클래스가 절대 활성화되지 않는다.
 *
 * prod profile 에서 [FieldPermissionResolver] Bean 이 미해소 상태로 남으면
 * Spring 이 `BeanCreationException` 을 던져 부팅 자체를 차단한다.
 * 즉, stub 없이 운영 배포 시 반드시 실제 adapter 가 존재해야 부팅이 성공한다.
 *
 * ## prod 실판정은 FR-PM-07 PR-B(identity-access)
 * FR-PM-07 PR-B 에서 identity-access BC 에 prod 프로파일용 필드 권한 adapter
 * `IdentityAccessFieldPermissionResolver` 가 도입되면 두 resolver 는
 * profile 로 상호 배타적(mutually exclusive)으로 동작한다.
 * - `AlwaysAllowFieldPermissionResolver` — `@Profile("!prod")` (개발/테스트/스테이징)
 * - `IdentityAccessFieldPermissionResolver` (FR-PM-07 PR-B) — `@Profile("prod")` (운영)
 *
 * ## 실차단 검증 위임
 * 실제 필드 수준 차단이 올바르게 동작하는지 검증하는 통합테스트는
 * identity-access prod 통합테스트(`FR-PM-07 PR-B`)가 담당한다.
 * 이 stub 은 issue-tracking 개발/테스트 환경의 부팅 보장만 책임진다.
 *
 * ## ArchUnit 강제
 * 소비자(Service/Controller 계층)는 [FieldPermissionResolver] interface 만 의존한다.
 * 이 구현체를 직접 import 하면 빌드 실패.
 *
 * @see FieldPermissionResolver
 */
@Component
@Profile("!prod")
class AlwaysAllowFieldPermissionResolver : FieldPermissionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [candidates] 를 그대로 반환한다.
     *
     * 개발/테스트 환경에서 필드 권한 검사 없이 이슈를 테스트할 수 있도록
     * 모든 필드의 열람을 허용한다. 운영 환경에서는 이 메서드가 절대 호출되지 않는다.
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
            "AlwaysAllowFieldPermissionResolver: granting all {} field(s) visibility on project {} to actor {} — stub (FR-PM-07 PR-B 미도입)",
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
     * 모든 필드의 편집을 허용한다. 운영 환경에서는 이 메서드가 절대 호출되지 않는다.
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
            "AlwaysAllowFieldPermissionResolver: granting all {} field(s) editability on project {} to actor {} — stub (FR-PM-07 PR-B 미도입)",
            candidates.size,
            projectId,
            actorId,
        )
        return candidates
    }
}
