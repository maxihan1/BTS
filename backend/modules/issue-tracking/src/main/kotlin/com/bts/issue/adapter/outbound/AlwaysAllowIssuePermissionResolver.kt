// AlwaysAllow stub — FR-PM-02 stub 교체 전까지 dev/staging 활성. @Profile("!prod") 로 운영 차단.

package com.bts.issue.adapter.outbound

import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * issue-tracking BC stub permission resolver.
 *
 * ## 보안 모델
 * 이 구현체는 [hasPermission] 이 항상 `true` 를 반환한다.
 * `@Profile("!prod")` 로 운영(prod) profile 에서 Bean 등록이 차단되므로,
 * 운영 환경에서는 이 클래스가 절대 활성화되지 않는다.
 *
 * prod profile 에서 [IssuePermissionResolver] Bean 이 미해소 상태로 남으면
 * Spring 이 `BeanCreationException` 을 던져 부팅 자체를 차단한다.
 * 즉, stub 없이 운영 배포 시 반드시 실제 adapter 가 존재해야 부팅이 성공한다.
 *
 * ## 대체 시점
 * FR-PM-02 에서 identity-access BC 가 `IdentityAccessIssuePermissionResolver`
 * (`@Component @Profile("prod")`) 를 구현하면 두 resolver 는 profile 로
 * 상호 배타적(mutually exclusive)으로 동작한다.
 * - `AlwaysAllowIssuePermissionResolver` — `@Profile("!prod")` (개발/테스트/스테이징)
 * - `IdentityAccessIssuePermissionResolver` — `@Profile("prod")` (운영)
 *
 * ## ArchUnit 강제
 * [com.bts.issue.application.IssueApplicationService] (Service 계층) 는
 * [IssuePermissionResolver] interface 만 의존한다. 이 구현체를 직접 import 하면 빌드 실패.
 *
 * @see IssuePermissionResolver
 * @see docs/adr/2026-05-22-issue-permission-resolver-port.md
 */
@Component
@Profile("!prod")
class AlwaysAllowIssuePermissionResolver : IssuePermissionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 항상 `true` 를 반환한다.
     *
     * 개발/테스트 환경에서 권한 검사 없이 이슈 CRUD 를 테스트할 수 있도록
     * 모든 요청을 허용한다. 운영 환경에서는 이 메서드가 절대 호출되지 않는다.
     *
     * WARN 로그에 [actorId] (UUID — PII 아님), [permission] (enum — PII 아님),
     * [scope] (sealed class — PII 아님) 를 기록해 개발/스테이징 환경에서 권한 우회
     * 빈도를 모니터링할 수 있게 한다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID.
     * @param permission 검증 요청 권한. [IssuePermission] enum 값.
     * @param scope 권한 적용 범위. [IssueScope] sealed 계층.
     * @return 항상 `true`.
     */
    override fun hasPermission(
        actorId: UUID,
        permission: IssuePermission,
        scope: IssueScope,
    ): Boolean {
        log.warn(
            "AlwaysAllowIssuePermissionResolver: granting {} on {} to actor {} — stub (FR-AU-12 미도입)",
            permission,
            scope,
            actorId,
        )
        return true
    }
}
