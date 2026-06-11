// NonProd SystemPermissionResolver stub — @Profile("!prod") fallback 빈 (FR-MF-04 Task 3)

package com.bts.issue.project.adapter

import com.bts.shared.permission.SystemPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * issue-tracking BC SystemPermissionResolver non-prod fallback.
 *
 * identity-access BC 의 prod 어댑터(IdentityAccessSystemPermissionResolver)는
 * issue-tracking 단독 부팅 시 존재하지 않는다. `@Profile("!prod")` 로 운영(prod) profile 에서
 * Bean 등록이 차단되므로, 운영 환경에서는 이 클래스가 절대 활성화되지 않는다.
 *
 * prod profile 에서 [SystemPermissionResolver] Bean 이 미해소 상태로 남으면
 * Spring 이 BeanCreationException 을 던져 부팅 자체를 차단한다.
 * 즉, stub 없이 운영 배포 시 반드시 실제 adapter 가 존재해야 부팅이 성공한다.
 *
 * ## 보안 모델
 * non-prod 단독 부팅(test / dev) 을 위한 fallback 이다.
 * `isSystemAdmin` 이 항상 `true` 를 반환하므로 운영 환경 사용 시 권한 우회가 발생한다.
 * `@Profile("!prod")` 로 운영 차단이 보장된다.
 *
 * ## AlwaysAllowComponentPermissionResolver 동형 패턴
 * [com.bts.issue.component.adapter.AlwaysAllowComponentPermissionResolver] 와 동형.
 *
 * @see SystemPermissionResolver
 */
@Component
@Profile("!prod")
class NonProdAllowSystemAdminResolver : SystemPermissionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 항상 `true` 를 반환한다.
     *
     * non-prod 환경에서 SYSTEM_ADMIN 검사 없이 require_2fa 토글을 테스트할 수 있도록
     * 모든 요청을 허용한다. 운영 환경에서는 이 메서드가 절대 호출되지 않는다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID.
     * @return 항상 `true`.
     */
    override fun isSystemAdmin(actorId: UUID): Boolean {
        log.warn(
            "NonProdAllowSystemAdminResolver: granting SYSTEM_ADMIN to actor {} — stub (FR-MF-04, !prod only)",
            actorId,
        )
        return true
    }
}
