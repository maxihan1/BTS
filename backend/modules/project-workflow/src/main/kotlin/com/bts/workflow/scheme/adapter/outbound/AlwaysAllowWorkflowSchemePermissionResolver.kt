// AlwaysAllow stub — FR-PM-04 까지 임시. @Profile("!prod") 로 운영 차단.

package com.bts.workflow.scheme.adapter.outbound

import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.scheme.port.outbound.WorkflowSchemePermission
import com.bts.workflow.scheme.port.outbound.WorkflowSchemePermissionResolver
import com.bts.workflow.scheme.port.outbound.WorkflowSchemeScope
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 워크플로우 스킴 권한 평가 stub.
 *
 * ## 보안 모델
 * 이 구현체는 [requirePermission] 이 항상 통과(예외 없음)한다.
 * `@Profile("!prod")` 로 운영(prod) profile 에서 Bean 등록이 차단되므로,
 * 운영 환경에서는 이 클래스가 절대 활성화되지 않는다.
 *
 * prod profile 에서 [WorkflowSchemePermissionResolver] Bean 이 미해소 상태로 남으면
 * Spring 이 `BeanCreationException` 을 던져 부팅 자체를 차단한다.
 * 즉, stub 없이 운영 배포 시 반드시 실제 adapter 가 존재해야 부팅이 성공한다.
 *
 * ## 대체 시점
 * FR-PM-04 (Project Management RBAC) 진척 시 identity-access BC 가
 * `IdentityAccessWorkflowSchemePermissionResolver` (`@Component @Profile("prod")`) 를 구현하면
 * 두 resolver 는 profile 로 상호 배타적(mutually exclusive)으로 동작한다.
 * - `AlwaysAllowWorkflowSchemePermissionResolver` — `@Profile("!prod")` (개발/테스트/스테이징)
 * - `IdentityAccessWorkflowSchemePermissionResolver` — `@Profile("prod")` (운영)
 *
 * 이 클래스는 정식 adapter 검증 완료 + 1주 대기 후 제거한다.
 *
 * ## ArchUnit 강제
 * 스킴 service 계층은 [WorkflowSchemePermissionResolver] interface 만 의존한다.
 * 이 구현체를 직접 import 하면 빌드 실패.
 *
 * @see WorkflowSchemePermissionResolver
 * @see docs/adr/project-scheme-mapping-jira-align
 */
@Component
@Profile("!prod")
class AlwaysAllowWorkflowSchemePermissionResolver : WorkflowSchemePermissionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 항상 통과한다 (예외를 던지지 않는다).
     *
     * 개발/테스트 환경에서 권한 검사 없이 스킴 CRUD 와 배정을 테스트할 수 있도록
     * 모든 요청을 허용한다. 운영 환경에서는 이 메서드가 절대 호출되지 않는다.
     *
     * WARN 로그에 [actor] (raw ID 문자열 — PII 아님), [permission] (enum — PII 아님),
     * [scope] (sealed class — PII 아님) 를 기록해 개발/스테이징 환경에서 권한 우회
     * 빈도를 모니터링할 수 있게 한다.
     *
     * @param actor 권한 평가 대상 행위자. raw ID 식별자.
     * @param permission 검증 요청 권한. [WorkflowSchemePermission] enum 값.
     * @param scope 권한 적용 범위. [WorkflowSchemeScope] sealed 계층.
     */
    override fun requirePermission(
        actor: ActorId,
        permission: WorkflowSchemePermission,
        scope: WorkflowSchemeScope,
    ) {
        log.warn(
            "AlwaysAllow stub 사용 중: actor={} permission={} scope={} — 정식 RBAC 미구현. FR-PM-04 후속 PR 필요",
            actor.raw,
            permission,
            scope,
        )
    }
}
