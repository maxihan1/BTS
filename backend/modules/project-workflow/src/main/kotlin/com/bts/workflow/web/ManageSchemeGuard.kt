// 전환 규칙 관리 REST 컨트롤러 2종이 공유하는 MANAGE_SCHEME + Global 권한 가드

package com.bts.workflow.web

import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.port.outbound.toUuid

/**
 * 현재 인증 주체에게 MANAGE_SCHEME + Global 권한을 요구한다. 없으면 그 자리에서 거부한다.
 *
 * ★ **저장소의 보안 계약이 사본 위에 서면 안 된다.** 전환 규칙 관리 컨트롤러(validator ·
 * post-action)가 각자 같은 세 줄을 들고 있으면, 스코프를 Global 에서 워크플로우 단위로 좁히는 날
 * 한쪽만 고쳐지고 다른 쪽은 조용히 옛 스코프로 남는다 — 두 컨트롤러 테스트가 각자 자기 사본만
 * 지키므로 red 도 안 난다. 가드는 한 구현이어야 한다.
 *
 * 순서가 계약의 일부다. 리소스를 조회하기 **전에** 부른다 — 뒤집히면 없는 워크플로우엔 404,
 * 있는 워크플로우엔 403 이 나가서 권한 없는 사용자가 응답만으로 실재 여부를 알아낸다(존재 probe).
 * (메모리 permission-assert-before-existence-makes-403-lie · auth-extraction-before-resource-lookup)
 *
 * @throws org.springframework.web.server.ResponseStatusException 인증 주체가 없거나 UUID 가 아닐 때(401).
 * @throws com.bts.shared.permission.WorkflowSchemeAccessDeniedException MANAGE_SCHEME 이 없을 때(403).
 */
internal fun WorkflowSchemePermissionResolver.requireManageScheme() {
    val actor = CurrentActor.current()
    requirePermission(
        actor.toUuid(),
        WorkflowSchemePermission.MANAGE_SCHEME,
        WorkflowSchemeScope.Global,
    )
}
