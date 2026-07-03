// IssueAttachmentService.upload 의 createdAt/uploadedBy 주입 통합 테스트 — import 원본 메타 보존 검증 (FR-IM-01 PR4 Task 2)

package com.bts.issue.attachment.application

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.attachment.IssueAttachmentIntegrationTest
import com.bts.issue.attachment.repository.AttachmentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import java.io.ByteArrayInputStream
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * [IssueAttachmentService.upload] 의 `createdAt`/`uploadedBy` 주입 파라미터 통합 테스트.
 *
 * FR-IM-01 PR4(Import — 첨부/이력) Task 2. Import worker 가 Jira 원본 첨부의 업로드 시각·업로더를
 * 보존해야 하므로, `upload` 가 두 값을 주입받으면 clock/actor 대신 주입값을 사용하고,
 * 주입하지 않으면(생략/null) 기존 동작(clock.instant()/actor.value)으로 폴백하는지 검증한다.
 *
 * [IssueAttachmentIntegrationTest.AttachmentTestConfig] 의 명시 `@Bean` 조립을 그대로 재사용한다
 * (`@SpringBootTest` 아님 — memory `fr-ac-01-d2-clamav-done` B1: 서비스 생성자에 파라미터가
 * 추가되지 않는 한 조립 변경은 불필요하다). 동일 컨텍스트 구성이라 Spring 테스트 컨텍스트 캐시도
 * [IssueAttachmentIntegrationTest] 와 공유될 수 있다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(
    classes = [
        TestConfig::class,
        IssueAttachmentIntegrationTest.AttachmentTestConfig::class,
    ],
)
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueAttachmentServiceImportTest {
    @Autowired
    private lateinit var service: IssueAttachmentService

    @Autowired
    private lateinit var attachmentRepository: AttachmentRepository

    private lateinit var issueKeyValue: String

    companion object {
        private const val PROJECT_KEY = "IMPTSVC"
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
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_attachments")
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
        issueKeyValue = insertIssue("Import 서비스 메타 보존 검증 이슈")
    }

    // ── createdAt/uploadedBy 주입 ────────────────────────────────────────────

    /**
     * Given createdAt/uploadedBy 를 명시 주입
     * When  upload 호출
     * Then  반환 및 저장된 Attachment 의 createdAt·uploadedBy 가 주입값과 일치(clock/actor 아님)
     */
    @Test
    fun `createdAt·uploadedBy 를 주입하면 clock·actor 대신 주입값을 사용한다`() {
        val actor = ActorId(UUID.randomUUID())
        val originalUploader = UUID.randomUUID()
        val originalCreatedAt = Instant.parse("2020-01-01T00:00:00Z")

        val result =
            service.upload(
                actor = actor,
                issueKey = IssueKey(issueKeyValue),
                filename = "imported.png",
                contentType = "image/png",
                sizeBytes = 4L,
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                createdAt = originalCreatedAt,
                uploadedBy = originalUploader,
            )

        assertThat(result.createdAt).isEqualTo(originalCreatedAt)
        assertThat(result.uploadedBy).isEqualTo(originalUploader)

        val persisted = requireNotNull(attachmentRepository.findById(result.id)) { "첨부 조회 실패 — id=${result.id}" }
        assertThat(persisted.createdAt).isEqualTo(originalCreatedAt)
        assertThat(persisted.uploadedBy).isEqualTo(originalUploader)
    }

    /**
     * Given createdAt/uploadedBy 를 생략(null)
     * When  upload 호출
     * Then  createdAt 은 호출 구간 [before, after] 이내(clock 폴백), uploadedBy 는 actor.value(폴백)
     */
    @Test
    fun `createdAt·uploadedBy 를 생략하면 기존 clock·actor 로 폴백한다`() {
        val actor = ActorId(UUID.randomUUID())
        val before = Instant.now()

        val result =
            service.upload(
                actor = actor,
                issueKey = IssueKey(issueKeyValue),
                filename = "fallback.png",
                contentType = "image/png",
                sizeBytes = 4L,
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
            )

        val after = Instant.now()

        assertThat(result.uploadedBy).isEqualTo(actor.value)
        assertThat(result.createdAt).isBetween(before, after)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** [TestConfig.postgres] 에 issue-tracking + project-workflow 마이그레이션을 적용한다. */
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

    /** 테스트 전용 프로젝트 1건 시드. */
    private fun seedProject() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Attachment Import Service Test Project")
                stmt.executeUpdate()
            }
        }
    }

    /** 이슈를 DB에 직접 삽입하고 이슈 키를 반환한다. */
    private fun insertIssue(summary: String): String =
        conn().use { c ->
            c.autoCommit = false
            val seq = nextKeySequence(c)
            val newIssueKey = "$PROJECT_KEY-$seq"
            val projectId = findProjectId(c)
            val taskTypeId = findTaskTypeId(c)
            insertIssueRow(c, newIssueKey, projectId, summary, taskTypeId)
            c.commit()
            newIssueKey
        }

    private fun nextKeySequence(c: Connection): Long =
        c.prepareStatement(
            "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
        ).use { stmt ->
            stmt.setString(1, PROJECT_KEY)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun findProjectId(c: Connection): UUID =
        c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
            stmt.setString(1, PROJECT_KEY)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1) as UUID
            }
        }

    private fun findTaskTypeId(c: Connection): Long =
        c.prepareStatement(
            "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "task 타입 없음 — V003 마이그레이션 확인 필요." }
                rs.getLong(1)
            }
        }

    private fun insertIssueRow(
        c: Connection,
        key: String,
        projectId: UUID,
        summary: String,
        taskTypeId: Long,
    ) {
        c.prepareStatement(
            "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                "VALUES (?, ?, ?, ?, 'open', 1, ?)",
        ).use { stmt ->
            stmt.setString(1, key)
            stmt.setObject(2, projectId)
            stmt.setString(3, summary)
            stmt.setObject(4, UUID.randomUUID())
            stmt.setLong(5, taskTypeId)
            stmt.executeUpdate()
        }
    }

    /** [TestConfig.postgres] 에 직접 연결하는 JDBC 커넥션을 반환한다. */
    private fun conn(): Connection =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
