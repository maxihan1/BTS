// 프로젝트 require_2fa 컬럼 UPDATE 전담 jOOQ Repository (FR-MF-04 Task 3)

package com.bts.issue.project.repository

import com.bts.issue.jooq.tables.references.PROJECTS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * projects.require_2fa 컬럼의 갱신을 담당하는 Repository.
 *
 * jOOQ generated 코드 접촉은 repository 레이어로 한정한다(ArchUnit 룰 2, hexagonal 경계).
 * [com.bts.issue.project.application.ProjectRequire2faApplicationService] 가
 * 이 Repository 를 통해 DB 에 접근하며, jOOQ 를 직접 import 하지 않는다.
 *
 * **책임 분리.** [ProjectLookupRepository] 는 조회 전용, 이 Repository 는 require_2fa 갱신 전담.
 * [ProjectLeadRepository] 와 동형 패턴.
 */
@Repository
class ProjectRequire2faRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트의 require_2fa 를 갱신한다.
     *
     * 소프트삭제된 프로젝트는 대상에서 제외한다(deleted_at IS NULL 조건).
     * 미존재 또는 소프트삭제 프로젝트면 UPDATE 가 0행을 영향하며, 서비스에서 사전 존재 확인 후 호출하므로
     * 이 Repository 는 별도 예외를 던지지 않는다.
     *
     * @param projectId 갱신 대상 프로젝트 UUID.
     * @param requireTwoFactor 설정할 require_2fa 값. true 면 민감 프로젝트, false 면 해제.
     */
    @Transactional
    fun updateRequire2fa(
        projectId: UUID,
        requireTwoFactor: Boolean,
    ) {
        log.debug(
            "ProjectRequire2faRepository.updateRequire2fa projectId={} requireTwoFactor={}",
            projectId,
            requireTwoFactor,
        )
        dsl
            .update(PROJECTS)
            .set(PROJECTS.REQUIRE_2FA, requireTwoFactor)
            .where(PROJECTS.ID.eq(projectId))
            .and(PROJECTS.DELETED_AT.isNull)
            .execute()
    }
}
