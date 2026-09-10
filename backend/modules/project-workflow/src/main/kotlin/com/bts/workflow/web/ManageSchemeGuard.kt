// 전환 규칙 관리 REST 컨트롤러 2종이 공유하는 MANAGE_SCHEME 권한 가드 — 스코프는 워크플로우 소유를 따른다

package com.bts.workflow.web

import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.workflow.port.outbound.toUuid
import com.bts.workflow.scheme.application.WorkflowOwnershipScopeResolver
import org.springframework.stereotype.Component

/**
 * 현재 인증 주체에게 대상 워크플로우에 대한 MANAGE_SCHEME 권한을 요구한다. 없으면 그 자리에서 거부한다.
 *
 * ★ **저장소의 보안 계약이 사본 위에 서면 안 된다.** 전환 규칙 관리 컨트롤러(validator ·
 * post-action)가 각자 같은 세 줄을 들고 있으면, 스코프를 Global 에서 워크플로우 단위로 좁히는 날
 * 한쪽만 고쳐지고 다른 쪽은 조용히 옛 스코프로 남는다 — 두 컨트롤러 테스트가 각자 자기 사본만
 * 지키므로 red 도 안 난다. 가드는 한 구현이어야 한다.
 *
 * FR-WF-08 에서 그 「좁히는 날」이 왔다. 스코프를 `WorkflowScope.Global` 하드코딩에서
 * 워크플로우의 소유 프로젝트 판정으로 바꾼다 — 전역 워크플로우는 그대로 전역, 프로젝트 전용
 * 워크플로우는 그 프로젝트 스코프다. 판정 자체는 [WorkflowOwnershipScopeResolver] 한 곳에 있다.
 *
 * 순서가 계약의 일부다. 리소스를 조회하기 **전에** 부른다 — 뒤집히면 없는 워크플로우엔 404,
 * 있는 워크플로우엔 403 이 나가서 권한 없는 사용자가 응답만으로 실재 여부를 알아낸다(존재 probe).
 * (메모리 permission-assert-before-existence-makes-403-lie · auth-extraction-before-resource-lookup)
 *
 * 스코프 결정이 대상 워크플로우를 한 번 읽지만, 이는 **권한 판정의 입력**이지 응답에 새는 정보가
 * 아니다. 없는 키는 전역으로 떨어져 SYSTEM_ADMIN 만 통과하므로 존재 여부가 드러나지 않는다.
 *
 * @param permissionResolver 스킴 권한 평가 outbound port.
 * @param scopeResolver 워크플로우 소유 → 권한 스코프 변환의 단일 결정 지점.
 */
@Component
class ManageSchemeGuard(
    private val permissionResolver: WorkflowSchemePermissionResolver,
    private val scopeResolver: WorkflowOwnershipScopeResolver,
) {
    /**
     * [workflowKey] 워크플로우의 전환 규칙을 관리할 권한을 요구한다.
     *
     * @param workflowKey 대상 워크플로우 키. 스코프 결정의 근거다.
     * @throws org.springframework.web.server.ResponseStatusException 인증 주체가 없거나 UUID 가 아닐 때(401).
     * @throws com.bts.shared.permission.WorkflowSchemeAccessDeniedException MANAGE_SCHEME 이 없을 때(403).
     */
    fun requireForWorkflow(workflowKey: String) {
        val actor = CurrentActor.current()
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.MANAGE_SCHEME,
            scopeResolver.ofWorkflow(workflowKey),
        )
    }
}
