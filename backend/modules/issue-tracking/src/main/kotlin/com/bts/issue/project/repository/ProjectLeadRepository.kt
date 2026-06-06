// 프로젝트 리드(lead_user_id) 조회·갱신 전담 jOOQ Repository (ProjectLookupRepository 와 책임 분리)

package com.bts.issue.project.repository

import com.bts.issue.jooq.tables.references.PROJECTS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * projects.lead_user_id 컬럼의 조회·갱신을 담당하는 Repository.
 *
 * **책임 분리.**
 * [ProjectLookupRepository] 는 projectIdOrKey 를 활성 프로젝트 UUID 로 해석하는 읽기 전용 책임만 가진다.
 * 이 Repository 는 리드 쓰기(updateLead) + 리드 조회(findLeadUserId) 를 전담한다.
 *
 * jOOQ generated 코드 접촉은 repository 레이어로 한정한다(ArchUnit 룰 2, hexagonal 경계).
 * application 의 [com.bts.issue.project.application.ProjectLeadApplicationService] 가
 * 이 Repository 를 통해 DB 에 접근하며, jOOQ 를 직접 import 하지 않는다.
 *
 * 활성 기준: `deleted_at IS NULL`. [findLeadUserId] 는 활성 프로젝트에 한해 조회한다.
 */
@Repository
class ProjectLeadRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 활성 프로젝트의 lead_user_id 를 조회한다.
     *
     * 소프트삭제(deleted_at IS NOT NULL) 프로젝트 또는 미존재 프로젝트는 null 반환.
     *
     * @param projectId 프로젝트 UUID.
     * @return 리드로 지정된 사용자 UUID. 리드가 없거나 프로젝트가 활성 아니면 null.
     */
    @Transactional(readOnly = true)
    fun findLeadUserId(projectId: UUID): UUID? {
        log.debug("ProjectLeadRepository.findLeadUserId projectId={}", projectId)
        return dsl
            .select(PROJECTS.LEAD_USER_ID)
            .from(PROJECTS)
            .where(PROJECTS.ID.eq(projectId))
            .and(PROJECTS.DELETED_AT.isNull)
            .fetchOne(PROJECTS.LEAD_USER_ID)
    }

    /**
     * 프로젝트의 lead_user_id 를 갱신한다.
     *
     * @param projectId 갱신 대상 프로젝트 UUID.
     * @param leadUserId 지정할 리드 사용자 UUID. null 이면 해제.
     */
    @Transactional
    fun updateLead(
        projectId: UUID,
        leadUserId: UUID?,
    ) {
        log.debug("ProjectLeadRepository.updateLead projectId={} leadUserId={}", projectId, leadUserId)
        dsl
            .update(PROJECTS)
            .set(PROJECTS.LEAD_USER_ID, leadUserId)
            .where(PROJECTS.ID.eq(projectId))
            .execute()
    }
}
