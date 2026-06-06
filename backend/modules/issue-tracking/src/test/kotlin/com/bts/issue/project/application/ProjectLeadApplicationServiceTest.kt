// ProjectLeadApplicationService Testcontainers 통합 테스트 — 실 DB + MockK UserLookupPort
// S8(지정), S9(해제), 422(미존재 사용자), 404(미존재 프로젝트), findLeadUserId(지정/미지정) 검증

package com.bts.issue.project.application

import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.domain.ProjectLeadNotFoundException
import com.bts.issue.project.domain.ProjectLeadProjectNotFoundException
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.project.repository.ProjectLookupRepository
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.user.UserLookupPort
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.DriverManager
import java.util.UUID

/**
 * ProjectLeadApplicationService Testcontainers 통합 테스트.
 *
 * 실 PostgreSQL(IssueTestcontainersBase singleton container) + Flyway migrate 로
 * projects.lead_user_id 쓰기/읽기를 검증한다.
 * [UserLookupPort] 는 MockK 로 대체하여 identity-access BC 의존 없이 실행된다.
 *
 * 검증 시나리오.
 * - S8. 유효 user UUID 지정 → lead_user_id 저장 + findLeadUserId 조회 일치.
 * - S9. leadUserId=null 해제 → lead_user_id null.
 * - 422: 미존재 user 지정(UserLookupPort.exists=false) → ProjectLeadNotFoundException.
 * - 404: 미존재/소프트삭제 프로젝트 → ProjectLeadProjectNotFoundException.
 * - findLeadUserId: 지정/미지정(null) 반환.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ProjectLeadApplicationServiceTest {
    private lateinit var sut: ProjectLeadApplicationService
    private lateinit var leadRepository: ProjectLeadRepository
    private val userLookupPort: UserLookupPort = mockk()

    /** Testcontainers 에 삽입한 테스트용 활성 프로젝트 UUID. */
    private lateinit var activeProjectId: UUID

    /** 소프트삭제된 프로젝트 UUID — 404 시나리오 검증용. */
    private lateinit var deletedProjectId: UUID

    @BeforeAll
    fun setup() {
        val postgres = IssueTestcontainersBase.postgres

        // Flyway migrate — 멱등, 이미 최신이면 아무 것도 하지 않음
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .placeholderReplacement(false)
            .locations("classpath:db/migration/issue-tracking")
            .load()
            .migrate()

        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)

        leadRepository = ProjectLeadRepository(dsl)
        val lookupRepository = ProjectLookupRepository(dsl)
        val projectLookup = ProjectLookup(lookupRepository)

        sut =
            ProjectLeadApplicationService(
                repository = leadRepository,
                projectLookup = projectLookup,
                userLookupPort = userLookupPort,
            )

        // 테스트용 활성 프로젝트 삽입
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES ('PLEAD', 'Project Lead Test') ON CONFLICT (key) DO NOTHING",
            ).use { it.executeUpdate() }

            conn.prepareStatement("SELECT id FROM projects WHERE key = 'PLEAD'").use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "PLEAD 프로젝트를 찾을 수 없습니다." }
                    activeProjectId = rs.getObject(1) as UUID
                }
            }

            // 소프트삭제 프로젝트 삽입 — key 형식: ^[A-Z][A-Z0-9]{1,9}$
            val deletedInsertSql =
                "INSERT INTO projects (key, name, deleted_at) " +
                    "VALUES ('PLEADDEL', 'Deleted Project', now()) ON CONFLICT (key) DO NOTHING"
            conn.prepareStatement(deletedInsertSql).use { it.executeUpdate() }

            conn.prepareStatement("SELECT id FROM projects WHERE key = 'PLEADDEL'").use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "PLEADDEL 프로젝트를 찾을 수 없습니다." }
                    deletedProjectId = rs.getObject(1) as UUID
                }
            }
        }
    }

    /** 각 테스트 전 lead_user_id 초기화 — 테스트 격리. */
    @BeforeEach
    fun resetLead() {
        val postgres = IssueTestcontainersBase.postgres
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE projects SET lead_user_id = NULL WHERE key IN ('PLEAD')").use {
                it.executeUpdate()
            }
        }
    }

    // ── S8: 유효 user 지정 ────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `S8 유효 사용자 UUID 지정 시 lead_user_id 가 저장되고 findLeadUserId 로 조회된다`() {
        val leadUserId = UUID.randomUUID()
        every { userLookupPort.exists(leadUserId) } returns true

        val result =
            sut.changeLead(
                actorId = UUID.randomUUID(),
                projectIdOrKey = activeProjectId.toString(),
                leadUserId = leadUserId,
            )

        assertThat(result.projectId).isEqualTo(activeProjectId)
        assertThat(result.leadUserId).isEqualTo(leadUserId)

        val stored = leadRepository.findLeadUserId(activeProjectId)
        assertThat(stored).isEqualTo(leadUserId)
    }

    // ── S9: null 해제 ─────────────────────────────────────────────────────────

    @Test
    @Order(2)
    fun `S9 leadUserId null 로 해제 시 lead_user_id 가 null 로 저장된다`() {
        // 먼저 리드 지정
        val leadUserId = UUID.randomUUID()
        every { userLookupPort.exists(leadUserId) } returns true
        sut.changeLead(
            actorId = UUID.randomUUID(),
            projectIdOrKey = activeProjectId.toString(),
            leadUserId = leadUserId,
        )

        // 해제
        val result =
            sut.changeLead(
                actorId = UUID.randomUUID(),
                projectIdOrKey = activeProjectId.toString(),
                leadUserId = null,
            )

        assertThat(result.leadUserId).isNull()
        val stored = leadRepository.findLeadUserId(activeProjectId)
        assertThat(stored).isNull()
    }

    // ── 422: 미존재 사용자 ────────────────────────────────────────────────────

    @Test
    @Order(3)
    fun `미존재 사용자 지정 시 ProjectLeadNotFoundException 이 발생한다`() {
        val unknownUserId = UUID.randomUUID()
        every { userLookupPort.exists(unknownUserId) } returns false

        assertThatThrownBy {
            sut.changeLead(
                actorId = UUID.randomUUID(),
                projectIdOrKey = activeProjectId.toString(),
                leadUserId = unknownUserId,
            )
        }.isInstanceOf(ProjectLeadNotFoundException::class.java)
    }

    // ── 404: 미존재 프로젝트 ──────────────────────────────────────────────────

    @Test
    @Order(4)
    fun `존재하지 않는 projectIdOrKey 로 changeLead 호출 시 ProjectLeadProjectNotFoundException 이 발생한다`() {
        val nonExistentId = UUID.randomUUID().toString()

        assertThatThrownBy {
            sut.changeLead(
                actorId = UUID.randomUUID(),
                projectIdOrKey = nonExistentId,
                leadUserId = null,
            )
        }.isInstanceOf(ProjectLeadProjectNotFoundException::class.java)
    }

    // ── 404: 소프트삭제 프로젝트 ─────────────────────────────────────────────

    @Test
    @Order(5)
    fun `소프트삭제된 프로젝트로 changeLead 호출 시 ProjectLeadProjectNotFoundException 이 발생한다`() {
        assertThatThrownBy {
            sut.changeLead(
                actorId = UUID.randomUUID(),
                projectIdOrKey = deletedProjectId.toString(),
                leadUserId = null,
            )
        }.isInstanceOf(ProjectLeadProjectNotFoundException::class.java)
    }

    // ── findLeadUserId: 지정/미지정 ───────────────────────────────────────────

    @Test
    @Order(6)
    fun `findLeadUserId 는 지정된 leadUserId 를 반환한다`() {
        val leadUserId = UUID.randomUUID()
        every { userLookupPort.exists(leadUserId) } returns true
        sut.changeLead(
            actorId = UUID.randomUUID(),
            projectIdOrKey = activeProjectId.toString(),
            leadUserId = leadUserId,
        )

        val found = leadRepository.findLeadUserId(activeProjectId)
        assertThat(found).isEqualTo(leadUserId)
    }

    @Test
    @Order(7)
    fun `findLeadUserId 는 리드가 없으면 null 을 반환한다`() {
        val found = leadRepository.findLeadUserId(activeProjectId)
        assertThat(found).isNull()
    }
}
