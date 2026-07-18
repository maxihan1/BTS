// 프로젝트 목록/설정 조회 전담 읽기 전용 jOOQ Repository (FR-PJ PR-3 Task 1)

package com.bts.issue.project.repository

import com.bts.issue.jooq.tables.records.ProjectsRecord
import com.bts.issue.jooq.tables.references.PROJECTS
import com.bts.issue.project.domain.Project
import org.jooq.Condition
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 프로젝트 목록/설정 조회 전담 읽기 전용(read) Repository.
 *
 * jOOQ generated 코드 접촉은 repository 레이어로 한정한다(ArchUnit 룰 2, hexagonal 경계).
 * 반환 타입은 기존 도메인 [Project] 를 재사용한다 — 게이트1 확정으로 별도 `ProjectView` 신설을
 * 취소했다(FR-PJ PR-3 plan). `Project.archivedAt` 매핑(FR-PJ-04)은 [toProject] 단일 지점에서
 * 담당 — `findAccessibleByKeys`·`findByIdOrKey` 두 조회 메서드가 공유한다(매핑 중복 없음).
 *
 * 단일 테이블(`projects`) 조회만 다루므로 다중 LEFT JOIN + count 카티전 곱 문제와는 무관하다.
 * 활성 기준은 `deleted_at IS NULL`. [findAccessibleByKeys] 의 `archived` 파라미터(FR-PJ-04 PR-4
 * Task 6)는 이 소프트 삭제 기준과 **직교하는** 별도 필터 축이다 — `deleted_at`/`archived_at` 를
 * 결합하지 않고 각자 자기 조건으로만 필터링한다.
 *
 * @param dsl jOOQ DSLContext.
 */
@Repository
class ProjectQueryRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 주어진 key 집합 중 접근 가능한 프로젝트를 name 오름차순으로 조회한다.
     *
     * `archived` 축은 [ProjectMembershipPort][com.bts.shared.membership.ProjectMembershipPort] 가
     * 반환하는 key 집합 **위에** 얹는 필터일 뿐, 그 포트의 술어는 건드리지 않는다(FR-PJ-04 PR-4
     * Task 6, PJ2-2 — §B3 `projectKeysOf` 불변).
     *
     * @param keys 조회할 프로젝트 key 집합. 빈 집합이면 쿼리를 실행하지 않고 즉시 빈 리스트를
     *   반환한다(빈 `IN` 절/불필요 쿼리 방지).
     * @param archived `false`(기본) 이면 활성 프로젝트만, `true`이면 아카이브 프로젝트만 반환한다
     *   (D8 지라 관례 — 목록은 아카이브 기본 제외, `?archived=true` 로만 아카이브를 본다).
     * @return 조건에 맞는 프로젝트 목록(name 오름차순). 소프트 삭제된 프로젝트는 항상 제외한다.
     */
    @Transactional(readOnly = true)
    fun findAccessibleByKeys(
        keys: Set<String>,
        archived: Boolean = false,
    ): List<Project> {
        if (keys.isEmpty()) return emptyList()

        log.debug("ProjectQueryRepository.findAccessibleByKeys keys={} archived={}", keys, archived)
        return dsl
            .selectFrom(PROJECTS)
            .where(PROJECTS.KEY.`in`(keys))
            .and(PROJECTS.DELETED_AT.isNull)
            .and(archivedListCondition(archived))
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

    /**
     * 목록 조회용 아카이브 필터 축 — `archived=false` 면 활성만, `true` 면 아카이브만.
     *
     * `deleted_at` 은 여기서 절대 참조하지 않는다(호출부에서 이미 별도 조건으로 결합) — 아카이브와
     * 소프트 삭제는 직교하는 별도 라이프사이클 축이다([com.bts.issue.project.archive.ProjectArchiveGuard]
     * 동형 원칙).
     */
    private fun archivedListCondition(archived: Boolean): Condition =
        if (archived) PROJECTS.ARCHIVED_AT.isNotNull else PROJECTS.ARCHIVED_AT.isNull

    private fun ProjectsRecord.toProject(): Project =
        Project(
            id = id ?: error("projects.id must not be null after DB fetch"),
            key = key,
            name = name,
            archivedAt = archivedAt?.toInstant(),
        )
}
