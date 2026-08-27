// 초안 서비스 통합 테스트 — 편집 시작점 · base_version 고정 · 기본값 복원이 초안에만 담기는지

package com.bts.workflow.application

import com.bts.shared.permission.WorkflowDefinitionAccessDeniedException
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.DraftStateDto
import com.bts.workflow.domain.DraftTransitionDto
import com.bts.workflow.domain.WorkflowDraftDefinition
import com.bts.workflow.domain.exception.WorkflowInvalidRequestException
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.postaction.PostActionRepository
import com.bts.workflow.repository.WorkflowDraftRepository
import com.bts.workflow.repository.WorkflowPublishRepository
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.seed.StandardWorkflowDefaults
import com.bts.workflow.seed.StateYamlDto
import com.bts.workflow.seed.TransitionYamlDto
import com.bts.workflow.seed.WorkflowYamlDto
import com.bts.workflow.testsupport.insertWorkflowStatus
import com.bts.workflow.validator.ValidatorRepository
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
 * [WorkflowDraftService] Testcontainers 통합 테스트.
 *
 * ### 여기서 지키는 두 계약
 * 1. **`base_version` 은 처음 한 번만 기록된다.** 저장할 때마다 갱신하면 낙관적 락이 무력해진다 —
 *    A 가 초안을 뜨고 → B 가 발행하고 → A 가 초안을 한 번 더 저장하면 base 가 새 버전으로 올라가
 *    A 의 발행이 B 의 변경을 조용히 덮어쓴다.
 * 2. **기본값 복원은 초안에만 담긴다.** 정규 테이블을 바로 되돌리면 「되돌림의 주체가 부팅 이벤트에서
 *    사람으로 바뀐다」는 ADR D4 의 취지가 사라진다 — 눌렀는데 운영이 즉시 바뀌면 부팅 되돌림과 같다.
 *
 * `StandardWorkflowDefaults` 를 스텁으로 대체한다. 실제 구현인 `YamlSeedService` 는 협력자가 5개고
 * 기존 테스트들이 「Spring 없이 미동작」이라 적고 회피할 만큼 조립이 무겁다. 창구를 좁혀 둔 것이
 * 이 테스트를 가능하게 한다.
 *
 * 참조. FR-WF-07 D4 · ADR 2026-08-18-workflow-db-as-source-of-truth §D3·§D4
 */
@Testcontainers
class WorkflowDraftServiceIntegrationTest {
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

        /** 순서가 load-bearing 이다 — issue_types 를 먼저 만들면 Flyway 가 non-empty 스키마를 거부한다. */
        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            flyway().target("200").load().migrate()
            createIssueTypes()
            flyway().load().migrate()
        }

        @JvmStatic
        private fun flyway() =
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )

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

    /** 테스트가 기본값 YAML 을 정하는 스텁. 실제 구현은 classpath 를 읽는다. */
    private class StubDefaults : StandardWorkflowDefaults {
        var yaml: WorkflowYamlDto? = null

        override fun loadStandardYaml(key: String): WorkflowYamlDto? = yaml
    }

    private val dsl =
        DSL.using(
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
            SQLDialect.POSTGRES,
        )
    private val objectMapper = ObjectMapper()
    private val draftRepository = WorkflowDraftRepository(dsl, objectMapper)
    private val publishRepository = WorkflowPublishRepository(dsl)
    private val cache = WorkflowCache(WorkflowRepository(dsl), dsl)
    private val defaults = StubDefaults()
    private val permissions = SwitchableWorkflowPermissions()

    private val service =
        WorkflowDraftService(
            draftRepository = draftRepository,
            publishRepository = publishRepository,
            currentDefinitionReader =
                CurrentDefinitionReader(
                    cache,
                    ValidatorRepository(dsl, objectMapper),
                    PostActionRepository(dsl, objectMapper),
                ),
            standardDefaults = defaults,
            permissionResolver = permissions,
        )

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private fun seedWorkflow(
        origin: String = "CUSTOM",
        version: Long = 0,
    ): String {
        val key = "wf-draft-${UUID.randomUUID().toString().take(8)}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val workflowId =
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "INSERT INTO workflows (key, name, origin, version)" +
                            " VALUES ('$key', '초안 서비스 테스트', '$origin', $version) RETURNING id",
                    ).use { rs ->
                        rs.next()
                        UUID.fromString(rs.getString(1))
                    }
                }
            insertWorkflowStatus(conn, workflowId, "open", "열림 $key", "TODO", 0)
            val compositionId =
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT id FROM workflow_statuses WHERE workflow_id = '$workflowId'",
                    ).use { rs ->
                        rs.next()
                        UUID.fromString(rs.getString(1))
                    }
                }
            conn.createStatement().use {
                it.execute(
                    "INSERT INTO workflow_transitions" +
                        " (workflow_id, kind, name, from_status_id, to_status_id, display_order)" +
                        " VALUES ('$workflowId', 'INITIAL', '이슈 생성', NULL, '$compositionId', 0)",
                )
            }
        }
        return key
    }

    private fun validDraft(
        key: String,
        name: String = "고친 이름",
    ) = WorkflowDraftDefinition(
        key = key,
        name = name,
        states = listOf(DraftStateDto(key = "open", name = "열림", category = "TODO", displayOrder = 0)),
        transitions = listOf(DraftTransitionDto(from = null, to = "open", name = "이슈 생성", kind = "INITIAL")),
    )

    private fun workflowId(key: String): UUID = publishRepository.findLiveByKey(key)!!.id

    // ── 편집 시작점 ───────────────────────────────────────────────────────────

    @Test
    fun `초안이 없으면 지금 정의를 돌려주되 DB 에 초안을 만들지 않는다`() {
        // 열어만 보고 닫은 워크플로우에 초안이 남으면 「편집 중」 표시가 거짓이 된다.
        val key = seedWorkflow()

        val view = service.get(ACTOR, key)

        assertThat(view.exists).isFalse()
        assertThat(view.definition.name).isEqualTo("초안 서비스 테스트")
        assertThat(view.definition.states.map { it.key }).containsExactly("open")
        assertThat(draftRepository.findByWorkflowId(workflowId(key))).isNull()
    }

    @Test
    fun `저장하면 그 초안이 조회된다`() {
        val key = seedWorkflow()

        service.save(ACTOR, key, validDraft(key))
        val view = service.get(ACTOR, key)

        assertThat(view.exists).isTrue()
        assertThat(view.definition.name).isEqualTo("고친 이름")
    }

    @Test
    fun `발행에서 터질 정의는 저장 단계에서 막는다`() {
        // 초안만 통과하고 발행에서 터지면 관리자는 고칠 방법을 모르는 막다른 길에 놓인다.
        val key = seedWorkflow()
        val broken = WorkflowDraftDefinition(key = key, name = "상태 없는 초안")

        assertThatThrownBy { service.save(ACTOR, key, broken) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    // ── ★ base_version 고정 ───────────────────────────────────────────────────

    /**
     * ★ 이 픽스처는 **초안 행이 남아 있는** 갈래만 덮는다. 실제 발행은 버전을 올리면서 초안 행을
     * **지우므로**, 그 뒤의 저장은 아래 테스트가 여는 다른 갈래를 탄다. 둘을 함께 두어야 낙관적
     * 락이 양쪽에서 성립한다.
     */
    @Test
    fun `두 번째 저장이 base_version 을 갱신하지 않는다`() {
        // 갱신하면 그 사이 남이 한 발행을 조용히 덮어쓰게 된다 — 낙관적 락이 무력해지는 지점이다.
        val key = seedWorkflow(version = 5)
        service.save(ACTOR, key, validDraft(key, "첫 저장"))

        // 그 사이 다른 세션이 발행해 버전이 올라갔다.
        dsl.execute("UPDATE workflows SET version = 9 WHERE key = ?", key)
        service.save(ACTOR, key, validDraft(key, "두 번째 저장"))

        val stored = draftRepository.findByWorkflowId(workflowId(key))!!
        assertThat(stored.definition.name).isEqualTo("두 번째 저장")
        assertThat(stored.baseVersion).isEqualTo(5)
    }

    /**
     * 발행은 **버전을 올리면서 초안 행을 지운다**(`WorkflowPublishService`). 그래서 「초안 없음」은
     * 편집 시작 직후뿐 아니라 **남이 방금 발행한 직후**에도 성립한다. 그 자리에서 서버가 현재
     * 버전을 다시 읽어 앵커로 삼으면, 옛 화면의 자동 저장 한 번으로 낙관적 락이 풀리고 A 의
     * 발행이 B 의 변경을 조용히 덮어쓴다 — 이 서비스 KDoc 이 막겠다고 적은 바로 그 사고다.
     *
     * 앵커는 **화면이 무엇을 보고 있었는지**이고 서버는 그것을 재구성할 수 없다. 그래서 저장
     * 요청이 함께 싣는다.
     */
    @Test
    fun `발행이 초안을 지운 뒤 옛 화면이 저장해도 base 는 발행 전 버전이다`() {
        val key = seedWorkflow(version = 5)
        service.save(ACTOR, key, validDraft(key, "첫 저장"))

        // 남이 발행했다 — 버전이 오르고 초안 행이 사라진다. 두 가지가 함께 일어나는 것이 핵심이다.
        dsl.execute("UPDATE workflows SET version = 6 WHERE key = ?", key)
        draftRepository.deleteByWorkflowId(workflowId(key))

        // A 의 자동 저장. A 의 화면은 여전히 버전 5 를 보고 있다.
        service.save(ACTOR, key, validDraft(key, "옛 화면의 저장"))

        assertThat(draftRepository.findByWorkflowId(workflowId(key))!!.baseVersion).isEqualTo(5)
    }

    // ── 폐기 ──────────────────────────────────────────────────────────────────

    @Test
    fun `폐기하면 true 를 돌려주고 초안이 사라진다`() {
        val key = seedWorkflow()
        service.save(ACTOR, key, validDraft(key))

        assertThat(service.discard(ACTOR, key)).isTrue()
        assertThat(draftRepository.findByWorkflowId(workflowId(key))).isNull()
    }

    @Test
    fun `없는 초안을 폐기하면 false 다`() {
        assertThat(service.discard(ACTOR, seedWorkflow())).isFalse()
    }

    // ── 기본값 복원 ───────────────────────────────────────────────────────────

    @Test
    fun `기본값 복원은 초안에만 담기고 정규 테이블은 그대로다`() {
        val key = seedWorkflow(origin = "SEED")
        defaults.yaml =
            WorkflowYamlDto(
                key = key,
                name = "YAML 원본 이름",
                states = listOf(StateYamlDto(key = "open", name = "열림", category = "TODO", displayOrder = 0)),
                transitions = listOf(TransitionYamlDto(to = "open", name = "이슈 생성", kind = "INITIAL")),
            )

        val restored = service.resetToDefault(ACTOR, key)

        assertThat(restored.name).isEqualTo("YAML 원본 이름")
        assertThat(draftRepository.findByWorkflowId(workflowId(key))!!.definition.name).isEqualTo("YAML 원본 이름")
        // 정규 테이블은 아직 옛 이름이다 — 발행해야 운영에 나간다.
        assertThat(cache.findByKey(key)!!.name).isEqualTo("초안 서비스 테스트")
    }

    @Test
    fun `사용자가 만든 워크플로우는 되돌릴 기본값이 없다`() {
        val key = seedWorkflow(origin = "CUSTOM")
        defaults.yaml = WorkflowYamlDto(key = key, name = "쓰이면 안 되는 값")

        assertThatThrownBy { service.resetToDefault(ACTOR, key) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    @Test
    fun `기본값 YAML 이 없으면 복원할 수 없다`() {
        val key = seedWorkflow(origin = "SEED")
        defaults.yaml = null

        assertThatThrownBy { service.resetToDefault(ACTOR, key) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    @Test
    fun `편집 권한이 없으면 초안을 저장할 수 없다`() {
        val key = seedWorkflow()
        permissions.allow = false

        try {
            assertThatThrownBy { service.save(ACTOR, key, validDraft(key)) }
                .isInstanceOf(WorkflowDefinitionAccessDeniedException::class.java)
        } finally {
            permissions.allow = true
        }
    }

    @Test
    fun `없는 워크플로우는 404 다`() {
        assertThatThrownBy { service.get(ACTOR, "wf-없음") }
            .isInstanceOf(WorkflowNotFoundException::class.java)
    }
}
