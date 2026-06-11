// NonProd SensitiveProjectResolver fallback — @Profile("!prod") 단독 부팅용 안전 기본(항상 false) (FR-MF-04 Task 4)

package com.atlas.bts.identity.mfa

import com.bts.shared.permission.SensitiveProjectResolver
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * identity-access BC 단독 부팅용 [SensitiveProjectResolver] non-prod fallback (FR-MF-04 Task 4).
 *
 * '민감 프로젝트(require_2fa=true)' 데이터는 issue-tracking BC 소유다. prod 에서는
 * issue-tracking 의 실제 adapter([com.bts.issue.project.adapter.IssueTrackingSensitiveProjectResolver])를
 * assembled 단일 부팅으로 주입받는다. 그러나 identity-access 단독 테스트/dev 부팅에는
 * 그 빈이 존재하지 않으므로, `@Profile("!prod")` fallback 으로 부팅 가용성을 확보한다.
 *
 * ## 안전 기본 = false (민감 아님)
 * [anyRequiresMfa] 는 항상 `false` 를 반환한다. 즉 non-prod 단독 부팅에서는 '민감 프로젝트 멤버'
 * 경로로 인한 MFA 강제가 발생하지 않는다(관리자 강제는 [com.atlas.bts.identity.mfa.MfaEnforcementPolicy]
 * 의 SystemPermissionResolver 경로로 별도 동작). 강제를 '덜 켜는' 방향이라 non-prod 가용성 우선이며,
 * 운영 보안에는 영향이 없다.
 *
 * ## fail-open 금지 — prod 누락은 부팅 차단으로 수렴
 * 이 빈은 `@Profile("!prod")` 라 prod 에서는 등록되지 않는다. prod 에 issue-tracking 실 adapter 가
 * 조립되지 않으면 [SensitiveProjectResolver] 빈이 미해소 상태가 되어 Spring 이 부팅 자체를
 * 차단한다(BeanCreationException). 즉 'prod 에서 민감 여부 불명'을 silent 하게 false 로 떨어뜨리지
 * 않고, 부팅 단계에서 loud 하게 실패시킨다
 * (learnings: crossbc-resolver-nullable-fail-open / profile-scoped-bean-boot-failure).
 *
 * ## 선례 — NonProdAllowSystemAdminResolver
 * issue-tracking 의 [com.bts.issue.project.adapter.NonProdAllowSystemAdminResolver](FR-MF-04 Task 3)
 * 와 동형 패턴이다. 그쪽은 허용(true)이 안전 기본이지만, 본 resolver 는 '민감 아님(false)'이
 * 안전 기본이라는 점만 다르다.
 *
 * @see SensitiveProjectResolver
 */
@Component
@Profile("!prod")
class NonProdSensitiveProjectResolver : SensitiveProjectResolver {
    /**
     * non-prod 단독 부팅에서는 항상 `false`(민감 프로젝트 없음)를 반환한다.
     *
     * @param projectIds 판정 대상 프로젝트 UUID 집합 (사용하지 않음).
     * @return 항상 `false`.
     */
    override fun anyRequiresMfa(projectIds: Set<UUID>): Boolean = false
}
