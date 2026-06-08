// identity-access non-prod CustomFieldPermissionResolver fallback — dev/test/staging 항상 허용, @Profile("!prod")로 운영 차단.

package com.atlas.bts.identity.permission

import com.bts.shared.permission.CustomFieldPermission
import com.bts.shared.permission.CustomFieldPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * identity-access BC non-prod [CustomFieldPermissionResolver] fallback (FR-IS-10 #98 부팅 회귀 보강).
 *
 * ## 존재 이유 (BC 격리)
 * identity-access 의 `MyProjectPermissionController` 가 shared-kernel 포트
 * [CustomFieldPermissionResolver] 를 주입받는다(FR-IS-10 #98). 운영(prod)에서는
 * prod adapter [IdentityAccessCustomFieldPermissionResolver] (`@Profile("prod")`) 가 그 포트를
 * 채우지만, non-prod 에서는 그 포트를 채우는 Bean 이 하나도 없어 identity-access 컨텍스트
 * ([com.atlas.bts.identity] component-scan)가 `No qualifying bean` 으로 부팅 실패한다.
 * #98 이 prod adapter 만 추가하고 non-prod fallback 을 누락해 모든 통합테스트가 깨졌다
 * (메모리 profile-scoped-bean-boot-failure). [DevAllowComponentPermissionResolver] 와 동형으로
 * identity-access 자체 non-prod fallback 을 둔다.
 *
 * ## 보안 모델 ([DevAllowComponentPermissionResolver] 와 동일)
 * [hasPermission] 은 항상 `true` 를 반환한다. `@Profile("!prod")` 로 운영 profile 에서
 * Bean 등록이 차단되므로 운영 환경에서는 이 클래스가 절대 활성화되지 않는다. prod profile 에서는
 * prod adapter 만 활성화되어 멤버 게이트 + role_permissions 매트릭스로 실제 판정한다.
 * 두 Bean 은 profile 로 상호 배타적이다.
 *
 * @see CustomFieldPermissionResolver
 * @see DevAllowComponentPermissionResolver
 * @see IdentityAccessCustomFieldPermissionResolver
 */
@Component
@Profile("!prod")
class DevAllowCustomFieldPermissionResolver : CustomFieldPermissionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 항상 `true` 를 반환한다.
     *
     * 개발/테스트/스테이징 환경에서 권한 검사 없이 동작을 검증할 수 있도록 모든 요청을 허용한다.
     * 운영 환경에서는 이 메서드가 절대 호출되지 않는다. WARN 로그에 [actorId] (UUID — PII 아님),
     * [permission] (enum — PII 아님), [projectId] (UUID — PII 아님) 를 기록한다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID.
     * @param permission 검증 요청 권한. [CustomFieldPermission] enum 값.
     * @param projectId 커스텀 필드가 속한 프로젝트의 UUID.
     * @return 항상 `true`.
     */
    override fun hasPermission(
        actorId: UUID,
        permission: CustomFieldPermission,
        projectId: UUID,
    ): Boolean {
        log.warn(
            "DevAllowCustomFieldPermissionResolver: granting {} on project {} to actor {} — non-prod fallback (운영 미활성)",
            permission,
            projectId,
            actorId,
        )
        return true
    }
}
