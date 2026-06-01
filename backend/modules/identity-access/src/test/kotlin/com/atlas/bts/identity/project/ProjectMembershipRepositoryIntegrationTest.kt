// ProjectMembershipRepository 통합 테스트 — CRUD + 카운트 + 역할갱신 + 중복제약 검증 (FR-PM-01 Task 3)

package com.atlas.bts.identity.project

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * JdbcProjectMembershipRepository 통합 테스트 (FR-PM-01 Task 3).
 *
 * ## 테스트 환경
 * - `@JdbcTest` + Testcontainers PostgreSQL 16 — Flyway V001~V007 자동 적용.
 * - users FK 충족을 위해 [setUp]에서 테스트 사용자 2명을 사전 삽입한다.
 *
 * ## 검증 시나리오
 * | 메서드 | 케이스 |
 * |---|---|
 * | save | 신규 삽입 후 반환값 검증 |
 * | save | UNIQUE(project_id,user_id) 위반 시 DataIntegrityViolationException |
 * | findByProjectAndUser | 존재하는 멤버십 반환 |
 * | findByProjectAndUser | 없는 멤버십 → null |
 * | listByProject | 프로젝트 멤버 목록 반환 |
 * | countByProject | 멤버 수 반환 |
 * | countAdminsByProject | PROJECT_ADMIN 역할 수만 반환 |
 * | updateRole | 역할 변경 + updated_at 갱신 |
 * | updateRole | 존재하지 않는 멤버십 → null |
 * | deleteByProjectAndUser | 삭제 성공 → true |
 * | deleteByProjectAndUser | 없는 멤버십 삭제 → false |
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcProjectMembershipRepository::class)
@Testcontainers
class ProjectMembershipRepositoryIntegrationTest {

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
    private lateinit var repo: ProjectMembershipRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    /** 테스트마다 재사용할 고정 사용자 ID */
    private val userId1 = UUID.randomUUID()
    private val userId2 = UUID.randomUUID()

    /** 테스트 픽스처 프로젝트 ID — cross-BC이므로 users FK 없음, UUID만 사용 */
    private val projectId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        // FK 순서 준수: project_memberships → users 순으로 삭제
        jdbc.update("DELETE FROM project_memberships", emptyMap<String, Any>())
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        // users.id FK 충족을 위해 테스트 사용자 사전 삽입
        jdbc.update(
            """
            INSERT INTO users (id, username, display_name)
            VALUES (:id, :username, :displayName)
            """,
            mapOf("id" to userId1, "username" to "user1", "displayName" to "User One"),
        )
        jdbc.update(
            """
            INSERT INTO users (id, username, display_name)
            VALUES (:id, :username, :displayName)
            """,
            mapOf("id" to userId2, "username" to "user2", "displayName" to "User Two"),
        )
    }

    // ── save ─────────────────────────────────────────────────────────────────

    @Test
    fun `save — 신규 멤버십 삽입 후 projectId, userId, role이 반환된다`() {
        val membership = ProjectMembership(
            projectId = projectId,
            userId = userId1,
            role = ProjectRole.MEMBER,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
        )

        val saved = repo.save(membership)

        assertThat(saved.projectId).isEqualTo(projectId)
        assertThat(saved.userId).isEqualTo(userId1)
        assertThat(saved.role).isEqualTo(ProjectRole.MEMBER)
        assertThat(saved.createdAt).isNotNull()
        assertThat(saved.updatedAt).isNotNull()
    }

    @Test
    fun `save — UNIQUE(project_id,user_id) 위반 시 DataIntegrityViolationException 발생`() {
        val membership = ProjectMembership(
            projectId = projectId,
            userId = userId1,
            role = ProjectRole.MEMBER,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
        )
        repo.save(membership)

        val duplicate = ProjectMembership(
            projectId = projectId,
            userId = userId1,
            role = ProjectRole.PROJECT_ADMIN,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
        )

        assertThatThrownBy { repo.save(duplicate) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    // ── findByProjectAndUser ──────────────────────────────────────────────────

    @Test
    fun `findByProjectAndUser — 존재하는 멤버십 조회 시 ProjectMembership 반환`() {
        val membership = ProjectMembership(
            projectId = projectId,
            userId = userId1,
            role = ProjectRole.PROJECT_ADMIN,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
        )
        repo.save(membership)

        val found = repo.findByProjectAndUser(projectId, userId1)

        assertThat(found).isNotNull()
        assertThat(found!!.projectId).isEqualTo(projectId)
        assertThat(found.userId).isEqualTo(userId1)
        assertThat(found.role).isEqualTo(ProjectRole.PROJECT_ADMIN)
    }

    @Test
    fun `findByProjectAndUser — 존재하지 않는 멤버십 조회 시 null 반환`() {
        val result = repo.findByProjectAndUser(projectId, UUID.randomUUID())

        assertThat(result).isNull()
    }

    // ── listByProject ─────────────────────────────────────────────────────────

    @Test
    fun `listByProject — 프로젝트 멤버 전체 목록을 반환한다`() {
        repo.save(ProjectMembership(projectId, userId1, ProjectRole.PROJECT_ADMIN, java.time.Instant.now(), java.time.Instant.now()))
        repo.save(ProjectMembership(projectId, userId2, ProjectRole.MEMBER, java.time.Instant.now(), java.time.Instant.now()))

        val list = repo.listByProject(projectId)

        assertThat(list).hasSize(2)
        assertThat(list.map { it.userId }).containsExactlyInAnyOrder(userId1, userId2)
    }

    @Test
    fun `listByProject — 멤버가 없는 프로젝트는 빈 목록을 반환한다`() {
        val result = repo.listByProject(UUID.randomUUID())

        assertThat(result).isEmpty()
    }

    // ── countByProject ────────────────────────────────────────────────────────

    @Test
    fun `countByProject — 프로젝트 전체 멤버 수를 반환한다`() {
        repo.save(ProjectMembership(projectId, userId1, ProjectRole.PROJECT_ADMIN, java.time.Instant.now(), java.time.Instant.now()))
        repo.save(ProjectMembership(projectId, userId2, ProjectRole.MEMBER, java.time.Instant.now(), java.time.Instant.now()))

        assertThat(repo.countByProject(projectId)).isEqualTo(2)
    }

    @Test
    fun `countByProject — 멤버 없는 프로젝트는 0을 반환한다`() {
        assertThat(repo.countByProject(UUID.randomUUID())).isEqualTo(0)
    }

    // ── countAdminsByProject ──────────────────────────────────────────────────

    @Test
    fun `countAdminsByProject — PROJECT_ADMIN 역할 수만 반환한다`() {
        repo.save(ProjectMembership(projectId, userId1, ProjectRole.PROJECT_ADMIN, java.time.Instant.now(), java.time.Instant.now()))
        repo.save(ProjectMembership(projectId, userId2, ProjectRole.MEMBER, java.time.Instant.now(), java.time.Instant.now()))

        assertThat(repo.countAdminsByProject(projectId)).isEqualTo(1)
    }

    @Test
    fun `countAdminsByProject — 어드민이 없으면 0을 반환한다`() {
        repo.save(ProjectMembership(projectId, userId1, ProjectRole.MEMBER, java.time.Instant.now(), java.time.Instant.now()))

        assertThat(repo.countAdminsByProject(projectId)).isEqualTo(0)
    }

    // ── updateRole ────────────────────────────────────────────────────────────

    @Test
    fun `updateRole — 역할 변경 시 변경된 ProjectMembership을 반환하고 updated_at이 갱신된다`() {
        repo.save(ProjectMembership(projectId, userId1, ProjectRole.MEMBER, java.time.Instant.now(), java.time.Instant.now()))
        val originalUpdatedAt = repo.findByProjectAndUser(projectId, userId1)!!.updatedAt

        // updated_at 비교를 위해 1ms 대기
        Thread.sleep(1)
        val updated = repo.updateRole(projectId, userId1, ProjectRole.PROJECT_ADMIN)

        assertThat(updated).isNotNull()
        assertThat(updated!!.role).isEqualTo(ProjectRole.PROJECT_ADMIN)
        assertThat(updated.updatedAt).isAfter(originalUpdatedAt)
    }

    @Test
    fun `updateRole — 존재하지 않는 멤버십 갱신 시 null 반환`() {
        val result = repo.updateRole(projectId, UUID.randomUUID(), ProjectRole.PROJECT_ADMIN)

        assertThat(result).isNull()
    }

    // ── deleteByProjectAndUser ────────────────────────────────────────────────

    @Test
    fun `deleteByProjectAndUser — 존재하는 멤버십 삭제 시 true 반환`() {
        repo.save(ProjectMembership(projectId, userId1, ProjectRole.MEMBER, java.time.Instant.now(), java.time.Instant.now()))

        val deleted = repo.deleteByProjectAndUser(projectId, userId1)

        assertThat(deleted).isTrue()
        assertThat(repo.findByProjectAndUser(projectId, userId1)).isNull()
    }

    @Test
    fun `deleteByProjectAndUser — 존재하지 않는 멤버십 삭제 시 false 반환`() {
        val result = repo.deleteByProjectAndUser(projectId, UUID.randomUUID())

        assertThat(result).isFalse()
    }
}
