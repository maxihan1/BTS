// issue-tracking BC 소유 projects 테이블에서 projectIdOrKey → 활성 프로젝트 UUID 를 해석하는 서비스

package com.bts.issue.component.application

import com.bts.issue.jooq.tables.references.PROJECTS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * projectIdOrKey (UUID 문자열 또는 projectKey) 를 활성 프로젝트 UUID 로 해석한다.
 *
 * - 입력이 UUID 형식이면 해당 UUID 의 활성 프로젝트를 DB 에서 확인하고 반환한다.
 * - 그 외에는 projectKey 로 간주해 `projects.key` 를 조회한다.
 * - 활성 기준: `deleted_at IS NULL`.
 * - 미존재 또는 소프트 삭제된 경우 null 반환 (상위에서 404 처리).
 *
 * in-BC: issue-tracking 이 소유한 projects 테이블을 직접 jOOQ DSL 로 조회.
 * cross-BC 호출 없음.
 *
 * 쿼리 패턴 출처: `IssueRepository.findProjectIdByKey` (soft-delete 제외 조건 동형).
 * IssueRepository 자체는 수정하지 않음 — DSLContext 공유로 동일 패턴 재구현.
 */
@Service
class ProjectLookup(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * projectIdOrKey 를 활성 프로젝트 UUID 로 해석한다.
     *
     * @param projectIdOrKey UUID 문자열 또는 projectKey (예: "BTS").
     * @return 활성 프로젝트의 UUID. 미존재·소프트 삭제·잘못된 형식이면 null.
     */
    @Transactional(readOnly = true)
    fun resolve(projectIdOrKey: String): UUID? {
        val uuid = tryParseUuid(projectIdOrKey)
        return if (uuid != null) {
            resolveByUuid(uuid)
        } else {
            resolveByKey(projectIdOrKey)
        }
    }

    // ── private ───────────────────────────────────────────────────────────────

    /**
     * 문자열을 UUID 로 파싱한다. 형식이 맞지 않으면 null 반환.
     */
    private fun tryParseUuid(value: String): UUID? =
        try {
            UUID.fromString(value)
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * UUID 로 활성 프로젝트를 조회한다.
     * IssueRepository.findProjectIdByKey 패턴에 UUID 조건을 추가한 신규 분기.
     */
    private fun resolveByUuid(id: UUID): UUID? {
        log.debug("ProjectLookup.resolveByUuid id={}", id)
        return dsl
            .select(PROJECTS.ID)
            .from(PROJECTS)
            .where(PROJECTS.ID.eq(id))
            .and(PROJECTS.DELETED_AT.isNull)
            .fetchOne(PROJECTS.ID)
    }

    /**
     * projectKey 로 활성 프로젝트 id 를 조회한다.
     * IssueRepository.findProjectIdByKey 와 동일한 쿼리 패턴 (soft-delete 제외).
     */
    private fun resolveByKey(projectKey: String): UUID? {
        log.debug("ProjectLookup.resolveByKey key={}", projectKey)
        return dsl
            .select(PROJECTS.ID)
            .from(PROJECTS)
            .where(PROJECTS.KEY.eq(projectKey))
            .and(PROJECTS.DELETED_AT.isNull)
            .fetchOne(PROJECTS.ID)
    }
}
