// 워크플로우 스킴 권한 평가 outbound port — FR-PM-04 시 identity-access adapter 로 교체

package com.bts.workflow.scheme.port.outbound

import com.bts.workflow.port.outbound.ActorId

/**
 * 워크플로우 스킴 권한 평가 outbound port.
 *
 * project-workflow BC 가 정의하고, identity-access BC 가 adapter 를 제공한다 (FR-PM-04 후속).
 * 본 PR 에서는 [com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver]
 * stub 만 활용한다.
 *
 * ## 호출 위치
 * 스킴 생성·수정·삭제·배정 service 메서드 진입 직후.
 * `@PreAuthorize` SpEL 표현식 대신 명시적 메서드 호출로 권한을 검증한다.
 *
 * ```kotlin
 * @Transactional
 * fun assignSchemeToProject(actor: ActorId, projectKey: String, schemeKey: WorkflowSchemeKey) {
 *     permissionResolver.requirePermission(
 *         actor,
 *         WorkflowSchemePermission.ASSIGN_SCHEME,
 *         WorkflowSchemeScope.Project(projectKey),
 *     )
 *     // ...
 * }
 * ```
 *
 * ## 설계 결정 — hasPermission vs requirePermission
 * [com.bts.workflow.port.outbound.PermissionResolver] 는 Boolean 반환 방식을 사용하지만,
 * 스킴 권한은 항상 예외를 던지는 Guard 패턴이 서비스 레이어 코드를 단순하게 만든다.
 * 호출자가 `if (!resolver.hasPermission(...)) throw ...` 를 반복할 필요가 없다.
 *
 * ## FR-PM-04 도입 시 교체 흐름
 * 1. identity-access BC 가 `IdentityAccessWorkflowSchemePermissionResolver`
 *    (`@Component @Profile("prod")`) 를 구현.
 * 2. [com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver]
 *    는 dev/staging 에서만 활성.
 * 3. 이 interface 를 사용하는 service 코드는 **변경 없음** — adapter 추가만으로 교체 완료.
 *
 * ## ADR 참조
 * `docs/adr/project-scheme-mapping-jira-align` — 스킴 권한을 독립 포트로 분리한 이유.
 *
 * @see com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver
 */
interface WorkflowSchemePermissionResolver {
    /**
     * 주어진 행위자([actor])가 특정 범위([scope]) 내에서 요청 권한([permission])을 보유하는지 검증한다.
     *
     * 권한이 없으면 예외를 던진다 (Guard 패턴).
     * 예외 타입은 구현체가 결정하며, 정식 adapter 는 WORKFLOW_ prefix 에러 코드와 함께 던진다.
     *
     * @param actor 권한 평가 대상 행위자. blank 는 [ActorId] 생성 시점에 이미 거부된다.
     * @param permission 검증할 스킴 권한. [WorkflowSchemePermission] 참조.
     * @param scope 권한 적용 범위. [WorkflowSchemeScope.Global] 또는 [WorkflowSchemeScope.Project] 중 하나.
     * @throws RuntimeException (정식 구현체) 권한이 없을 때. stub 구현체는 예외를 던지지 않는다.
     */
    fun requirePermission(
        actor: ActorId,
        permission: WorkflowSchemePermission,
        scope: WorkflowSchemeScope,
    )
}
