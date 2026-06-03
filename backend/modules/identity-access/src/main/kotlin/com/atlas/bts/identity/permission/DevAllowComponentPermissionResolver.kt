// identity-access non-prod ComponentPermissionResolver fallback — dev/test/staging 항상 허용, @Profile("!prod")로 운영 차단.

package com.atlas.bts.identity.permission

import com.bts.shared.permission.ComponentPermission
import com.bts.shared.permission.ComponentPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * identity-access BC non-prod [ComponentPermissionResolver] fallback.
 *
 * ## 존재 이유 (BC 격리)
 * identity-access 의 `MyProjectPermissionController` (FR-PM-03 task-2 확장)가
 * shared-kernel 포트 [ComponentPermissionResolver] 를 주입받는다. 운영(prod)에서는
 * prod adapter (`@Profile("prod")`) 가 그 포트를 채우지만, non-prod 에서는
 * issue-tracking BC 의 `AlwaysAllowComponentPermissionResolver` (`@Profile("!prod")`)
 * 만 그 포트를 채운다. 그런데 identity-access 컨텍스트
 * ([com.atlas.bts.identity] component-scan)는 issue-tracking 모듈을 스캔하지 않으므로,
 * non-prod identity-access 컨텍스트에는 [ComponentPermissionResolver] Bean 이 하나도 없다.
 * BC 격리(다른 BC 직접 import 금지)상 issue-tracking 의 stub 을 끌어올 수 없어,
 * identity-access 자체 구현으로 동등한 non-prod fallback 을 둔다.
 *
 * ## 보안 모델 (issue-tracking AlwaysAllow 와 동일)
 * [hasPermission] 은 항상 `true` 를 반환한다.
 * `@Profile("!prod")` 로 운영(prod) profile 에서 Bean 등록이 차단되므로,
 * 운영 환경에서는 이 클래스가 절대 활성화되지 않는다.
 *
 * prod profile 에서는 prod adapter (`@Profile("prod")`) 만 활성화되어
 * 멤버 게이트 + role_permissions 매트릭스로 실제 판정한다.
 * 두 Bean 은 profile 로 상호 배타적(mutually exclusive)이다.
 *
 * ## 컨텍스트 분리로 Bean 충돌 없음
 * identity-access 와 issue-tracking 은 각자 독립 `@SpringBootApplication` 이라
 * 컨텍스트가 분리된다. identity-access 컨텍스트엔 이 fallback 만,
 * issue-tracking 컨텍스트엔 자기 `AlwaysAllowComponentPermissionResolver` 만 로드되어 충돌하지 않는다.
 *
 * [DevAllowIssuePermissionResolver] 1:1 — scope sealed 계층 대신 `projectId: UUID` 시그니처만 다르다.
 *
 * @see ComponentPermissionResolver
 * @see DevAllowIssuePermissionResolver
 */
@Component
@Profile("!prod")
class DevAllowComponentPermissionResolver : ComponentPermissionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 항상 `true` 를 반환한다.
     *
     * 개발/테스트/스테이징 환경에서 권한 검사 없이 동작을 검증할 수 있도록
     * 모든 요청을 허용한다. 운영 환경에서는 이 메서드가 절대 호출되지 않는다.
     *
     * WARN 로그에 [actorId] (UUID — PII 아님), [permission] (enum — PII 아님),
     * [projectId] (UUID — PII 아님) 를 기록해 개발/스테이징 환경에서 권한 우회
     * 빈도를 모니터링할 수 있게 한다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID.
     * @param permission 검증 요청 권한. [ComponentPermission] enum 값.
     * @param projectId 컴포넌트가 속한 프로젝트의 UUID.
     * @return 항상 `true`.
     */
    override fun hasPermission(
        actorId: UUID,
        permission: ComponentPermission,
        projectId: UUID,
    ): Boolean {
        log.warn(
            "DevAllowComponentPermissionResolver: granting {} on project {} to actor {} — non-prod fallback (운영 미활성)",
            permission,
            projectId,
            actorId,
        )
        return true
    }
}
