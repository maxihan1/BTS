// 프로젝트 목록/설정 조회 전담 읽기 전용 jOOQ Repository (FR-PJ PR-3 Task 1)

package com.bts.issue.project.repository

import com.bts.issue.jooq.tables.records.ProjectsRecord
import com.bts.issue.jooq.tables.references.PROJECTS
import com.bts.issue.project.domain.Project
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 프로젝트 목록/설정 조회 전담 읽기 전용(read) Repository.
 *
 * jOOQ generated 코드 접촉은 repository 레이어로 한정한다(ArchUnit 룰 2, hexagonal 경계).
 * 반환 타입은 기존 도메인 [Project](id, key, name) 를 재사용한다 — 게이트1 확정으로 별도
 * `ProjectView` 신설을 취소했다(FR-PJ PR-3 plan).
 *
 * 단일 테이블(`projects`) 조회만 다루므로 다중 LEFT JOIN + count 카티전 곱 문제와는 무관하다.
 * 활성 기준은 `deleted_at IS NULL`.
 *
 * @param dsl jOOQ DSLContext.
 */
@Repository
class ProjectQueryRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 주어진 key 집합 중 활성 프로젝트를 name 오름차순으로 조회한다.
     *
     * @param keys 조회할 프로젝트 key 집합. 빈 집합이면 쿼리를 실행하지 않고 즉시 빈 리스트를
     *   반환한다(빈 `IN` 절/불필요 쿼리 방지).
     * @return 활성 프로젝트 목록(name 오름차순). 소프트 삭제된 프로젝트는 제외한다.
     */
    @Transactional(readOnly = true)
    fun findAccessibleByKeys(keys: Set<String>): List<Project> {
        if (keys.isEmpty()) return emptyList()

        log.debug("ProjectQueryRepository.findAccessibleByKeys keys={}", keys)
        return dsl
            .selectFrom(PROJECTS)
            .where(PROJECTS.KEY.`in`(keys))
            .and(PROJECTS.DELETED_AT.isNull)
            .orderBy(PROJECTS.NAME.asc())
            .fetch()
            .map { it.toProject() }
    }

    /**
     * 프로젝트 UUID 로 활성 프로젝트 단건을 조회한다.
     *
     * 다형 해석(UUID/key 판별)은 상위 서비스의 [com.bts.issue.project.ProjectLookup.resolve] 가
     * 담당하므로, 이 메서드는 UUID 만 받는다.
     *
     * @param projectId 프로젝트 UUID.
     * @return 활성 프로젝트. 미존재·소프트 삭제 시 null.
     */
    @Transactional(readOnly = true)
    fun findByIdOrKey(projectId: UUID): Project? {
        log.debug("ProjectQueryRepository.findByIdOrKey id={}", projectId)
        return dsl
            .selectFrom(PROJECTS)
            .where(PROJECTS.ID.eq(projectId))
            .and(PROJECTS.DELETED_AT.isNull)
            .fetchOne()
            ?.toProject()
    }

    private fun ProjectsRecord.toProject(): Project =
        Project(
            id = id ?: error("projects.id must not be null after DB fetch"),
            key = key,
            name = name,
            archivedAt = archivedAt?.toInstant(),
        )
}
