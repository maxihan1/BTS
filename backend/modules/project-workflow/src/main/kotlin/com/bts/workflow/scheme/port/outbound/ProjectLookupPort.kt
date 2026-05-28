// project-workflow BC 내부 임시 outbound port — projectKey → projects.id(UUID) 변환

package com.bts.workflow.scheme.port.outbound

import com.bts.workflow.scheme.domain.ProjectKey
import java.util.UUID

/**
 * projectKey → projects.id(UUID) 변환 outbound port.
 *
 * ## 임시 stub 경고
 * 현재 구현체 [com.bts.workflow.scheme.adapter.outbound.JdbcProjectLookupAdapter] 는
 * project-workflow BC 가 `projects` 테이블을 직접 jOOQ 로 조회하는 cross-BC 회색지대 구현이다.
 *
 * **FR-PM-04 정식 cross-BC port 도입 시 이 인터페이스의 구현체를 교체해야 한다.**
 * projects 테이블은 project-management BC 소유이며, 정식 port 가 도입되면
 * [JdbcProjectLookupAdapter] 를 삭제하고 project-management 공개 API (`:api:` 패키지) 구현체로 대체한다.
 *
 * 선례: [com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver] —
 * 동일한 패턴으로 임시 stub 이 도입되어 있다.
 *
 * @see com.bts.workflow.scheme.adapter.outbound.JdbcProjectLookupAdapter
 */
interface ProjectLookupPort {
    /**
     * [projectKey] 에 해당하는 프로젝트의 UUID(projects.id) 를 반환한다.
     *
     * @param projectKey 조회할 프로젝트 키.
     * @return 프로젝트 UUID, 없으면 null.
     */
    fun findIdByKey(projectKey: ProjectKey): UUID?
}
