// 개발/테스트 전용 stub — 항상 true 반환. @Profile("!prod") 로 운영 부팅 차단.
// identity-access PR #8 머지 후 IdentityAccessPermissionResolver 가 대체한다.

package com.bts.workflow.adapter

import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.port.outbound.PermissionResolver
import com.bts.workflow.port.outbound.Scope
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 개발/테스트 전용 [PermissionResolver] stub.
 *
 * ## 보안 모델
 * 이 구현체는 [hasPermission] 이 항상 `true` 를 반환한다.
 * `@Profile("!prod")` 로 운영(prod) profile 에서 Bean 등록이 차단되므로,
 * 운영 환경에서는 이 클래스가 절대 활성화되지 않는다.
 *
 * prod profile 에서 이 Bean 이 활성화되면 모든 권한 검사가 우회되어
 * 심각한 보안 위협이 발생한다. [DEVELOPMENT.md §1 #16 참조]
 *
 * ## 대체 시점
 * identity-access PR #8 머지 후 별도 PR 에서 `IdentityAccessPermissionResolver` 가
 * 실제 권한 판정 로직을 구현하고, 두 resolver 는 profile 로 상호 배타적(mutually exclusive)으로 동작한다.
 * - `AlwaysAllowPermissionResolver` — `@Profile("!prod")` (개발/테스트)
 * - `IdentityAccessPermissionResolver` — `@Profile("prod")` (운영)
 *
 * @see PermissionResolver
 * @see com.bts.workflow.port.outbound.Scope
 */
@Component
@Profile("!prod")
class AlwaysAllowPermissionResolver : PermissionResolver {
    /**
     * 항상 `true` 를 반환한다.
     *
     * 개발/테스트 환경에서 권한 검사 없이 워크플로우 전이를 테스트할 수 있도록
     * 모든 요청을 허용한다. 운영 환경에서는 이 메서드가 절대 호출되지 않는다.
     *
     * @param actorId 권한을 판정할 액터 식별자.
     * @param permission 검사할 권한 문자열.
     * @param scope 권한 평가 범위.
     * @return 항상 `true`.
     */
    override fun hasPermission(
        actorId: ActorId,
        permission: String,
        scope: Scope,
    ): Boolean = true
}
