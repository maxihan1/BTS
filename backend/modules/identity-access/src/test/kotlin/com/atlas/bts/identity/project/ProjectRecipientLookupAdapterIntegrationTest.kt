// ProjectRecipientLookupAdapter 통합 테스트 — projectKey로 멤버/관리자 수신자 조회 검증 (FR-NT-03 Task 5)

package com.atlas.bts.identity.project

import com.atlas.bts.identity.support.SharedPostgres
import com.bts.shared.issue.ProjectRecipientLookupPort
import com.bts.shared.issue.ProjectRecipients
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
import java.util.UUID

/**
 * [ProjectRecipientLookupAdapter] Testcontainers 통합 테스트 (FR-NT-03 Task 5).
 *
 * ## 테스트 환경
 * - `@JdbcTest` + Testcontainers PostgreSQL 16 — Flyway V001~V007 자동 적용.
 * - projects 테이블은 issue-tracking 소유이므로 [setUp]에서 직접 생성한다.
 * - project_memberships / users FK 충족을 위해 [setUp]에서 사용자 행을 사전 삽입한다.
 *
 * ## 검증 시나리오
 * | 케이스 | 조건 | 기대값 |
 * |---|---|---|
 * | T1 | 멤버 N명 + 관리자 M명 존재 | memberIds / adminIds 정확히 반환 |
 * | T2 | 멤버만 존재, 관리자 없음 | adminIds 빈 목록 |
 * | T3 | 존재하지 않는 projectKey | ProjectRecipients.empty() |
 * | T4 | 소프트삭제된 project key | ProjectRecipients.empty() |
 * | T5 | 멤버가 없는 프로젝트 | 두 목록 모두 빈 목록 |
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    JdbcProjectMembershipRepository::class,
    JdbcProjectDirectory::class,
    ProjectRecipientLookupAdapter::class,
)
class ProjectRecipientLookupAdapterIntegrationTest {
    companion object {
        /**
         * 공용 컨테이너의 템플릿 DB 를 복제한 전용 데이터베이스.
         *
         * 격리는 그대로이고 컨테이너 기동과 마이그레이션 재적용만 사라진다.
         * 근거와 주의점은 [com.atlas.bts.identity.support.SharedPostgres] 헤더.
         */
        @JvmStatic
        val postgres = SharedPostgres.freshDatabase()

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            // 템플릿 DB 에서 이미 적용됐다 — 여기서 다시 돌리면 이 최적화가 무의미해진다
            r.add("spring.flyway.enabled") { "false" }
            // 공용 컨테이너라 커넥션 한도도 공유한다. context 캐시가 쌓이면 기본 풀(10)로는
            // max_connections 를 넘긴다 — SharedPostgres 헤더 참조.
            r.add("spring.datasource.hikari.maximum-pool-size") { SharedPostgres.MAX_POOL_SIZE }
        }
    }

    @Autowired
    private lateinit var adapter: ProjectRecipientLookupPort

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private val adminId1 = UUID.randomUUID()
    private val memberId1 = UUID.randomUUID()
    private val memberId2 = UUID.randomUUID()
    private val projectId = UUID.randomUUID()
    private val projectKey = "TPNT"

    @BeforeEach
    fun setUp() {
        // FK 순서 준수: project_memberships → users / projects 순으로 삭제
        jdbc.update("DELETE FROM project_memberships", emptyMap<String, Any>())
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        // projects 테이블은 issue-tracking 소유 — identity-access Flyway 에 없으므로 직접 생성
        jdbc.jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS projects (
                id UUID PRIMARY KEY,
                key VARCHAR(10) UNIQUE,
                deleted_at TIMESTAMPTZ
            )
            """.trimIndent(),
        )
        jdbc.update("DELETE FROM projects", emptyMap<String, Any>())

        // 테스트용 사용자 사전 삽입 (project_memberships → users FK 충족)
        listOf(
            Triple(adminId1, "admin1", "Admin One"),
            Triple(memberId1, "member1", "Member One"),
            Triple(memberId2, "member2", "Member Two"),
        ).forEach { (id, username, displayName) ->
            jdbc.update(
                """
                INSERT INTO users (id, username, display_name)
                VALUES (:id, :username, :displayName)
                """,
                mapOf("id" to id, "username" to username, "displayName" to displayName),
            )
        }

        // 활성 프로젝트 삽입
        jdbc.update(
            "INSERT INTO projects (id, key, deleted_at) VALUES (:id, :key, NULL)",
            mapOf("id" to projectId, "key" to projectKey),
        )
    }

    // ── T1: 멤버 N명 + 관리자 M명 ───────────────────────────────────────────────

    @Test
    fun `T1 - 멤버와 관리자 모두 있는 프로젝트 - memberIds와 adminIds를 정확히 반환한다`() {
        jdbc.update(
            "INSERT INTO project_memberships (project_id, user_id, role) VALUES (:p, :u, :r)",
            mapOf("p" to projectId, "u" to adminId1, "r" to "PROJECT_ADMIN"),
        )
        jdbc.update(
            "INSERT INTO project_memberships (project_id, user_id, role) VALUES (:p, :u, :r)",
            mapOf("p" to projectId, "u" to memberId1, "r" to "MEMBER"),
        )
        jdbc.update(
            "INSERT INTO project_memberships (project_id, user_id, role) VALUES (:p, :u, :r)",
            mapOf("p" to projectId, "u" to memberId2, "r" to "MEMBER"),
        )

        val result = adapter.findProjectRecipients(projectKey)

        assertThat(result.adminIds).containsExactlyInAnyOrder(adminId1)
        assertThat(result.memberIds).containsExactlyInAnyOrder(adminId1, memberId1, memberId2)
    }

    // ── T2: 멤버만 있고 관리자 없음 ──────────────────────────────────────────────

    @Test
    fun `T2 - MEMBER 역할만 있는 프로젝트 - adminIds는 빈 목록을 반환한다`() {
        jdbc.update(
            "INSERT INTO project_memberships (project_id, user_id, role) VALUES (:p, :u, :r)",
            mapOf("p" to projectId, "u" to memberId1, "r" to "MEMBER"),
        )

        val result = adapter.findProjectRecipients(projectKey)

        assertThat(result.adminIds).isEmpty()
        assertThat(result.memberIds).containsExactly(memberId1)
    }

    // ── T3: 존재하지 않는 projectKey ─────────────────────────────────────────────

    @Test
    fun `T3 - 존재하지 않는 projectKey - ProjectRecipients empty를 반환한다`() {
        val result = adapter.findProjectRecipients("GHOST")

        assertThat(result).isEqualTo(ProjectRecipients.empty())
    }

    // ── T4: 소프트삭제된 project ──────────────────────────────────────────────────

    @Test
    fun `T4 - 소프트삭제된 프로젝트 key - ProjectRecipients empty를 반환한다`() {
        jdbc.update(
            "UPDATE projects SET deleted_at = NOW() WHERE key = :key",
            mapOf("key" to projectKey),
        )

        val result = adapter.findProjectRecipients(projectKey)

        assertThat(result).isEqualTo(ProjectRecipients.empty())
    }

    // ── T5: 멤버가 없는 프로젝트 ─────────────────────────────────────────────────

    @Test
    fun `T5 - 멤버가 없는 프로젝트 - 두 목록 모두 빈 목록을 반환한다`() {
        val result = adapter.findProjectRecipients(projectKey)

        assertThat(result.memberIds).isEmpty()
        assertThat(result.adminIds).isEmpty()
    }
}
