// FR-IS-03 Task 8 — PATCH /issues/{key}/assignee 엔드포인트 통합 테스트

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.shared.user.UserLookupPort
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.DriverManager
import java.util.UUID

/**
 * FR-IS-03 Task 8 — PATCH /api/v1/issues/{key}/assignee 통합 테스트.
 *
 * 실제 Testcontainers PostgreSQL + 전체 스택 위에서 동작한다.
 * [TestConfig] 를 재사용하되, [AssigneeTestConfig] 로 [UserLookupPort] 를 제어 가능한
 * mockk 인스턴스로 오버라이드한다.
 *
 * ## C3 — UserLookupPort test double
 * [UserLookupPort.exists] 는 [KNOWN_USER_ID] 만 true 를 반환하고 그 외는 false.
 * S4(ASSIGNEE_NOT_FOUND 422) 시나리오는 [UNKNOWN_USER_ID] 를 assigneeId 로 전달해 재현한다.
 *
 * ## 검증 시나리오
 * - S1. 미할당 이슈에 assignee 지정 → 200 + assigneeId = 지정값, version + 1
 * - S2. assigneeId = null 로 해제 → 200 + assigneeId 없음
 * - S3. 다른 사용자로 변경 → 200 + assigneeId = 새 값
 * - S4. 존재하지 않는 사용자 → 422 ASSIGNEE_NOT_FOUND
 * - S5. expectedVersion 불일치 → 409 VERSION_CONFLICT
 * - S6. 존재하지 않는 이슈 → 404 ISSUE_NOT_FOUND
 * - (추가) expectedVersion 누락 → 400
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TestConfig::class, IssueControllerAssigneeIntegrationTest.AssigneeTestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueControllerAssigneeIntegrationTest {
    /**
     * UserLookupPort 오버라이드 설정.
     *
     * [TestConfig] 의 `userLookupPort` 를 @Primary 로 교체.
     * [KNOWN_USER_ID] / [ANOTHER_KNOWN_USER_ID] 만 exists=true, 그 외는 false 반환.
     * S4(ASSIGNEE_NOT_FOUND 422) 시나리오는 [UNKNOWN_USER_ID] 전달로 재현한다.
     */
    @Configuration
    open class AssigneeTestConfig {
        @Bean
        @Primary
        open fun userLookupPortForAssigneeTest(): UserLookupPort =
            object : UserLookupPort {
                private val knownIds = setOf(KNOWN_USER_ID, ANOTHER_KNOWN_USER_ID)

                override fun exists(userId: UUID): Boolean = userId in knownIds
            }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        /** UserLookupPort.exists = true 인 기본 할당자 UUID */
        val KNOWN_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")

        /** UserLookupPort.exists = true 인 다른 할당자 UUID (S3용) */
        val ANOTHER_KNOWN_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000011")

        /** UserLookupPort.exists = false 인 미존재 UUID (S4용) */
        val UNKNOWN_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000099")

        private const val PROJECT_KEY = "ASSIGN"
        private var migrated = false
        private var seeded = false
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedProject()
            seeded = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // CurrentActor 결선(FR-PM-06 PR-B) 이후 컨트롤러가 인증 주체를 요구하므로 SecurityContext 를 주입한다.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "11111111-1111-4111-8111-111111111111",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── S1. 미할당 이슈에 assignee 지정 → 200 + assigneeId = 지정값 ────────────

    /**
     * S1 assignee 지정.
     *
     * Given  assignee 없는 이슈 (version=1)
     * When   PATCH /{key}/assignee { assigneeId: KNOWN_USER_ID, expectedVersion: 1 }
     * Then   200 + data.assigneeId = KNOWN_USER_ID, data.version = 2
     */
    @Test
    fun `S1 미할당 이슈에 assignee 지정 - 200 및 assigneeId와 version 증가 확인`() {
        val key = insertIssue("S1 담당자 지정 이슈")

        val body =
            mapOf(
                "assigneeId" to KNOWN_USER_ID.toString(),
                "expectedVersion" to 1L,
            )

        mockMvc.perform(
            patch("/api/v1/issues/$key/assignee")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.assigneeId").value(KNOWN_USER_ID.toString()))
    }

    // ── S2. assigneeId = null 로 해제 → 200 + assigneeId 없음 ─────────────────

    /**
     * S2 assignee 해제.
     *
     * Given  이슈에 assignee 지정 후 (version=2)
     * When   PATCH /{key}/assignee { assigneeId: null, expectedVersion: 2 }
     * Then   200 + data.assigneeId 없음(null)
     */
    @Test
    fun `S2 assigneeId null로 해제 - 200 및 assigneeId 없음 확인`() {
        val key = insertIssue("S2 담당자 해제 이슈")

        // 먼저 assignee 지정
        val assignBody =
            mapOf(
                "assigneeId" to KNOWN_USER_ID.toString(),
                "expectedVersion" to 1L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key/assignee")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(assignBody)),
        ).andExpect(status().isOk)

        // null 로 해제
        val releaseBody =
            mapOf(
                "assigneeId" to null,
                "expectedVersion" to 2L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key/assignee")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(releaseBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.assigneeId").doesNotExist())
    }

    // ── S3. 다른 사용자로 변경 → 200 ─────────────────────────────────────────

    /**
     * S3 담당자 변경.
     *
     * Given  이슈에 KNOWN_USER_ID 지정 후 (version=2)
     * When   PATCH /{key}/assignee { assigneeId: ANOTHER_KNOWN_USER_ID, expectedVersion: 2 }
     * Then   200 + data.assigneeId = ANOTHER_KNOWN_USER_ID
     */
    @Test
    fun `S3 다른 사용자로 담당자 변경 - 200 및 새 assigneeId 확인`() {
        val key = insertIssue("S3 담당자 변경 이슈")

        // 초기 assignee 지정
        val firstBody =
            mapOf(
                "assigneeId" to KNOWN_USER_ID.toString(),
                "expectedVersion" to 1L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key/assignee")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(firstBody)),
        ).andExpect(status().isOk)

        // 다른 사용자로 변경
        val changeBody =
            mapOf(
                "assigneeId" to ANOTHER_KNOWN_USER_ID.toString(),
                "expectedVersion" to 2L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key/assignee")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(changeBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.assigneeId").value(ANOTHER_KNOWN_USER_ID.toString()))
    }

    // ── S4. 존재하지 않는 사용자 → 422 ASSIGNEE_NOT_FOUND ─────────────────────

    /**
     * S4 미존재 사용자 → 422.
     *
     * Given  이슈 1건 (version=1)
     * When   PATCH /{key}/assignee { assigneeId: UNKNOWN_USER_ID, expectedVersion: 1 }
     * Then   422 + errorCode = ASSIGNEE_NOT_FOUND
     */
    @Test
    fun `S4 존재하지 않는 사용자 assignee 지정 - 422 ASSIGNEE_NOT_FOUND`() {
        val key = insertIssue("S4 미존재 사용자 검증 이슈")

        val body =
            mapOf(
                "assigneeId" to UNKNOWN_USER_ID.toString(),
                "expectedVersion" to 1L,
            )

        mockMvc.perform(
            patch("/api/v1/issues/$key/assignee")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("ASSIGNEE_NOT_FOUND"))
    }

    // ── S5. expectedVersion 불일치 → 409 VERSION_CONFLICT ─────────────────────

    /**
     * S5 OCC 충돌.
     *
     * Given  이슈 version=1, PATCH expectedVersion=99
     * Then   409 + errorCode = VERSION_CONFLICT
     */
    @Test
    fun `S5 expectedVersion 불일치 - 409 VERSION_CONFLICT`() {
        val key = insertIssue("S5 OCC 충돌 검증 이슈")

        val body =
            mapOf(
                "assigneeId" to KNOWN_USER_ID.toString(),
                "expectedVersion" to 99L,
            )

        mockMvc.perform(
            patch("/api/v1/issues/$key/assignee")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))
    }

    // ── S6. 미존재 이슈 → 404 ISSUE_NOT_FOUND ─────────────────────────────────

    /**
     * S6 미존재 이슈.
     *
     * Given  존재하지 않는 이슈 키 "ASSIGN-99999"
     * When   PATCH /ASSIGN-99999/assignee { assigneeId: KNOWN_USER_ID, expectedVersion: 1 }
     * Then   404 + errorCode = ISSUE_NOT_FOUND
     */
    @Test
    fun `S6 존재하지 않는 이슈 - 404 ISSUE_NOT_FOUND`() {
        val body =
            mapOf(
                "assigneeId" to KNOWN_USER_ID.toString(),
                "expectedVersion" to 1L,
            )

        mockMvc.perform(
            patch("/api/v1/issues/ASSIGN-99999/assignee")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    // ── 추가. expectedVersion 누락 → 400 ──────────────────────────────────────

    /**
     * expectedVersion 누락 → 400 VALIDATION_FAILED.
     *
     * Given  요청 바디에 expectedVersion 없음
     * Then   400 + errorCode = VALIDATION_FAILED
     */
    @Test
    fun `expectedVersion 누락 시 400 VALIDATION_FAILED`() {
        val key = insertIssue("expectedVersion 누락 검증 이슈")

        val body =
            mapOf(
                "assigneeId" to KNOWN_USER_ID.toString(),
                // expectedVersion 누락
            )

        mockMvc.perform(
            patch("/api/v1/issues/$key/assignee")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun applyMigrations() {
        Flyway.configure()
            .dataSource(
                TestConfig.postgres.jdbcUrl,
                TestConfig.postgres.username,
                TestConfig.postgres.password,
            )
            .placeholderReplacement(false)
            .locations(
                "classpath:db/migration/issue-tracking",
                "classpath:db/migration/project-workflow",
            )
            .load()
            .migrate()
    }

    private fun seedProject() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Assignee Integration Test Project")
                stmt.executeUpdate()
            }
        }
    }

    private fun insertIssue(summary: String): String {
        conn().use { conn ->
            conn.autoCommit = false

            val seq =
                conn.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { stmt ->
                    stmt.setString(1, PROJECT_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            val issueKey = "$PROJECT_KEY-$seq"

            val projectId =
                conn.prepareStatement(
                    "SELECT id FROM projects WHERE key = ?",
                ).use { stmt ->
                    stmt.setString(1, PROJECT_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            val taskTypeId =
                conn.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입 없음 — V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            conn.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                    "VALUES (?, ?, ?, ?, ?, 1, ?)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, summary)
                stmt.setObject(4, UUID.fromString("00000000-0000-0000-0000-000000000001"))
                stmt.setString(5, "open")
                stmt.setLong(6, taskTypeId)
                stmt.executeUpdate()
            }

            conn.commit()
            return issueKey
        }
    }

    private fun conn() =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
