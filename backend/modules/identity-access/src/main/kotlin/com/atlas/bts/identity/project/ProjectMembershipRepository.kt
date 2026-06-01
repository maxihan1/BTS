// project_memberships 테이블 접근 인터페이스 + JdbcTemplate 구현체 (FR-PM-01 Task 3)

package com.atlas.bts.identity.project

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * project_memberships 테이블 접근 인터페이스 (FR-PM-01 Task 3).
 *
 * 한 사용자([ProjectMembership.userId])가 한 프로젝트([ProjectMembership.projectId])에서
 * 가지는 역할([ProjectRole])을 CRUD한다.
 *
 * 구현체: [JdbcProjectMembershipRepository].
 */
interface ProjectMembershipRepository {

    /**
     * 멤버십을 삽입하고 DB 반영 상태를 반환한다.
     *
     * UNIQUE(project_id, user_id) 위반 시 [org.springframework.dao.DataIntegrityViolationException]을
     * 그대로 전파한다. 상위 레이어가 이를 HTTP 409로 매핑한다.
     *
     * @param membership 삽입할 멤버십 값 객체
     * @return DB에 반영된 [ProjectMembership] (createdAt / updatedAt은 DB NOW() 기준)
     */
    fun save(membership: ProjectMembership): ProjectMembership

    /**
     * 특정 프로젝트 + 사용자 조합으로 멤버십을 조회한다.
     *
     * @return 존재하면 [ProjectMembership], 없으면 null
     */
    fun findByProjectAndUser(projectId: UUID, userId: UUID): ProjectMembership?

    /**
     * 프로젝트에 속한 모든 멤버십 목록을 반환한다.
     *
     * @return 멤버십이 없으면 빈 목록
     */
    fun listByProject(projectId: UUID): List<ProjectMembership>

    /**
     * 프로젝트의 전체 멤버 수를 반환한다.
     *
     * @return 0 이상의 정수
     */
    fun countByProject(projectId: UUID): Int

    /**
     * 프로젝트의 PROJECT_ADMIN 역할 보유자 수를 반환한다.
     *
     * 마지막 어드민 제거 방어 로직에 사용한다 (FR-PM-01 EC-01).
     *
     * @return 0 이상의 정수
     */
    fun countAdminsByProject(projectId: UUID): Int

    /**
     * 멤버십의 역할을 변경하고 updated_at을 NOW()로 갱신한다.
     *
     * @param projectId 대상 프로젝트 ID
     * @param userId 대상 사용자 ID
     * @param role 변경할 역할
     * @return 갱신된 [ProjectMembership]. 해당 멤버십이 존재하지 않으면 null.
     */
    fun updateRole(projectId: UUID, userId: UUID, role: ProjectRole): ProjectMembership?

    /**
     * 멤버십을 hard delete한다 (ADR D6: hard delete 정책).
     *
     * @return 삭제가 실제로 발생했으면 true, 해당 멤버십이 없었으면 false
     */
    fun deleteByProjectAndUser(projectId: UUID, userId: UUID): Boolean

    /**
     * 프로젝트 단위 PostgreSQL advisory 트랜잭션 락을 획득한다 (EC-1/EC-2b 동시성 보호).
     *
     * `pg_advisory_xact_lock(hi, lo)` — 트랜잭션 종료 시 자동 해제.
     * 부트스트랩(count→insert)과 마지막 admin 검사(count→delete/update)를
     * 직렬화하는 데 사용한다. 반드시 `@Transactional` 내에서 호출해야 한다.
     *
     * UUID를 상위 64bit([hi])와 하위 64bit([lo])로 분리하여 두 bigint 파라미터에 바인딩한다.
     *
     * @param projectId 락을 획득할 프로젝트 UUID
     */
    fun acquireProjectLock(projectId: UUID)
}

/**
 * [ProjectMembershipRepository] JDBC 구현체 (FR-PM-01 Task 3).
 *
 * **트랜잭션 경계 (DATA.md §6):**
 * 클래스 레벨 `@Transactional(REQUIRED, READ_COMMITTED)` — 호출 측 트랜잭션에 참여하거나 새로 시작.
 * 읽기 전용 메서드는 `@Transactional(readOnly = true)`로 오버라이드.
 *
 * **INSERT 설계:**
 * DB의 `DEFAULT gen_random_uuid()`와 `DEFAULT NOW()`를 활용한다.
 * 클라이언트가 id/createdAt/updatedAt을 제공하지 않고 RETURNING으로 DB 확정값을 받는다.
 *
 * **SQL 인젝션 방어:**
 * 모든 파라미터를 NamedParameterJdbcTemplate `:param` 바인딩으로 처리 (DEVELOPMENT.md §1.3).
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class JdbcProjectMembershipRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : ProjectMembershipRepository {

    /**
     * 멤버십을 삽입하고 DB RETURNING 결과를 반환한다.
     *
     * id / created_at / updated_at은 DB DEFAULT로 생성한다.
     * UNIQUE(project_id, user_id) 위반 시 [org.springframework.dao.DataIntegrityViolationException] 전파.
     */
    override fun save(membership: ProjectMembership): ProjectMembership =
        jdbc.queryForObject(
            SQL_INSERT,
            mapOf(
                "projectId" to membership.projectId,
                "userId" to membership.userId,
                "role" to membership.role.name,
            ),
            MembershipRowMapper,
        ) ?: error("INSERT RETURNING 결과 없음 — projectId=${membership.projectId}, userId=${membership.userId}")

    @Transactional(readOnly = true)
    override fun findByProjectAndUser(projectId: UUID, userId: UUID): ProjectMembership? =
        jdbc.query(
            SQL_FIND_BY_PROJECT_AND_USER,
            mapOf("projectId" to projectId, "userId" to userId),
            MembershipRowMapper,
        ).firstOrNull()

    @Transactional(readOnly = true)
    override fun listByProject(projectId: UUID): List<ProjectMembership> =
        jdbc.query(
            SQL_LIST_BY_PROJECT,
            mapOf("projectId" to projectId),
            MembershipRowMapper,
        )

    @Transactional(readOnly = true)
    override fun countByProject(projectId: UUID): Int =
        jdbc.queryForObject(
            SQL_COUNT_BY_PROJECT,
            mapOf("projectId" to projectId),
            Int::class.java,
        ) ?: 0

    @Transactional(readOnly = true)
    override fun countAdminsByProject(projectId: UUID): Int =
        jdbc.queryForObject(
            SQL_COUNT_ADMINS_BY_PROJECT,
            mapOf("projectId" to projectId, "role" to ProjectRole.PROJECT_ADMIN.name),
            Int::class.java,
        ) ?: 0

    /**
     * 멤버십 역할을 변경하고 updated_at을 NOW()로 갱신한다.
     *
     * UPDATE ... RETURNING으로 갱신 결과를 단일 쿼리로 반환한다.
     * 해당 행이 없으면 RETURNING 결과가 없으므로 null을 반환한다.
     */
    override fun updateRole(projectId: UUID, userId: UUID, role: ProjectRole): ProjectMembership? =
        jdbc.query(
            SQL_UPDATE_ROLE,
            mapOf("projectId" to projectId, "userId" to userId, "role" to role.name),
            MembershipRowMapper,
        ).firstOrNull()

    /**
     * 멤버십을 hard delete한다.
     *
     * 영향받은 행 수가 1 이상이면 true, 0이면 false를 반환한다.
     */
    override fun deleteByProjectAndUser(projectId: UUID, userId: UUID): Boolean =
        jdbc.update(
            SQL_DELETE_BY_PROJECT_AND_USER,
            mapOf("projectId" to projectId, "userId" to userId),
        ) > 0

    /**
     * 프로젝트 단위 advisory 트랜잭션 락을 획득한다.
     *
     * UUID를 상위 64bit(hi)와 하위 64bit(lo)로 분리하여 두 bigint 파라미터에 바인딩한다.
     * 문자열 결합 없이 named parameter 바인딩으로 SQL 인젝션을 원천 차단한다 (DEVELOPMENT.md §1.3).
     * 트랜잭션 커밋/롤백 시 PostgreSQL이 자동으로 락을 해제한다.
     */
    override fun acquireProjectLock(projectId: UUID) {
        val hi = projectId.mostSignificantBits
        val lo = projectId.leastSignificantBits
        jdbc.update(SQL_ADVISORY_LOCK, mapOf("hi" to hi, "lo" to lo))
    }

    // ── SQL 상수 ─────────────────────────────────────────────────────────────

    private companion object {

        /**
         * 멤버십 INSERT — id / created_at / updated_at은 DB DEFAULT 사용.
         * RETURNING으로 DB 확정값을 반환한다.
         */
        const val SQL_INSERT = """
            INSERT INTO project_memberships (project_id, user_id, role)
            VALUES (:projectId, :userId, :role)
            RETURNING project_id, user_id, role, created_at, updated_at
        """

        const val SQL_FIND_BY_PROJECT_AND_USER = """
            SELECT project_id, user_id, role, created_at, updated_at
            FROM project_memberships
            WHERE project_id = :projectId
              AND user_id = :userId
        """

        const val SQL_LIST_BY_PROJECT = """
            SELECT project_id, user_id, role, created_at, updated_at
            FROM project_memberships
            WHERE project_id = :projectId
        """

        const val SQL_COUNT_BY_PROJECT = """
            SELECT COUNT(*)
            FROM project_memberships
            WHERE project_id = :projectId
        """

        /** PROJECT_ADMIN 역할만 집계 — idx_project_memberships_admins 부분 인덱스 활용. */
        const val SQL_COUNT_ADMINS_BY_PROJECT = """
            SELECT COUNT(*)
            FROM project_memberships
            WHERE project_id = :projectId
              AND role = :role
        """

        /**
         * 멤버십 역할 UPDATE — updated_at을 NOW()로 갱신.
         * RETURNING으로 갱신된 행 반환. 해당 행이 없으면 결과 없음.
         */
        const val SQL_UPDATE_ROLE = """
            UPDATE project_memberships
            SET role       = :role,
                updated_at = NOW()
            WHERE project_id = :projectId
              AND user_id    = :userId
            RETURNING project_id, user_id, role, created_at, updated_at
        """

        const val SQL_DELETE_BY_PROJECT_AND_USER = """
            DELETE FROM project_memberships
            WHERE project_id = :projectId
              AND user_id    = :userId
        """

        /**
         * 프로젝트 UUID를 상위/하위 64bit으로 분리하여 advisory 트랜잭션 락을 획득한다.
         * :hi = mostSignificantBits, :lo = leastSignificantBits — 두 파라미터 모두 bigint.
         * SELECT pg_advisory_xact_lock 은 행을 반환하지 않으므로 jdbc.update 로 실행한다.
         *
         * CAST(:hi AS bigint) / CAST(:lo AS bigint):
         * NamedParameterJdbcTemplate 이 Long 파라미터를 PreparedStatement ?로 바인딩할 때
         * PostgreSQL 타입 추론이 실패하는 문제를 명시적 캐스팅으로 해소한다.
         * 문자열 결합 없이 named parameter 바인딩을 유지한다 (DEVELOPMENT.md §1.3).
         */
        const val SQL_ADVISORY_LOCK =
            "SELECT pg_advisory_xact_lock(CAST(:hi AS bigint), CAST(:lo AS bigint))"
    }
}

/** project_memberships RowMapper — ResultSet → ProjectMembership 변환 */
private object MembershipRowMapper : RowMapper<ProjectMembership> {
    override fun mapRow(rs: ResultSet, rowNum: Int): ProjectMembership =
        ProjectMembership(
            // getObject + UUID::class.java — Postgres JDBC 권장 방식 (JdbcUserRepository 동일 패턴)
            projectId = rs.getObject("project_id", UUID::class.java),
            userId = rs.getObject("user_id", UUID::class.java),
            role = ProjectRole.from(rs.getString("role")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
}
