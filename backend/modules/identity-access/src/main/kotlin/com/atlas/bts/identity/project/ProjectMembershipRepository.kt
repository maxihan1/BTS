// project_memberships 테이블 접근 인터페이스 + JdbcTemplate 구현체 (FR-PM-01 Task 3/B2)

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
 *
 * CRUD/카운트/락/조인 조회 + 사용자별 프로젝트 id 조회([listProjectIdsByUser])가 한 영속
 * 책임에 응집한다. 멤버십 영속 경계를 인위로 쪼개면 트랜잭션 결합만 늘어나므로 TooManyFunctions 억제.
 */
@Suppress("TooManyFunctions")
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
     * 사용자가 멤버로 속한 모든 프로젝트의 id 목록을 반환한다 (FR-MF-04 Task 4).
     *
     * MFA 강제 정책([com.atlas.bts.identity.mfa.MfaEnforcementPolicy])이 '민감 프로젝트 소속'을
     * 판정하기 위해 사용한다. project_memberships 는 hard delete 정책(ADR D6)이라
     * `deleted_at` 컬럼이 없으므로 soft-delete 필터가 필요 없다.
     *
     * @param userId 조회할 사용자 ID
     * @return 멤버십이 없으면 빈 목록
     */
    fun listProjectIdsByUser(userId: UUID): List<UUID>

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
     * `pg_advisory_xact_lock(bigint)` — 트랜잭션 종료 시 자동 해제.
     * 부트스트랩(count→insert)과 마지막 admin 검사(count→delete/update)를
     * 직렬화하는 데 사용한다. 반드시 `@Transactional` 내에서 호출해야 한다.
     *
     * UUID의 mostSignificantBits xor leastSignificantBits로 단일 bigint 락 키를 생성한다.
     *
     * @param projectId 락을 획득할 프로젝트 UUID
     */
    fun acquireProjectLock(projectId: UUID)

    /**
     * 프로젝트에 속한 모든 멤버를 users 테이블과 LEFT JOIN해 [ProjectMemberView] 목록으로 반환한다.
     *
     * LEFT JOIN을 사용하므로 users 행이 없는 orphan 멤버십도 포함되며,
     * 해당 멤버의 [ProjectMemberView.displayName]과 [ProjectMemberView.username]은 null이다.
     *
     * @param projectId 조회할 프로젝트 ID
     * @return 멤버가 없으면 빈 목록
     */
    fun listMemberViewsByProject(projectId: UUID): List<ProjectMemberView>

    /**
     * 특정 프로젝트+사용자 조합을 users 테이블과 LEFT JOIN해 [ProjectMemberView]로 반환한다.
     *
     * POST/PATCH 응답용 단건 조회에 사용한다.
     * LEFT JOIN을 사용하므로 users 행이 없어도 멤버십이 있으면 반환하며,
     * [ProjectMemberView.displayName]과 [ProjectMemberView.username]은 null이다.
     *
     * @param projectId 대상 프로젝트 ID
     * @param userId 대상 사용자 ID
     * @return 멤버십이 존재하면 [ProjectMemberView], 없으면 null
     */
    fun findMemberView(projectId: UUID, userId: UUID): ProjectMemberView?
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
@Suppress("TooManyFunctions")
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
    override fun listProjectIdsByUser(userId: UUID): List<UUID> =
        jdbc.query(
            SQL_LIST_PROJECT_IDS_BY_USER,
            mapOf("userId" to userId),
            ProjectIdRowMapper,
        )

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
     * UUID의 mostSignificantBits xor leastSignificantBits로 단일 bigint 키를 생성한다.
     * PostgreSQL `pg_advisory_xact_lock(bigint)` 단일-bigint 시그니처를 사용한다.
     * 2-bigint 버전(`pg_advisory_xact_lock(bigint, bigint)`)은 존재하지 않는다.
     *
     * `SELECT pg_advisory_xact_lock(...)` 는 void를 반환하지 않고 행을 반환하므로
     * jdbc.update()가 "A result was returned when none was expected" 오류를 낸다.
     * jdbcTemplate.execute(PreparedStatementCallback)으로 실행하여 결과를 무시한다.
     * 문자열 결합 없이 PreparedStatement ? 바인딩으로 SQL 인젝션을 원천 차단한다 (DEVELOPMENT.md §1.3).
     * 트랜잭션 커밋/롤백 시 PostgreSQL이 자동으로 락을 해제한다.
     */
    override fun acquireProjectLock(projectId: UUID) {
        val key = projectId.mostSignificantBits xor projectId.leastSignificantBits
        jdbc.jdbcTemplate.execute(SQL_ADVISORY_LOCK) { ps ->
            ps.setLong(1, key)
            ps.execute()
        }
    }

    /**
     * 프로젝트 멤버 전체를 users LEFT JOIN으로 [ProjectMemberView] 목록으로 반환한다.
     *
     * LEFT JOIN이므로 users 행이 없는 orphan 멤버십도 포함된다.
     */
    @Transactional(readOnly = true)
    override fun listMemberViewsByProject(projectId: UUID): List<ProjectMemberView> =
        jdbc.query(
            SQL_LIST_MEMBER_VIEWS_BY_PROJECT,
            mapOf("projectId" to projectId),
            MemberViewRowMapper,
        )

    /**
     * 특정 프로젝트+사용자 조합을 users LEFT JOIN으로 [ProjectMemberView]로 반환한다.
     *
     * LEFT JOIN이므로 users 행이 없어도 멤버십이 존재하면 반환한다.
     */
    @Transactional(readOnly = true)
    override fun findMemberView(projectId: UUID, userId: UUID): ProjectMemberView? =
        jdbc.query(
            SQL_FIND_MEMBER_VIEW,
            mapOf("projectId" to projectId, "userId" to userId),
            MemberViewRowMapper,
        ).firstOrNull()

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

        /**
         * 사용자가 멤버로 속한 프로젝트 id 목록 — MFA 강제 정책의 '민감 프로젝트 소속' 판정용.
         * project_memberships 는 hard delete 라 soft-delete 필터가 불필요하다 (ADR D6).
         */
        const val SQL_LIST_PROJECT_IDS_BY_USER = """
            SELECT project_id
            FROM project_memberships
            WHERE user_id = :userId
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
         * 프로젝트 UUID를 단일 bigint 키로 변환하여 advisory 트랜잭션 락을 획득한다.
         * ? = mostSignificantBits xor leastSignificantBits — bigint 단일 파라미터.
         *
         * PostgreSQL pg_advisory_xact_lock 은 단일 bigint 또는 두 int4 시그니처만 존재한다.
         * 두 bigint 시그니처는 없으므로 hi xor lo 로 단일 bigint 키를 생성한다.
         * SELECT 가 결과를 반환하므로 jdbcTemplate.execute(PreparedStatementCallback) 으로 실행한다.
         * NamedParameterJdbcTemplate.update() 는 "A result was returned when none was expected" 오류를 낸다.
         */
        const val SQL_ADVISORY_LOCK = "SELECT pg_advisory_xact_lock(?)"

        /**
         * MemberView 조회에 공통으로 사용하는 SELECT + FROM + LEFT JOIN 절.
         *
         * [SQL_LIST_MEMBER_VIEWS_BY_PROJECT]와 [SQL_FIND_MEMBER_VIEW]가 컬럼 목록을 공유하므로
         * 중복을 제거한다. 구체적인 WHERE 조건은 각 상수에서 이어붙인다.
         *
         * LEFT JOIN — users 행이 없는 orphan 멤버십도 결과에 포함.
         * u.display_name / u.username 은 users 행이 없을 경우 null.
         */
        const val SQL_MEMBER_VIEW_BASE = """
            SELECT m.project_id,
                   m.user_id,
                   m.role,
                   m.created_at,
                   m.updated_at,
                   u.display_name,
                   u.username
            FROM   project_memberships m
            LEFT JOIN users u ON m.user_id = u.id
        """

        /** 프로젝트 멤버 전체 MemberView 목록 조회. */
        const val SQL_LIST_MEMBER_VIEWS_BY_PROJECT = "$SQL_MEMBER_VIEW_BASE WHERE m.project_id = :projectId"

        /** 특정 프로젝트+사용자 MemberView 단건 조회. */
        const val SQL_FIND_MEMBER_VIEW = "$SQL_MEMBER_VIEW_BASE WHERE m.project_id = :projectId AND m.user_id = :userId"
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

/**
 * project_memberships LEFT JOIN users RowMapper — ResultSet → ProjectMemberView 변환.
 *
 * users 컬럼(display_name, username)은 LEFT JOIN 결과이므로 null 가능.
 * getString 반환값이 null이면 Kotlin nullable String?에 그대로 저장된다.
 */
private object MemberViewRowMapper : RowMapper<ProjectMemberView> {
    override fun mapRow(rs: ResultSet, rowNum: Int): ProjectMemberView =
        ProjectMemberView(
            projectId = rs.getObject("project_id", UUID::class.java),
            userId = rs.getObject("user_id", UUID::class.java),
            role = ProjectRole.from(rs.getString("role")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            displayName = rs.getString("display_name"),
            username = rs.getString("username"),
        )
}

/** project_id 단일 컬럼 RowMapper — listProjectIdsByUser 용 (MFA 강제 정책 멤버십 조회). */
private object ProjectIdRowMapper : RowMapper<UUID> {
    override fun mapRow(rs: ResultSet, rowNum: Int): UUID = rs.getObject("project_id", UUID::class.java)
}
