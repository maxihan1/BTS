// 워크플로우 스킴 권한 평가 outbound port(공용) — prod 판정은 identity-access(FR-PM-04).

package com.bts.shared.permission

import java.util.UUID

/**
 * 워크플로우 스킴 권한 평가 outbound port — 전 BC 공용.
 *
 * project-workflow BC 의 WorkflowSchemeController/ProjectWorkflowSchemeController/
 * WorkflowSchemeApplicationService 가 이 interface 를 통해 권한 판정을 요청한다.
 * 개발/스테이징/테스트 환경에서는 project-workflow 내부의
 * `AlwaysAllowWorkflowSchemePermissionResolver`(`@Profile("!prod")`) stub 이 활성화된다.
 * prod 실판정 adapter 는 identity-access 가 `@Profile("prod")` 로 제공한다(FR-PM-04).
 *
 * ## 계약 타입 배치 (BC 격리)
 * FR-PM-04 D2 — 이 interface 는 원래 project-workflow(`com.bts.workflow.scheme.port.outbound`)에
 * 있었으나, identity-access 가 prod adapter 를 제공하려면 이 BC 를 import 해야 하는데
 * BC 격리 ArchUnit 룰상 불가하다. 따라서 계약을 shared-kernel 로 이동한다
 * (FR-PM-03 ComponentPermissionResolver 동형).
 *
 * ## actorId 타입 — UUID (BC 공통 분모)
 * 각 BC 는 `ActorId`, `UserId` 등 자체 별칭을 사용할 수 있으나, 공용 포트 시그니처는
 * `java.util.UUID` 를 사용해 BC 간 타입 결합을 제거한다. 호출자는 자신의 actor 타입에서
 * UUID 를 추출하여 전달한다(예. project-workflow 는 `UUID.fromString(actor.raw)`).
 *
 * ## 설계 결정 — Guard 패턴(예외) vs Boolean
 * 본 포트는 권한이 없으면 [WorkflowSchemeAccessDeniedException] 을 던지는 Guard 패턴이다.
 * 호출자가 `if (!resolver.hasPermission(...)) throw ...` 를 반복할 필요가 없어 서비스 코드를
 * 단순하게 만든다. 예외 타입을 shared-kernel 에 둔 이유는, prod adapter(identity-access)가 던지고
 * project-workflow 핸들러가 catch 하여 BC 를 가로지르기 때문이다(FR-PM-03 Boolean 방식과의 차이).
 *
 * ## ArchUnit 강제
 * - 소비자(Service/Controller 계층)는 이 interface 만 의존한다. 구체 구현체 직접 import 금지.
 * - `com.atlas.bts.identity.*` 클래스를 project-workflow 에서 직접 import 하면 빌드 실패(BC 격리 룰).
 *
 * @see WorkflowSchemePermission
 * @see WorkflowScope
 * @see WorkflowSchemeAccessDeniedException
 */
interface WorkflowSchemePermissionResolver {
    /**
     * 주어진 행위자([actorId])가 특정 범위([scope]) 내에서 요청 권한([permission])을 보유하는지 검증한다.
     *
     * 권한이 없으면 예외를 던진다 (Guard 패턴). 정식 prod adapter 는
     * [WorkflowSchemeAccessDeniedException]([WorkflowSchemeAccessDeniedException.WORKFLOW_SCHEME_ACCESS_DENIED]
     * 에러 코드)을 던진다. stub 구현체는 예외를 던지지 않는다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID. nil UUID 는 호출 이전 인증 단계에서 이미 거부된다.
     * @param permission 검증할 스킴 권한. [WorkflowSchemePermission] 참조.
     * @param scope 권한 적용 범위. [WorkflowScope.Global] 또는 [WorkflowScope.Project] 중 하나.
     * @throws WorkflowSchemeAccessDeniedException (정식 구현체) 권한이 없을 때.
     */
    fun requirePermission(
        actorId: UUID,
        permission: WorkflowSchemePermission,
        scope: WorkflowScope,
    )
}
