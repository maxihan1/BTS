// AlwaysAllow stub — FR-PM-03 prod 구현 전까지 dev/staging 활성. @Profile("!prod") 로 운영 차단.

package com.bts.issue.version.adapter

import com.bts.shared.permission.VersionPermission
import com.bts.shared.permission.VersionPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * issue-tracking BC 버전 권한 stub resolver.
 *
 * ## 보안 모델
 * 이 구현체는 [hasPermission] 이 항상 `true` 를 반환한다.
 * `@Profile("!prod")` 로 운영(prod) profile 에서 Bean 등록이 차단되므로,
 * 운영 환경에서는 이 클래스가 절대 활성화되지 않는다.
 *
 * prod profile 에서 [VersionPermissionResolver] Bean 이 미해소 상태로 남으면
 * Spring 이 `BeanCreationException` 을 던져 부팅 자체를 차단한다.
 * 즉, stub 없이 운영 배포 시 반드시 실제 adapter 가 존재해야 부팅이 성공한다.
 *
 * ## prod 실판정은 FR-PM-03 이연
 * FR-PM-03 에서 prod 프로파일용 버전 권한 adapter 가 도입되면 두 resolver 는
 * profile 로 상호 배타적(mutually exclusive)으로 동작한다.
 * - `AlwaysAllowVersionPermissionResolver` — `@Profile("!prod")` (개발/테스트/스테이징)
 * - prod adapter (FR-PM-03) — `@Profile("prod")` (운영)
 *
 * ## ArchUnit 강제
 * 소비자(VersionApplicationService/VersionController)는 [VersionPermissionResolver]
 * interface 만 의존한다. 이 구현체를 직접 import 하면 빌드 실패.
 *
 * @see VersionPermissionResolver
 */
@Component
@Profile("!prod")
class AlwaysAllowVersionPermissionResolver : VersionPermissionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 항상 `true` 를 반환한다.
     *
     * 개발/테스트 환경에서 권한 검사 없이 버전 CRUD 를 테스트할 수 있도록
     * 모든 요청을 허용한다. 운영 환경에서는 이 메서드가 절대 호출되지 않는다.
     *
     * WARN 로그에 [actorId] (UUID — PII 아님), [permission] (enum — PII 아님),
     * [projectId] (UUID — PII 아님) 를 기록해 개발/스테이징 환경에서 권한 우회
     * 빈도를 모니터링할 수 있게 한다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID.
     * @param permission 검증 요청 권한. [VersionPermission] enum 값.
     * @param projectId 버전이 속한 프로젝트의 UUID.
     * @return 항상 `true`.
     */
    override fun hasPermission(
        actorId: UUID,
        permission: VersionPermission,
        projectId: UUID,
    ): Boolean {
        log.warn(
            "AlwaysAllowVersionPermissionResolver: granting {} on project {} to actor {} — stub (FR-PM-03 미도입)",
            permission,
            projectId,
            actorId,
        )
        return true
    }
}
