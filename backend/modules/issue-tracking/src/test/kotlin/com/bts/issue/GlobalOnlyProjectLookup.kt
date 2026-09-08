// 워크플로우 소유를 늘 「전역」으로 답하는 테스트 스텁 — issue-tracking 조립 테스트 공용 (FR-WF-08)

package com.bts.issue

import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import java.util.UUID

/**
 * `projects` 를 되짚지 않는 [ProjectLookupPort] 스텁.
 *
 * issue-tracking 의 조립 테스트들은 **이슈 전환**을 재지 워크플로우 소유를 재지 않는다. 그 테스트들이
 * 쓰는 워크플로우·스킴은 전부 전역 시드라 소유를 되짚을 것이 없고, 되짚지 못하면
 * `WorkflowOwnershipScopeResolver` 는 fail-closed 로 전역을 준다 — 픽스처의 실제 상태와 같다.
 *
 * ★실물 `JdbcProjectLookupAdapter` 를 쓰지 않는 이유는 **스코프**다. 이 조립 코드는 파일마다
 * `projectLookup` 선언 위치가 달라(어떤 곳은 함수 파라미터, 어떤 곳은 조립 뒤) 이름으로 참조하면
 * 절반이 컴파일에서 깨진다. 이름 없는 상수 하나로 두면 그 의존이 사라진다.
 *
 * 소유별 판정 자체는 project-workflow 의 `WorkflowOwnershipScopeResolverTest` 가 전수로 잰다.
 */
object GlobalOnlyProjectLookup : ProjectLookupPort {
    override fun findIdByKey(projectKey: ProjectKey): UUID? = null

    override fun findKeyById(projectId: UUID): ProjectKey? = null
}
