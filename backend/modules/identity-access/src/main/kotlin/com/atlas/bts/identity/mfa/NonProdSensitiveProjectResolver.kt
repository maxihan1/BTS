// SensitiveProjectResolver fallback — 실 adapter 부재 시 등록되는 프로파일 무관 안전 기본(항상 false) (FR-MF-04)

package com.atlas.bts.identity.mfa

import com.bts.shared.permission.SensitiveProjectResolver
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * [SensitiveProjectResolver] 의 **프로파일 무관 fallback** (FR-MF-04).
 *
 * 클래스명에 'NonProd' 가 남아 있으나, 이 빈은 더 이상 프로파일로 갈리지 않는다.
 * [SensitiveProjectResolverFallbackConfig] 가 `@ConditionalOnMissingBean` 으로 **실 adapter 가
 * 부재할 때만** 이 클래스를 빈으로 등록한다(파일명/클래스명은 범위 축소를 위해 유지, 동작 정정은 본
 * KDoc 으로 명시).
 *
 * ## 등록 조건 — 실 adapter 우선(assembled-first)
 * '민감 프로젝트(require_2fa=true)' 데이터는 issue-tracking BC 소유다. assembled 부팅에서는
 * issue-tracking 의 실 adapter([com.bts.issue.project.adapter.IssueTrackingSensitiveProjectResolver])
 * 가 [SensitiveProjectResolver] 빈으로 존재하므로 이 fallback 은 **등록되지 않는다** — 운영의 실
 * 강제 평가는 그대로 동작한다. 반대로 identity-access 단독 부팅(prod/비prod 테스트·dev,
 * issue-tracking 없음)에서는 실 빈이 부재하므로 이 fallback 이 등록되어 부팅 가용성을 확보한다.
 *
 * ## 안전 기본 = false (민감 아님)
 * [anyRequiresMfa] 는 항상 `false` 를 반환한다. 즉 fallback 활성 시에는 '민감 프로젝트 멤버' 경로로
 * 인한 MFA 강제가 발생하지 않는다(관리자 강제는 [MfaEnforcementPolicy] 의 SystemPermissionResolver
 * 경로로 별도 동작). 강제를 '덜 켜는' 방향이라 fail-safe 이며, 단독 부팅 가용성을 우선한다.
 *
 * ## loud-fail 폐기 사유 — 배포 모델상 standalone-prod 부재
 * 과거에는 `@Profile("!prod")` 로 prod 단독 부팅 시 빈 미해소 → BeanCreationException 으로
 * loud-fail 시켰다. 그러나 배포 모델(no-cross-bc-deployment-assembly)상 'issue-tracking 없는
 * standalone-prod' 시나리오 자체가 존재하지 않아 loud-fail 로 막을 대상이 없다. 오히려 그 분기가
 * identity-access 단독 `@ActiveProfiles("prod")` 통합테스트의 부팅을 깨뜨리는 회귀를 유발했다.
 * 따라서 loud-fail 을 폐기하고, fallback 등록 시 [SensitiveProjectResolverFallbackConfig] 가 시작
 * WARN 로그로 misassembled 를 관측 가능하게 둔다.
 *
 * ## 선례 — NonProdAllowSystemAdminResolver
 * issue-tracking 의 [com.bts.issue.project.adapter.NonProdAllowSystemAdminResolver](FR-MF-04 Task 3)
 * 와 동형 fallback 패턴이다. 그쪽은 허용(true)이 안전 기본이지만, 본 resolver 는 '민감 아님(false)'이
 * 안전 기본이라는 점만 다르다.
 *
 * @see SensitiveProjectResolver
 * @see SensitiveProjectResolverFallbackConfig
 */
class NonProdSensitiveProjectResolver : SensitiveProjectResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * fallback 활성 시 항상 `false`(민감 프로젝트 없음)를 반환한다.
     *
     * @param projectIds 판정 대상 프로젝트 UUID 집합 (사용하지 않음).
     * @return 항상 `false`.
     */
    override fun anyRequiresMfa(projectIds: Set<UUID>): Boolean {
        log.debug("NonProdSensitiveProjectResolver.anyRequiresMfa → false (fallback, 민감 아님)")
        return false
    }
}
