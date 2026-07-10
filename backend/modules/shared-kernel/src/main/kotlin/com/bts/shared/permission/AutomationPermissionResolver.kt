// 자동화 규칙 관리(MANAGE_AUTOMATION) 권한 평가 outbound port(공용) — prod 판정은 identity-access(FR-AT-01 D5).

package com.bts.shared.permission

import java.util.UUID

/**
 * 자동화 규칙 관리 권한 평가 outbound port — 전 BC 공용.
 *
 * automation BC 의 룰 CRUD(AutomationRuleController/AutomationRuleService)가 이 interface 를 통해
 * "행위자가 대상 프로젝트에서 자동화 규칙을 관리(MANAGE_AUTOMATION)할 수 있는가"를 판정 요청한다.
 * prod 실판정 adapter 는 identity-access 가
 * [com.atlas.bts.identity.permission.IdentityAccessAutomationPermissionResolver](`@Profile("prod")`)
 * 로 제공한다(FR-AT-01 D5). non-prod(dev/test/staging)에서는 **소비 모듈(automation)** 이 자신의
 * 컨텍스트에 fail-safe stub 을 제공한다 — [WorkflowSchemePermissionResolver] 가 project-workflow 의
 * `AlwaysAllowWorkflowSchemePermissionResolver` 로 채워지는 것과 동형이다(consumer-owns-stub).
 *
 * ## 계약 타입 배치 (BC 격리)
 * 이 interface 는 shared-kernel 에 두어 소비 BC(automation)가 identity-access 내부를 역방향
 * 의존하지 않게 한다. identity-access 가 prod adapter 를 제공하려면 automation 을 import 해야 하는데
 * BC 격리 ArchUnit 룰상 불가하므로, 계약을 공용 패키지에 둔다([ComponentPermissionResolver]·
 * [WorkflowSchemePermissionResolver] 동형).
 *
 * ## Boolean 반환 — Guard(예외)가 아닌 이유
 * [WorkflowSchemePermissionResolver] 가 예외를 던지는 Guard 패턴인 것과 달리, 본 포트는 Boolean 을
 * 반환한다([ComponentPermissionResolver]·[SystemPermissionResolver] 동형). 소비자(automation
 * 컨트롤러)가 거부 시 **일반 메시지 403** 을 직접 던지므로 내부 사정(존재 여부/정책)이 응답에 새지
 * 않는다(FR-PM-04 Guard 예외 message 누출 회귀 방지). 예외 타입이 BC 를 가로지를 필요가 없다.
 *
 * ## fail-closed (불명은 거부)
 * 계약상 판정 불명(미해석 프로젝트 키·비멤버·매트릭스 미보유)은 모두 `false`(거부)로 수렴해야 한다.
 * 소비자는 이 포트를 non-null 로 주입받아야 하며, nullable 의존성 + `?: return` 같은 fail-open
 * 처리를 금지한다(cross-BC resolver fail-open 회귀 방지).
 *
 * ## actorId 타입 — UUID (BC 공통 분모)
 * 각 BC 는 `ActorId`, `UserId` 등 자체 별칭을 사용할 수 있으나, 공용 포트 시그니처는
 * `java.util.UUID` 를 사용해 BC 간 타입 결합을 제거한다. 호출자는 자신의 actor 타입에서 UUID 를
 * 추출하여 전달한다.
 *
 * ## projectKey 타입 — String (프로젝트 키 소유는 issue-tracking BC)
 * 자동화 룰은 프로젝트 키(`automation_rules.project_key`)로 스코프된다. 소비자(automation)는
 * 키→UUID 해석을 스스로 하지 않고(그 매핑은 identity-access `ProjectDirectory` 소유), 프로젝트 키
 * 문자열을 그대로 전달한다. 미해석 키는 prod adapter 에서 거부로 처리된다(fail-closed).
 *
 * ## ArchUnit 강제
 * - 소비자(Service/Controller 계층)는 이 interface 만 의존한다. 구체 구현체 직접 import 금지.
 * - `com.atlas.bts.identity.*` 클래스를 automation 에서 직접 import 하면 빌드 실패(BC 격리 룰).
 *
 * @see WorkflowSchemePermissionResolver
 * @see ComponentPermissionResolver
 * @see SystemPermissionResolver
 */
interface AutomationPermissionResolver {
    /**
     * 주어진 행위자([actorId])가 대상 프로젝트([projectKey]) 내에서 자동화 규칙 관리
     * (MANAGE_AUTOMATION) 권한을 보유하는지 판정한다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID. nil UUID 는 호출 이전 인증 단계에서 이미 거부된다.
     * @param projectKey 자동화 룰이 속한 프로젝트의 키(예. "ATLAS"). 미해석 키는 거부(false)된다.
     * @return MANAGE_AUTOMATION 을 보유하면 `true`, 그 외(미해석 키·비멤버·매트릭스 미보유)는 `false`.
     */
    fun hasManageAutomation(
        actorId: UUID,
        projectKey: String,
    ): Boolean
}
