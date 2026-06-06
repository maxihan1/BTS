// AlwaysAllow stub — FR-PM-06 T9 prod 구현 전까지 dev/staging 활성. @Profile("!prod") 로 운영 차단.

package com.bts.issue.adapter.outbound

import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * issue-tracking BC stub security directory.
 *
 * ## 보안 모델
 * 이 구현체는 [levelBelongsToProjectScheme] 이 항상 `true` 를,
 * [accessibleLevels] 가 `unrestricted=true` 인 [IssueSecurityAccess] 를 반환한다.
 * `@Profile("!prod")` 로 운영(prod) profile 에서 Bean 등록이 차단되므로,
 * 운영 환경에서는 이 클래스가 절대 활성화되지 않는다.
 *
 * prod profile 에서 [IssueSecurityDirectory] Bean 이 미해소 상태로 남으면
 * Spring 이 `BeanCreationException` 을 던져 부팅 자체를 차단한다.
 * 즉, stub 없이 운영 배포 시 반드시 실제 adapter 가 존재해야 부팅이 성공한다.
 *
 * ## 대체 시점
 * FR-PM-06 T9 에서 identity-access BC 가 `IdentityAccessIssueSecurityDirectory`
 * (`@Component @Profile("prod")`) 를 구현하면 두 directory 는 profile 로
 * 상호 배타적(mutually exclusive)으로 동작한다.
 * - `AlwaysAllowIssueSecurityDirectory` — `@Profile("!prod")` (개발/테스트/스테이징)
 * - `IdentityAccessIssueSecurityDirectory` — `@Profile("prod")` (운영, T9 구현)
 *
 * @see IssueSecurityDirectory
 * @see IssueSecurityAccess
 */
@Component
@Profile("!prod")
class AlwaysAllowIssueSecurityDirectory : IssueSecurityDirectory {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 항상 `true` 를 반환한다.
     *
     * 개발/테스트 환경에서 보안 등급 검증 없이 이슈 CRUD 를 테스트할 수 있도록
     * 모든 등급을 스킴 소속으로 허용한다. 운영 환경에서는 이 메서드가 절대 호출되지 않는다.
     *
     * @param levelId 검증할 보안 등급 UUID (PII 아님).
     * @param projectKey 대상 프로젝트 키 (PII 아님).
     * @return 항상 `true`.
     */
    override fun levelBelongsToProjectScheme(
        levelId: UUID,
        projectKey: String,
    ): Boolean {
        log.warn(
            "AlwaysAllowIssueSecurityDirectory: levelBelongsToProjectScheme always true " +
                "for level={} project={} — stub (FR-PM-06 T9 미도입)",
            levelId,
            projectKey,
        )
        return true
    }

    /**
     * `unrestricted=true` 인 [IssueSecurityAccess] 를 반환한다.
     *
     * 개발/테스트 환경에서 보안 등급 필터 없이 이슈 목록을 조회할 수 있도록
     * 모든 이슈를 접근 가능으로 허용한다 (unrestricted=true → WHERE 절 미적용).
     * 운영 환경에서는 이 메서드가 절대 호출되지 않는다.
     *
     * @param actorId 접근 가능 등급을 조회할 행위자 UUID (PII 아님).
     * @param projectKey 대상 프로젝트 키 (PII 아님).
     * @return unrestricted=true, 모든 levelIds 집합 비어있는 [IssueSecurityAccess].
     */
    override fun accessibleLevels(
        actorId: UUID,
        projectKey: String,
    ): IssueSecurityAccess {
        log.warn(
            "AlwaysAllowIssueSecurityDirectory: accessibleLevels unrestricted=true " +
                "for actor={} project={} — stub (FR-PM-06 T9 미도입)",
            actorId,
            projectKey,
        )
        return IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )
    }
}
