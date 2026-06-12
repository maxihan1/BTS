// identity-access non-prod TemplatePermissionResolver fallback — dev/test/staging 항상 허용, @Profile("!prod")로 운영 차단.

package com.atlas.bts.identity.permission

import com.bts.shared.permission.TemplatePermission
import com.bts.shared.permission.TemplatePermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * identity-access BC non-prod [TemplatePermissionResolver] fallback (FR-TM-01 부팅 회귀 보강).
 *
 * ## 존재 이유 (BC 격리)
 * identity-access 컨텍스트([com.atlas.bts.identity] component-scan)는 운영(prod)에서
 * prod adapter [IdentityAccessTemplatePermissionResolver] (`@Profile("prod")`)로
 * 포트 [TemplatePermissionResolver]를 채운다. non-prod 에서는 그 포트를 채우는 Bean이
 * 하나도 없으면 향후 identity-access 가 이 포트를 주입받게 되는 순간 `No qualifying bean`으로
 * 부팅이 깨진다(메모리 profile-scoped-bean-boot-failure). prod adapter만 추가하고 non-prod
 * fallback 을 누락하면 모든 통합테스트가 깨진 #98 회귀를 답습하므로,
 * [DevAllowCustomFieldPermissionResolver] 와 동형으로 identity-access 자체 non-prod
 * fallback 을 둔다.
 *
 * ## 보안 모델 ([DevAllowCustomFieldPermissionResolver] 와 동일)
 * [hasPermission] 은 항상 `true` 를 반환한다. `@Profile("!prod")` 로 운영 profile 에서
 * Bean 등록이 차단되므로 운영 환경에서는 이 클래스가 절대 활성화되지 않는다. prod profile 에서는
 * prod adapter 만 활성화되어 멤버 게이트 + role_permissions 매트릭스로 실제 판정한다.
 * 두 Bean 은 profile 로 상호 배타적이다.
 *
 * @see TemplatePermissionResolver
 * @see DevAllowCustomFieldPermissionResolver
 * @see IdentityAccessTemplatePermissionResolver
 */
@Component
@Profile("!prod")
class DevAllowTemplatePermissionResolver : TemplatePermissionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 항상 `true` 를 반환한다.
     *
     * 개발/테스트/스테이징 환경에서 권한 검사 없이 동작을 검증할 수 있도록 모든 요청을 허용한다.
     * 운영 환경에서는 이 메서드가 절대 호출되지 않는다. WARN 로그에 [actorId] (UUID — PII 아님),
     * [permission] (enum — PII 아님), [projectId] (UUID — PII 아님) 를 기록한다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID.
     * @param permission 검증 요청 권한. [TemplatePermission] enum 값.
     * @param projectId 이슈 템플릿이 속한 프로젝트의 UUID.
     * @return 항상 `true`.
     */
    override fun hasPermission(
        actorId: UUID,
        permission: TemplatePermission,
        projectId: UUID,
    ): Boolean {
        log.warn(
            "DevAllowTemplatePermissionResolver: granting {} on project {} to actor {} — non-prod fallback (운영 미활성)",
            permission,
            projectId,
            actorId,
        )
        return true
    }
}
