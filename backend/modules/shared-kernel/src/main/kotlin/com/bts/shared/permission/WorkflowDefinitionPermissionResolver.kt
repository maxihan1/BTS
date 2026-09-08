// 워크플로우 정의 권한 평가 outbound port(공용) — prod 판정은 identity-access 의 시스템 관리자 판정.

package com.bts.shared.permission

import java.util.UUID

/**
 * 워크플로우 정의 권한 평가 outbound port — 전 BC 공용.
 *
 * project-workflow BC 의 컨트롤러/애플리케이션 서비스가 이 interface 를 통해 권한 판정을 요청한다.
 * 개발/스테이징에서는 project-workflow 내부의 `AlwaysAllowWorkflowDefinitionPermissionResolver`
 * (`@Profile("!prod")`) stub 이 활성화된다.
 *
 * ## 스코프 인자 (FR-WF-08)
 * 워크플로우 정의는 더 이상 사이트 전역 자원만이 아니다 — `workflows.project_id` 가 붙으면서
 * 「전역 공유 워크플로우」와 「그 프로젝트 전용 워크플로우」로 갈렸다. 그래서 [WorkflowScope] 를
 * 받는다. **스킴 판정기와 같은 타입**이다(D8) — 두 타입을 따로 두면 서로를 검사하지 않는
 * 두 목록이 되어, 한쪽에만 스코프가 늘어도 다른 쪽은 조용히 통과한다.
 *
 * 호출부는 대상의 소유를 스코프로 옮겨 넘긴다. project-workflow 에서는
 * `WorkflowOwnershipScopeResolver` 가 그 변환의 단일 결정 지점이다.
 *
 * ## Guard 패턴 — Boolean 이 아니라 예외
 * 권한이 없으면 [WorkflowDefinitionAccessDeniedException] 을 던진다.
 * [WorkflowSchemePermissionResolver] 와 동형이다 — 전역 자원이고 같은 BC 가 소비한다.
 * [VersionPermissionResolver] 의 `Boolean` 규약은 프로젝트 스코프 자원의 것이라 여기 맞지 않는다.
 * stub 구현체는 예외를 던지지 않는다.
 *
 * ## prod 판정 경로
 * [WorkflowScope.Global] 은 권한 매트릭스를 거치지 않고 `SystemPermissionResolver.isSystemAdmin`
 * 으로 판정한다. [WorkflowScope.Project] 는 멤버 게이트 + `role_permissions` 매트릭스
 * (`MANAGE_WORKFLOW`)를 탄다 — `IdentityAccessWorkflowSchemePermissionResolver` 와 같은 경로다.
 *
 * ★**[WorkflowDefinitionPermission.DELETE] 만 예외다.** 대상이 프로젝트 소유여도 시스템 관리자만
 * 통과한다(스펙 §4 D6). 이슈가 물려 있는 워크플로우 삭제는 파급이 크고, 그 위임은 요구에 없다.
 *
 * ## 검사 순서 — 권한이 먼저, 존재 확인이 나중
 * 호출부는 대상 조회보다 **먼저** 이 포트를 부른다. 존재 probe 를 막기 위한 의도된 정책이며
 * `VersionApplicationService` 를 비롯한 이 저장소의 관례다. 결과적으로 권한 없는 행위자는
 * 워크플로우가 있든 없든 거부를 받는다.
 *
 * ## ArchUnit 강제
 * 소비자(Service/Controller 계층)는 이 interface 만 의존한다. 구체 구현체 직접 import 금지.
 *
 * @see WorkflowDefinitionPermission
 */
interface WorkflowDefinitionPermissionResolver {
    /**
     * 주어진 행위자([actorId])가 요청 권한([permission])을 보유하는지 검증하고, 없으면 예외를 던진다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID. nil UUID 는 호출 이전 인증 단계에서 이미 거부된다.
     * @param permission 검증할 워크플로우 정의 권한.
     * @param scope 대상 워크플로우의 소유 범위. 전역 공유면 [WorkflowScope.Global],
     *   프로젝트 전용이면 [WorkflowScope.Project].
     * @throws WorkflowDefinitionAccessDeniedException (정식 구현체) 권한이 없을 때.
     */
    fun requirePermission(
        actorId: UUID,
        permission: WorkflowDefinitionPermission,
        scope: WorkflowScope,
    )
}
