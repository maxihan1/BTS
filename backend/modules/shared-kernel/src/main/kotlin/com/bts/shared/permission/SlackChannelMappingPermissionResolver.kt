// Slack 채널 매핑 관리 권한 평가 outbound port(공용) — prod 판정은 identity-access(FR-SL-06 Task 8).

package com.bts.shared.permission

import java.util.UUID

/**
 * Slack 채널 매핑 관리 권한 평가 outbound port — 전 BC 공용.
 *
 * slack-integration BC 의 채널 매핑 CRUD(SlackChannelMappingController/SlackChannelMappingService)가
 * 이 interface 를 통해 "행위자가 대상 프로젝트에서 Slack 채널 매핑을 관리할 수 있는가"를 판정 요청한다.
 * prod 실판정 adapter 는 identity-access 가
 * [com.atlas.bts.identity.permission.IdentityAccessSlackChannelMappingPermissionResolver](`@Profile("prod")`)
 * 로 제공한다(FR-SL-06 Task 8, PROJECT_ADMIN 역할 직접 확인). non-prod(dev/test/staging)에서는
 * **소비 모듈(slack-integration)** 이 자신의 컨텍스트에 fail-safe stub 을 제공한다 —
 * [WorkflowSchemePermissionResolver] 가 project-workflow 의 `AlwaysAllowWorkflowSchemePermissionResolver`
 * 로 채워지는 것과 동형이다(consumer-owns-stub).
 *
 * ## 계약 타입 배치 (BC 격리)
 * 이 interface 는 shared-kernel 에 두어 소비 BC(slack-integration)가 identity-access 내부를 역방향
 * 의존하지 않게 한다. identity-access 가 prod adapter 를 제공하려면 slack-integration 을 import 해야 하는데
 * BC 격리 ArchUnit 룰상 불가하므로, 계약을 공용 패키지에 둔다([AutomationPermissionResolver]·
 * [ComponentPermissionResolver]·[WorkflowSchemePermissionResolver] 동형).
 *
 * ## Boolean 반환 — Guard(예외)가 아닌 이유
 * [WorkflowSchemePermissionResolver] 가 예외를 던지는 Guard 패턴인 것과 달리, 본 포트는 Boolean 을
 * 반환한다([AutomationPermissionResolver]·[ComponentPermissionResolver]·[SystemPermissionResolver] 동형).
 * 소비자(slack 컨트롤러)가 거부 시 **일반 메시지 403** 을 직접 던지므로 내부 사정(존재 여부/정책)이
 * 응답에 새지 않는다(FR-PM-04 Guard 예외 message 누출 회귀 방지). 예외 타입이 BC 를 가로지를 필요가 없다.
 *
 * ## fail-closed (불명은 거부)
 * 계약상 판정 불명(미해석 프로젝트 키·비멤버·비관리자)은 모두 `false`(거부)로 수렴해야 한다.
 * 소비자는 이 포트를 non-null 로 주입받아야 하며, nullable 의존성 + `?: return` 같은 fail-open
 * 처리를 금지한다(cross-BC resolver fail-open 회귀 방지). 이 interface 는 **default 구현을 두지 않는다** —
 * prod adapter 든 non-prod stub 이든 Bean 이 부재하면 부팅이 실패해 공유 누출이 원천 차단된다(fail-closed 안전망).
 *
 * ## actorId 타입 — UUID (BC 공통 분모)
 * 각 BC 는 `ActorId`, `UserId` 등 자체 별칭을 사용할 수 있으나, 공용 포트 시그니처는
 * `java.util.UUID` 를 사용해 BC 간 타입 결합을 제거한다. 호출자는 자신의 actor 타입에서 UUID 를
 * 추출하여 전달한다.
 *
 * ## projectKey 타입 — String (프로젝트 키 소유는 issue-tracking BC)
 * 채널 매핑은 프로젝트 키(`slack_channel_project_map.project_key`)로 스코프된다. 소비자(slack-integration)는
 * 키→UUID 해석을 스스로 하지 않고(그 매핑은 identity-access `ProjectDirectory` 소유), 프로젝트 키
 * 문자열을 그대로 전달한다. 미해석 키는 prod adapter 에서 거부로 처리된다(fail-closed).
 *
 * ## ArchUnit 강제
 * - 소비자(Service/Controller 계층)는 이 interface 만 의존한다. 구체 구현체 직접 import 금지.
 * - `com.atlas.bts.identity.*` 클래스를 slack-integration 에서 직접 import 하면 빌드 실패(BC 격리 룰).
 * - 이 interface 는 원시 타입(UUID/String/Boolean)만 사용한다. BC 도메인 타입을 참조하면
 *   [com.bts.shared.architecture.SharedKernelBoundaryArchTest] 가 빌드를 차단한다.
 *
 * @see AutomationPermissionResolver
 * @see WorkflowSchemePermissionResolver
 * @see ComponentPermissionResolver
 * @see SystemPermissionResolver
 */
interface SlackChannelMappingPermissionResolver {
    /**
     * 주어진 행위자([actorId])가 대상 프로젝트([projectKey]) 내에서 Slack 채널 매핑을
     * 관리(생성/조회/수정/삭제)할 수 있는지 판정한다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID. nil UUID 는 호출 이전 인증 단계에서 이미 거부된다.
     * @param projectKey 채널 매핑이 속한 프로젝트의 키(예. "ATLAS"). 미해석 키는 거부(false)된다.
     * @return 채널 매핑 관리 권한(PROJECT_ADMIN)을 보유하면 `true`, 그 외(미해석 키·비멤버·비관리자)는 `false`.
     */
    fun hasManageChannelMapping(
        actorId: UUID,
        projectKey: String,
    ): Boolean
}
