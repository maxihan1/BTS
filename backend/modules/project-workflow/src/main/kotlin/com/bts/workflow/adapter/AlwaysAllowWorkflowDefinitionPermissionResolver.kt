// AlwaysAllow stub — 비-prod 전용. @Profile("!prod") 로 운영에서 절대 활성화되지 않는다.

package com.bts.workflow.adapter

import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 개발·테스트·스테이징에서 워크플로우 정의 권한을 항상 허용하는 stub.
 *
 * ### ★ 이 stub 때문에 권한 회귀가 로컬에서 안 보인다
 * 항상 허용하므로 **검사 순서 오류나 권한 누락이 로컬 눈확인으로는 드러나지 않는다.**
 * 운영 사용자만 거부를 받는다. 그래서 권한 관련 테스트는 이 stub 을 우회해
 * [IdentityAccessWorkflowDefinitionPermissionResolver] 를 직접 세우거나 거부 스텁을 주입한다
 * (MEMORY `permission-assert-before-existence-makes-403-lie` · `unreachable-state-fixture-is-fake-green`).
 *
 * ### 왜 identity-access 에 Dev 어댑터를 따로 두지 않는가
 * `WorkflowSchemePermissionResolver` 와 같은 형태를 쓴다 — 비-prod 구현은 **BC 쪽 stub 하나**다.
 * 양쪽에 비-prod 빈을 두면 `@Profile("!prod")` 가 둘 다 살아 빈 충돌이 난다.
 */
@Component
@Profile("!prod")
class AlwaysAllowWorkflowDefinitionPermissionResolver : WorkflowDefinitionPermissionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun requirePermission(
        actorId: UUID,
        permission: WorkflowDefinitionPermission,
    ) {
        log.warn(
            "AlwaysAllow stub 사용 중: actor={} permission={} — 운영에서는 시스템 관리자만 허용된다",
            actorId,
            permission,
        )
    }
}
