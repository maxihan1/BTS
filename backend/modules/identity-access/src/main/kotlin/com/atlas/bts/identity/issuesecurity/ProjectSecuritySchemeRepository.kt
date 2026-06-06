// 프로젝트 ↔ 이슈 보안 스킴 적용 영속 포트 (FR-PM-06 Task 4)

package com.atlas.bts.identity.issuesecurity

import java.util.UUID

/**
 * 프로젝트에 적용된 이슈 보안 스킴(0~1개)을 관리하는 영속 포트.
 *
 * 프로젝트당 스킴은 최대 1개이며([assign] 으로 교체), `project_id` 는 issue-tracking BC 를
 * 가리키는 cross-BC 참조라 외래키가 없다. 적용 대상 `scheme_id` 는 issue_security_schemes 에
 * FK(ON DELETE RESTRICT)로 묶여 있어, 적용 중인 스킴은 삭제할 수 없다.
 */
interface ProjectSecuritySchemeRepository {
    /**
     * 프로젝트에 보안 스킴을 적용한다. 이미 적용된 스킴이 있으면 새 스킴으로 덮어쓴다(교체).
     *
     * @param projectId cross-BC 프로젝트 식별자(FK 없음)
     * @param schemeId 적용할 보안 스킴 식별자
     */
    fun assign(
        projectId: UUID,
        schemeId: UUID,
    )

    /**
     * 프로젝트에 적용된 보안 스킴 식별자를 반환한다.
     *
     * @return 적용된 scheme_id, 미적용이면 null
     */
    fun findByProject(projectId: UUID): UUID?

    /**
     * 프로젝트의 보안 스킴 적용을 해제한다. 미적용 프로젝트에 호출해도 멱등하다(no-op).
     */
    fun unassign(projectId: UUID)
}
