// 활성 프로젝트 UUID 를 id 또는 key 로 조회하는 jOOQ Repository (hexagonal 경계 — jOOQ 는 repository 레이어에만)

package com.bts.issue.component.repository

import com.bts.issue.jooq.tables.references.PROJECTS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * issue-tracking 이 소유한 projects 테이블에서 활성 프로젝트 UUID 를 조회한다(in-BC).
 *
 * jOOQ generated 코드 접촉은 repository 레이어로 한정한다(ArchUnit 룰 2, hexagonal 경계).
 * application 의 [com.bts.issue.component.application.ProjectLookup] 이 이 Repository 를 통해
 * DB 에 접근하며, jOOQ 를 직접 import 하지 않는다.
 *
 * 활성 기준: `deleted_at IS NULL`. 미존재·소프트 삭제 시 null.
 */
@Repository
class ProjectLookupRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * UUID 로 활성 프로젝트 id 를 조회한다.
     *
     * @param id 프로젝트 UUID.
     * @return 활성 프로젝트의 UUID. 미존재·소프트 삭제 시 null.
     */
    @Transactional(readOnly = true)
    fun findActiveProjectId(id: UUID): UUID? {
        log.debug("ProjectLookupRepository.findActiveProjectId id={}", id)
        return dsl
            .select(PROJECTS.ID)
            .from(PROJECTS)
            .where(PROJECTS.ID.eq(id))
            .and(PROJECTS.DELETED_AT.isNull)
            .fetchOne(PROJECTS.ID)
    }

    /**
     * projectKey 로 활성 프로젝트 id 를 조회한다.
     *
     * @param projectKey 프로젝트 키 (예: "BTS").
     * @return 활성 프로젝트의 UUID. 미존재·소프트 삭제 시 null.
     */
    @Transactional(readOnly = true)
    fun findActiveProjectIdByKey(projectKey: String): UUID? {
        log.debug("ProjectLookupRepository.findActiveProjectIdByKey key={}", projectKey)
        return dsl
            .select(PROJECTS.ID)
            .from(PROJECTS)
            .where(PROJECTS.KEY.eq(projectKey))
            .and(PROJECTS.DELETED_AT.isNull)
            .fetchOne(PROJECTS.ID)
    }
}
