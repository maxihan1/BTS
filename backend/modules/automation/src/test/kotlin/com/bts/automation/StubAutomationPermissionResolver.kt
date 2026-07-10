// automation 통합 테스트용 fail-closed AutomationPermissionResolver stub — 프로젝트 키 단위 allow/deny 토글 (FR-AT-01 Task 6)

package com.bts.automation

import com.bts.shared.permission.AutomationPermissionResolver
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * automation 통합 테스트가 프로젝트 키 단위로 허용을 명시 등록하는 fail-closed [AutomationPermissionResolver]
 * stub (FR-AT-01 Task 6, plan-eng-review E4).
 *
 * automation BC 격리상 shared-kernel [AutomationPermissionResolver] 포트의 prod 구현(identity-access)은
 * automation 모듈의 컴파일/테스트 클래스패스에 존재하지 않는다(Task 5 가 의도적으로 automation 쪽에 두지
 * 않음). [AutomationRuleService] 는 이 포트를 **non-null 생성자 주입**으로 요구하므로([[crossbc-resolver-nullable-fail-open]]
 * 회귀 방지), automation test-boot 컨텍스트에는 이 stub 이 대신 등록되어야 한다.
 *
 * ## fail-closed (교훈 crossbc-resolver-nullable-fail-open)
 * [allowedProjectKeys] 에 등록되지 않은 프로젝트 키는 기본적으로 모두 거부(false)한다. 테스트가 허용할
 * 프로젝트 키를 [allow] 로 명시 등록해야 통과한다. slack `StubSystemPermissionResolver` 와 동일 정책
 * (기본값·미등록은 모두 거부로 수렴).
 *
 * ## actorId 는 판정에 사용하지 않음
 * 이 stub 은 프로젝트 키 단위로만 allow/deny 를 토글한다(Task 6 컨트롤러 테스트가 요구하는 최소 범위).
 * actor 별 세분화가 필요해지면(후속 task) 등록 방식을 `Pair<UUID, String>` 으로 확장한다.
 *
 * ## 빈 등록 위치 (파일 범위 제약)
 * `AutomationTestcontainersBase`(Task 1 산출물)는 이 Task 의 파일 범위 밖이라 `@Bean` 을 추가할 수 없다.
 * 대신 [com.bts.automation.web.AutomationRuleControllerTest] 가 자신의 nested `@TestConfiguration` 에서
 * 이 클래스를 `@Bean` 으로 등록한다(테스트 파일 자체 범위 내).
 */
class StubAutomationPermissionResolver : AutomationPermissionResolver {
    private val allowedProjectKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun hasManageAutomation(
        actorId: UUID,
        projectKey: String,
    ): Boolean = projectKey in allowedProjectKeys

    /** [projectKey] 에 대한 MANAGE_AUTOMATION 판정을 허용으로 등록한다. */
    fun allow(projectKey: String) {
        allowedProjectKeys += projectKey
    }

    /** [projectKey] 에 대한 허용 등록을 해제한다(이후 판정은 다시 거부로 수렴). */
    fun deny(projectKey: String) {
        allowedProjectKeys -= projectKey
    }

    /** 모든 허용 등록을 초기화한다(테스트 격리용). */
    fun reset() {
        allowedProjectKeys.clear()
    }
}
