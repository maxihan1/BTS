// identity-access BC에서 issue-tracking BC의 프로젝트 존재를 read-only로 확인하는 포트 인터페이스 및 구현체

package com.atlas.bts.identity.project

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * cross-BC read-only 조회 포트 — projects 테이블 존재 확인 (FR-PM-01 Task 4).
 *
 * identity-access BC는 issue-tracking BC 코드를 직접 import하지 않는다 (ADR D2 deployment invariant).
 * 동일 PostgreSQL·동일 public 스키마를 공유하므로 DB read-only 쿼리로 대체한다.
 * 분리 배포 시 SPI(Service Provider Interface)로 교체한다.
 *
 * **의존 컬럼**: `id UUID`, `deleted_at TIMESTAMPTZ` 두 컬럼만.
 * projects DDL 변경 시 이 두 컬럼 유지 여부를 반드시 확인할 것.
 *
 * 구현체: [JdbcProjectDirectory].
 */
interface ProjectDirectory {

    /**
     * 프로젝트가 활성 상태로 존재하는지 확인한다.
     *
     * @param projectId 확인할 프로젝트 UUID
     * @return 행이 존재하고 `deleted_at IS NULL`이면 `true`, 소프트삭제 또는 미존재이면 `false`
     */
    fun exists(projectId: UUID): Boolean

    /**
     * 프로젝트 키를 UUID로 변환한다.
     *
     * **cross-BC 격리**: key 컬럼 소유권은 issue-tracking BC.
     * key 정규식(`^[A-Z][A-Z0-9]{1,9}$`) 검증은 issue-tracking 소유이므로 이 포트는 신뢰하고 그대로 전달한다.
     * projects DDL drift 발생 시 key 컬럼 유지 여부를 반드시 확인할 것.
     *
     * @param key 변환할 프로젝트 키 (예: "ATLAS")
     * @return 활성 프로젝트의 UUID, 소프트삭제 또는 미존재이면 `null`
     */
    fun resolveKeyToId(key: String): UUID?
}

/**
 * [ProjectDirectory] JDBC 구현체 (FR-PM-01 Task 4).
 *
 * **cross-BC 격리 (ADR D2)**:
 * issue-tracking 코드를 import하지 않는다. 동일 DB public 스키마의 projects 테이블을
 * read-only SQL로만 접근한다. 분리 배포 시 SPI로 교체한다.
 *
 * **SQL 인젝션 방어**:
 * 모든 파라미터를 [NamedParameterJdbcTemplate]에 바인딩한다 (DEVELOPMENT.md §1.3).
 * 문자열 결합 방식의 SQL 생성 절대 금지.
 *
 * **트랜잭션**:
 * 읽기 전용 단일 쿼리 — `@Transactional(readOnly = true)`.
 */
@Repository
@Transactional(readOnly = true)
class JdbcProjectDirectory(
    private val jdbc: NamedParameterJdbcTemplate,
) : ProjectDirectory {

    override fun exists(projectId: UUID): Boolean =
        jdbc.queryForObject(
            SQL_EXISTS,
            mapOf("id" to projectId),
            Boolean::class.java,
        ) ?: false

    override fun resolveKeyToId(key: String): UUID? =
        jdbc.query(
            SQL_RESOLVE_KEY,
            mapOf("key" to key),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }
            .firstOrNull()

    private companion object {
        /**
         * projects 행 존재 + 소프트삭제 미적용 여부를 단일 EXISTS 쿼리로 확인한다.
         * SELECT EXISTS 는 항상 행 하나를 반환하므로 null safe하다.
         */
        const val SQL_EXISTS = """
            SELECT EXISTS(
                SELECT 1
                FROM projects
                WHERE id = :id
                  AND deleted_at IS NULL
            )
        """

        /**
         * projectKey → UUID 변환. 소프트삭제 행은 제외한다.
         * key 컬럼은 issue-tracking BC 소유 (UNIQUE, NOT NULL 보장).
         */
        const val SQL_RESOLVE_KEY = """
            SELECT id
            FROM projects
            WHERE key = :key
              AND deleted_at IS NULL
        """
    }
}
