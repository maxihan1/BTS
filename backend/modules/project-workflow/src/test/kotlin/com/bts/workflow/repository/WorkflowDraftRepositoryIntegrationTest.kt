// WorkflowDraftRepository 통합 테스트 — Testcontainers + Flyway 전량 + 초안 JSONB 왕복

package com.bts.workflow.repository

import com.bts.workflow.domain.DraftRuleDto
import com.bts.workflow.domain.DraftStateDto
import com.bts.workflow.domain.DraftTransitionDto
import com.bts.workflow.domain.WorkflowDraftDefinition
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * [WorkflowDraftRepository] Testcontainers 통합 테스트.
 *
 * `workflow_drafts` 는 V208 신규 테이블이고 `workflow_id` 가 PK 이자 workflows 로 향하는
 * FK(ON DELETE CASCADE)다. 그래서 워크플로우 행을 먼저 심어야 INSERT 자체가 성립한다.
 * 상태·전환 픽스처는 필요 없다 — 초안은 정의를 JSONB 통째로 담아 다른 테이블을 참조하지 않는다.
 * 그것이 초안을 JSONB 로 둔 이유이기도 하다.
 *
 * 검증 범위.
 * - upsert → find: 정의 JSONB 왕복 (상태·전환·규칙 중첩 구조 포함)
 * - upsert 2회: 덮어쓰기. 초안은 워크플로우당 1개다
 * - deleteByWorkflowId: 그 초안만 빠지고 다른 워크플로우 초안은 남는다
 * - 손상된 JSONB: 빈 정의로 접지 않고 **예외**를 던진다
 *
 * **워크플로우는 테스트마다 새로 만든다.** 하나를 공유하면 앞선 테스트가 남긴 초안이 뒤의 단언을
 * 오염시키고, 그 오염을 `@TestMethodOrder` 로 막으면 실행 순서에 기대는 테스트가 된다
 * (`ValidatorRepositoryIntegrationTest` 와 같은 판단).
 *
 * 참조. FR-WF-07 D4 · V208__workflow_drafts_and_publications.sql
 */
@Testcontainers
class WorkflowDraftRepositoryIntegrationTest {
    companion object {
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            createIssueTypes()
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .load()
                .migrate()
        }

        /** V201 의 cross-BC FK 대상. issue-tracking 마이그레이션이 만들지 않는 경로라 테스트가 직접 만든다. */
        @JvmStatic
        private fun createIssueTypes() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        "CREATE TABLE IF NOT EXISTS issue_types (" +
                            "id BIGSERIAL PRIMARY KEY, key VARCHAR(30) NOT NULL UNIQUE, " +
                            "name VARCHAR(255) NOT NULL, is_standard BOOLEAN NOT NULL DEFAULT FALSE, " +
                            "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), " +
                            "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)",
                    )
                }
            }
        }
    }

    private val dsl =
        DSL.using(
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
            SQLDialect.POSTGRES,
        )

    private val objectMapper = ObjectMapper()

    private val repository = WorkflowDraftRepository(dsl, objectMapper)

    /** 테스트마다 새 워크플로우를 심는다. key 는 호출마다 달라야 uq_workflows_key 에 걸리지 않는다. */
    private fun newWorkflow(): UUID {
        val key = "wf-draft-${UUID.randomUUID()}"
        return DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "INSERT INTO workflows (key, name) VALUES ('$key', '초안 테스트') RETURNING id",
                ).use { rs ->
                    rs.next()
                    UUID.fromString(rs.getString(1))
                }
            }
        }
    }

    private fun sampleDefinition(name: String = "초안 이름") =
        WorkflowDraftDefinition(
            key = "wf-sample",
            name = name,
            description = "설명",
            states =
                listOf(
                    DraftStateDto(key = "open", name = "열림", category = "TODO", displayOrder = 0),
                    DraftStateDto(key = "done", name = "완료", category = "DONE", displayOrder = 1),
                ),
            transitions =
                listOf(
                    DraftTransitionDto(from = null, to = "open", name = "이슈 생성", kind = "INITIAL"),
                    DraftTransitionDto(
                        from = "open",
                        to = "done",
                        name = "완료",
                        validators = listOf(DraftRuleDto(type = "RequiredField", config = mapOf("field" to "resolution"))),
                        postActions = listOf(DraftRuleDto(type = "CALL_WEBHOOK", config = mapOf("url" to "https://x"))),
                    ),
                ),
        )

    // ── 왕복 ──────────────────────────────────────────────────────────────────

    @Test
    fun `초안을 저장하고 다시 읽으면 정의가 그대로다`() {
        val workflowId = newWorkflow()
        val definition = sampleDefinition()

        repository.upsert(workflowId, definition, baseVersion = 3, updatedBy = null)
        val found = repository.findByWorkflowId(workflowId)

        assertThat(found).isNotNull
        assertThat(found!!.definition).isEqualTo(definition)
        assertThat(found.baseVersion).isEqualTo(3)
    }

    @Test
    fun `중첩된 규칙 config 까지 왕복한다`() {
        // JSONB 직렬화가 얕으면 규칙의 config 맵이 통째로 사라진다 — 화면에는 규칙이 있는데
        // 발행하면 규칙 없는 워크플로우가 나가는, 조용한 실패다.
        val workflowId = newWorkflow()
        repository.upsert(workflowId, sampleDefinition(), baseVersion = 0, updatedBy = null)

        val transition = repository.findByWorkflowId(workflowId)!!.definition.transitions.last()

        assertThat(transition.validators).hasSize(1)
        assertThat(transition.validators.single().config).containsEntry("field", "resolution")
        assertThat(transition.postActions.single().config).containsEntry("url", "https://x")
    }

    @Test
    fun `편집자 id 가 보존된다`() {
        val workflowId = newWorkflow()
        val editor = UUID.randomUUID()

        repository.upsert(workflowId, sampleDefinition(), baseVersion = 0, updatedBy = editor)

        assertThat(repository.findByWorkflowId(workflowId)!!.updatedBy).isEqualTo(editor)
    }

    // ── 초안은 워크플로우당 1개 ───────────────────────────────────────────────

    @Test
    fun `두 번 저장하면 덮어쓴다 — 초안은 하나다`() {
        val workflowId = newWorkflow()

        repository.upsert(workflowId, sampleDefinition("첫 번째"), baseVersion = 0, updatedBy = null)
        repository.upsert(workflowId, sampleDefinition("두 번째"), baseVersion = 7, updatedBy = null)

        val found = repository.findByWorkflowId(workflowId)!!
        assertThat(found.definition.name).isEqualTo("두 번째")
        assertThat(found.baseVersion).isEqualTo(7)
    }

    // ── 폐기 ──────────────────────────────────────────────────────────────────

    @Test
    fun `초안을 폐기하면 사라진다`() {
        val workflowId = newWorkflow()
        repository.upsert(workflowId, sampleDefinition(), baseVersion = 0, updatedBy = null)

        val deleted = repository.deleteByWorkflowId(workflowId)

        assertThat(deleted).isTrue()
        assertThat(repository.findByWorkflowId(workflowId)).isNull()
    }

    @Test
    fun `없는 초안을 폐기하면 false 를 돌려준다`() {
        // 없는 것을 지웠다고 성공을 보고하면 화면이 「폐기됨」을 표시하고 관리자는 초안이
        // 있었다고 오해한다. 204 와 404 를 가르는 근거가 이 반환값이다.
        assertThat(repository.deleteByWorkflowId(newWorkflow())).isFalse()
    }

    @Test
    fun `한 워크플로우의 초안을 지워도 다른 워크플로우 초안은 남는다`() {
        val kept = newWorkflow()
        val removed = newWorkflow()
        repository.upsert(kept, sampleDefinition("남는 것"), baseVersion = 0, updatedBy = null)
        repository.upsert(removed, sampleDefinition("지울 것"), baseVersion = 0, updatedBy = null)

        repository.deleteByWorkflowId(removed)

        assertThat(repository.findByWorkflowId(kept)).isNotNull
        assertThat(repository.findByWorkflowId(removed)).isNull()
    }

    @Test
    fun `초안이 없으면 null 이다`() {
        assertThat(repository.findByWorkflowId(newWorkflow())).isNull()
    }

    // ── 손상된 JSONB 는 조용히 접지 않는다 ────────────────────────────────────

    @Test
    fun `읽을 수 없는 정의는 빈 초안이 아니라 예외다`() {
        // 형제인 TransitionRuleRepository 는 파싱 실패 시 빈 Map 으로 접는다. 규칙 config 는
        // 그래도 되지만 초안은 다르다 — 빈 정의를 돌려주면 관리자에게는 편집하던 내용이 사라진
        // 것으로 보이고, 그 화면에서 저장하는 순간 진짜로 사라진다. 여기서는 멈추는 편이 싸다.
        val workflowId = newWorkflow()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use {
                it.execute(
                    "INSERT INTO workflow_drafts (workflow_id, definition, base_version)" +
                        " VALUES ('$workflowId', '[\"배열은 초안 정의가 아니다\"]'::jsonb, 0)",
                )
            }
        }

        assertThatThrownBy { repository.findByWorkflowId(workflowId) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(workflowId.toString())
    }
}
