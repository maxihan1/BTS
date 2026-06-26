// ProjectMembershipAdapter 통합 테스트 — 멤버 프로젝트 키 집합 반환·소프트삭제 제외·미멤버 빈 Set(fail-closed)·키 원형 일치 검증

package com.atlas.bts.identity.project

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * ProjectMembershipAdapter 통합 테스트 (FR-SR-03 PR2 Task 5).
 *
 * ## 목적
 * shared-kernel [com.bts.shared.membership.ProjectMembershipPort] 의 identity-access 구현체가
 * project_memberships(identity-access) 와 projects(issue-tracking)를 JOIN 해
 * 사용자가 멤버로 속한 프로젝트의 **키 집합**을 반환하는지 실제 PostgreSQL 로 검증한다.
 *
 * ## 테스트 환경
 * - `@JdbcTest` — DataSource + JdbcTemplate 슬라이스만 로드. `@Import` 로 어댑터만 등록한다.
 * - Testcontainers PostgreSQL 16 — Flyway 마이그레이션 자동 적용 (project_memberships/users 생성).
 * - projects 테이블은 issue-tracking 소유라 identity-access Flyway 에 없으므로
 *   [setUp] 에서 직접 생성한다 (JdbcProjectDirectoryIntegrationTest 동일 패턴).
 *
 * ## 검증 시나리오
 * | 케이스 | 조건 | 기대값 |
 * |---|---|---|
 * | (a) 멤버 키 집합 | 멤버 프로젝트 2개 + 타 사용자 프로젝트 1개 | 본인 멤버 키 2개만 |
 * | (b) 소프트삭제 제외 | 멤버이나 deleted_at 설정됨 | 해당 키 제외 |
 * | (c) 미멤버 fail-closed | 멤버십 0개 | 빈 Set |
 * | (d) 키 원형 일치 | key="ATLAS" | 정확히 "ATLAS" (접두사/변환 없음) |
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProjectMembershipAdapter::class)
@Testcontainers
class ProjectMembershipAdapterTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            r.add("spring.flyway.enabled") { "true" }
        }
    }

    @Autowired
    private lateinit var adapter: ProjectMembershipAdapter

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    /** 멤버십을 가질 대상 사용자 */
    private val memberUserId = UUID.randomUUID()

    /** 멤버십을 가지지 않거나 타 프로젝트에만 속한 대조군 사용자 */
    private val otherUserId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        // projects 테이블은 issue-tracking 소유 — identity-access Flyway 에 없으므로 직접 생성한다.
        // id/key/deleted_at 세 컬럼만 의존 (ADR D2: cross-BC 는 최소 컬럼만 의존).
        jdbc.jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS projects (
                id UUID PRIMARY KEY,
                key VARCHAR(10) UNIQUE,
                deleted_at TIMESTAMPTZ
            )
            """.trimIndent(),
        )

        // FK 순서 준수: project_memberships(user_id → users) → users 순으로 정리.
        jdbc.update("DELETE FROM project_memberships", emptyMap<String, Any>())
        jdbc.update("DELETE FROM projects", emptyMap<String, Any>())
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        // project_memberships.user_id → users.id FK 충족.
        insertUser(memberUserId, "member", "Member User")
        insertUser(otherUserId, "other", "Other User")
    }

    @Test
    fun `projectKeysOf — 사용자가 멤버인 프로젝트 키만 반환하고 타 사용자 프로젝트는 제외한다`() {
        val atlas = insertProject(key = "ATLAS")
        val bts = insertProject(key = "BTS")
        val foreign = insertProject(key = "OTHER")

        insertMembership(atlas, memberUserId)
        insertMembership(bts, memberUserId)
        insertMembership(foreign, otherUserId) // 타 사용자 멤버십 — 노출되면 안 됨

        assertThat(adapter.projectKeysOf(memberUserId))
            .containsExactlyInAnyOrder("ATLAS", "BTS")
    }

    @Test
    fun `projectKeysOf — 소프트삭제(deleted_at)된 프로젝트는 제외한다`() {
        val active = insertProject(key = "ATLAS")
        val deleted = insertProject(key = "GONE", deleted = true)

        insertMembership(active, memberUserId)
        insertMembership(deleted, memberUserId)

        assertThat(adapter.projectKeysOf(memberUserId))
            .containsExactlyInAnyOrder("ATLAS")
    }

    @Test
    fun `projectKeysOf — 멤버십이 없는 사용자는 빈 Set을 반환한다 (fail-closed)`() {
        // 프로젝트는 존재하지만 memberUserId 의 멤버십은 전혀 없다.
        val atlas = insertProject(key = "ATLAS")
        insertMembership(atlas, otherUserId)

        assertThat(adapter.projectKeysOf(memberUserId)).isEmpty()
    }

    @Test
    fun `projectKeysOf — 반환 형식은 projects_key 원형과 정확히 일치한다 (C2)`() {
        // saved_filter_shares.target_id(PROJECT) 저장 형식과 일치해야 한다 — 접두사/변환 없는 원형 키.
        val atlas = insertProject(key = "ATLAS")
        insertMembership(atlas, memberUserId)

        val keys = adapter.projectKeysOf(memberUserId)

        assertThat(keys).containsExactlyInAnyOrder("ATLAS")
        assertThat(keys.single()).isEqualTo("ATLAS")
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────

    private fun insertUser(id: UUID, username: String, displayName: String) {
        jdbc.update(
            "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)",
            mapOf("id" to id, "username" to username, "displayName" to displayName),
        )
    }

    /** projects 행을 삽입하고 생성한 UUID 를 반환한다. deleted=true 면 deleted_at=NOW(). */
    private fun insertProject(key: String, deleted: Boolean = false): UUID {
        val id = UUID.randomUUID()
        // 데이터(id/key)는 바인딩하고, deleted_at 만 고정 SQL 상수 두 갈래로 선택한다 (문자열 결합 없음).
        val sql = if (deleted) {
            "INSERT INTO projects (id, key, deleted_at) VALUES (:id, :key, NOW())"
        } else {
            "INSERT INTO projects (id, key, deleted_at) VALUES (:id, :key, NULL)"
        }
        jdbc.update(sql, mapOf("id" to id, "key" to key))
        return id
    }

    private fun insertMembership(projectId: UUID, userId: UUID) {
        jdbc.update(
            "INSERT INTO project_memberships (project_id, user_id, role) VALUES (:projectId, :userId, :role)",
            mapOf("projectId" to projectId, "userId" to userId, "role" to "MEMBER"),
        )
    }
}
