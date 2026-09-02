// 초안 서비스 통합 테스트 — 편집 시작점 · base_version 고정 · 기본값 복원이 초안에만 담기는지

package com.bts.workflow.application

import com.bts.shared.permission.WorkflowDefinitionAccessDeniedException
import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.DraftRuleDto
import com.bts.workflow.domain.DraftStateDto
import com.bts.workflow.domain.DraftTransitionDto
import com.bts.workflow.domain.WorkflowDraftDefinition
import com.bts.workflow.domain.exception.WorkflowInvalidRequestException
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.engine.DefaultWorkflowPostActionFactory
import com.bts.workflow.engine.DefaultWorkflowValidatorFactory
import com.bts.workflow.expression.SpelEvaluator
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.port.outbound.PermissionResolver
import com.bts.workflow.port.outbound.Scope
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
import org.assertj.core.api.Assertions.assertThatCode
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
import java.util.concurrent.Executors

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

    /** 실물 팩토리로 조립한다 — 「지원 type 의 정본은 팩토리」 계약이 이 테스트에서도 성립해야 한다. */
    private val ruleGuard =
        TransitionRuleGuard(
            DefaultWorkflowValidatorFactory(
                object : PermissionResolver {
                    override fun hasPermission(
                        actorId: ActorId,
                        permission: String,
                        scope: Scope,
                    ): Boolean = true
                },
                SpelEvaluator(Executors.newSingleThreadExecutor()),
            ),
            DefaultWorkflowPostActionFactory(),
        )

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
            ruleWriter =
                DraftRuleWriter(
                    ValidatorRepository(dsl, objectMapper),
                    PostActionRepository(dsl, objectMapper),
                    ruleGuard,
                ),
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
        states = listOf(DraftStateDto(key = "open", name = "열림 $key", category = "TODO", displayOrder = 0)),
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

        service.save(ACTOR, key, validDraft(key), baseVersion = 0)
        val view = service.get(ACTOR, key)

        assertThat(view.exists).isTrue()
        assertThat(view.definition.name).isEqualTo("고친 이름")
    }

    @Test
    fun `발행에서 터질 정의는 저장 단계에서 막는다`() {
        // 초안만 통과하고 발행에서 터지면 관리자는 고칠 방법을 모르는 막다른 길에 놓인다.
        val key = seedWorkflow()
        val broken = WorkflowDraftDefinition(key = key, name = "상태 없는 초안")

        assertThatThrownBy { service.save(ACTOR, key, broken, baseVersion = 0) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    /**
     * ★ **저장은 통과해야 한다.** 시작 전환 「정확히 1개」는 발행 경계의 규칙이다.
     *
     * 저장까지 막으면 새로 만든 워크플로우가 초안 편집기에서 처음부터 못 쓰인다 —
     * `WorkflowCommandService.create` 는 상태만 심고 전환을 하나도 만들지 않으므로, 편집기가
     * `GET /draft` 로 받은 본문을 **그대로** `PUT` 해도 400 이 된다. 그러면 그 편집기로는 요구되는
     * 시작 전환을 추가할 방법 자체가 없다.
     *
     * 초안은 원래 불완전한 중간 상태이고, 그것을 담아 두는 것이 초안이 존재하는 이유다.
     * 완전해야 하는 순간은 운영에 나가는 때 하나뿐이다.
     */
    @Test
    fun `전환이 하나도 없는 초안도 저장은 된다 — 편집 중간 상태다`() {
        val key = seedWorkflow()
        val noTransitions =
            WorkflowDraftDefinition(
                key = key,
                name = "편집을 막 시작한 초안",
                states = listOf(DraftStateDto(key = "open", name = "열림 $key", category = "TODO", displayOrder = 0)),
                transitions = emptyList(),
            )

        service.save(ACTOR, key, noTransitions, baseVersion = 0)

        assertThat(draftRepository.findByWorkflowId(workflowId(key))!!.definition.transitions).isEmpty()
    }

    /**
     * ★ 규칙 관문은 **저장에서도** 돈다 — 발행에서만 돌면 「저장 204 · 발행 400」이 된다.
     *
     * 재리뷰가 잡은 것이다. `WorkflowDraftDefinition` · `DraftRuleWriter` · 이 서비스의 KDoc
     * 세 곳이 「저장 시점에 같은 판정을 태운다」고 적었는데 실제로는 `checkAll` 의 호출자가
     * 발행뿐이었다 — 1차 리뷰가 BLOCKER 2 로 잡은 「검증이 없는데 문서가 있다고 적음」과
     * **같은 양식**이다. 문서를 고치는 대신 코드를 문서에 맞춘다.
     */
    @Test
    fun `편집 API 가 막는 validator 타입은 저장 단계에서 막힌다`() {
        val key = seedWorkflow()
        val base = validDraft(key)
        val withCustomExpression =
            base.copy(
                transitions =
                    base.transitions.map {
                        it.copy(validators = listOf(DraftRuleDto("CustomExpression", mapOf("expression" to "1 == 1"))))
                    },
            )

        assertThatThrownBy { service.save(ACTOR, key, withCustomExpression, baseVersion = 0) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    // ── ★ 받아 놓고 버리지 않는다 (절대 규칙 16) ──────────────────────────────
    //
    // 초안이 담는 상태 `name`·`category` 와 워크플로우 `key` 는 발행에서 **쓰이지 않는다** —
    // 편성 INSERT 는 workflow_id·status_id·display_order 만 쓰고, 이름·카테고리는 전역 카탈로그에서
    // 온다. key 도 replaceDefinition 이 건드리지 않는다.
    //
    // 그런데 그 값이 workflow_publications.definition 스냅샷에는 **그대로 기록된다.**
    // 즉 append-only 감사 기록이 「실제로 일어나지 않은 변경」을 발행됐다고 증언한다.
    // 받아서 버릴 바에는 거절해야 한다.

    @Test
    fun `상태 이름을 카탈로그와 다르게 보낸 초안은 거부한다`() {
        val key = seedWorkflow() // 카탈로그의 이름은 "열림 $key" 다
        val base = validDraft(key)
        val renamed = base.copy(states = base.states.map { it.copy(name = "내가 정한 이름") })

        assertThatThrownBy { service.save(ACTOR, key, renamed, baseVersion = 0) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    @Test
    fun `상태 카테고리를 카탈로그와 다르게 보낸 초안은 거부한다`() {
        val key = seedWorkflow() // 카탈로그의 카테고리는 TODO 다
        val base = validDraft(key)
        val recategorised = base.copy(states = base.states.map { it.copy(category = "DONE") })

        assertThatThrownBy { service.save(ACTOR, key, recategorised, baseVersion = 0) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    /**
     * 형제 `UpdateWorkflowRequest` 는 「이슈·자동화·검색이 문자열로 참조하는 식별자라 바뀌면 조용히
     * 끊긴다」는 이유로 `key` 필드를 **아예 없앴다.** 초안 API 가 그 구멍을 다시 열어서는 안 된다.
     */
    @Test
    fun `경로와 다른 key 를 담은 초안은 거부한다`() {
        val key = seedWorkflow()

        assertThatThrownBy { service.save(ACTOR, key, validDraft(key).copy(key = "남의-워크플로우"), baseVersion = 0) }
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
        service.save(ACTOR, key, validDraft(key, "첫 저장"), baseVersion = 5)

        // 그 사이 다른 세션이 발행해 버전이 올라갔다.
        dsl.execute("UPDATE workflows SET version = 9 WHERE key = ?", key)
        service.save(ACTOR, key, validDraft(key, "두 번째 저장"), baseVersion = 5)

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
        service.save(ACTOR, key, validDraft(key, "첫 저장"), baseVersion = 5)

        // 남이 발행했다 — 버전이 오르고 초안 행이 사라진다. 두 가지가 함께 일어나는 것이 핵심이다.
        dsl.execute("UPDATE workflows SET version = 6 WHERE key = ?", key)
        draftRepository.deleteByWorkflowId(workflowId(key))

        // A 의 자동 저장. A 의 화면은 여전히 버전 5 를 보고 있다.
        service.save(ACTOR, key, validDraft(key, "옛 화면의 저장"), baseVersion = 5)

        assertThat(draftRepository.findByWorkflowId(workflowId(key))!!.baseVersion).isEqualTo(5)
    }

    // ── 폐기 ──────────────────────────────────────────────────────────────────

    @Test
    fun `폐기하면 true 를 돌려주고 초안이 사라진다`() {
        val key = seedWorkflow()
        service.save(ACTOR, key, validDraft(key), baseVersion = 0)

        assertThat(service.discard(ACTOR, key)).isTrue()
        assertThat(draftRepository.findByWorkflowId(workflowId(key))).isNull()
    }

    /**
     * ★ 발행 권한만 가진 운영자도 초안을 폐기할 수 있어야 한다 — 그것이 유일한 탈출구다.
     *
     * 앵커는 write-once 다(`upsert` 의 `DO UPDATE` 가 그 컬럼을 뺀다). 그래서 `UPDATE` 만 가진
     * 행위자가 `baseVersion=0` 으로 초안을 만들면 그 워크플로우의 발행은 **영구 409** 가 되고,
     * 409 문구가 가리키는 출구는 초안 폐기 하나다. 그 폐기에 `UPDATE` 를 요구하면 **발행 운영자는
     * 스스로 빠져나올 수 없다.**
     *
     * 「발행할 수 있는 사람은 발행을 막는 초안도 치울 수 있다」가 읽기로도 자연스럽다.
     */
    @Test
    fun `발행 권한만 있어도 초안을 폐기할 수 있다`() {
        val key = seedWorkflow()
        service.save(ACTOR, key, validDraft(key), baseVersion = 0)
        permissions.deny += WorkflowDefinitionPermission.UPDATE

        try {
            assertThat(service.discard(ACTOR, key)).isTrue()
            assertThat(draftRepository.findByWorkflowId(workflowId(key))).isNull()
        } finally {
            permissions.deny.clear()
        }
    }

    @Test
    fun `편집 권한도 발행 권한도 없으면 폐기할 수 없다`() {
        val key = seedWorkflow()
        service.save(ACTOR, key, validDraft(key), baseVersion = 0)
        permissions.allow = false

        try {
            assertThatThrownBy { service.discard(ACTOR, key) }
                .isInstanceOf(WorkflowDefinitionAccessDeniedException::class.java)
        } finally {
            permissions.allow = true
        }
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
                states = listOf(StateYamlDto(key = "open", name = "열림 $key", category = "TODO", displayOrder = 0)),
                transitions = listOf(TransitionYamlDto(to = "open", name = "이슈 생성", kind = "INITIAL")),
            )

        val restored = service.resetToDefault(ACTOR, key, baseVersion = 0)

        assertThat(restored.definition.name).isEqualTo("YAML 원본 이름")
        assertThat(draftRepository.findByWorkflowId(workflowId(key))!!.definition.name).isEqualTo("YAML 원본 이름")
        // 정규 테이블은 아직 옛 이름이다 — 발행해야 운영에 나간다.
        assertThat(cache.findByKey(key)!!.name).isEqualTo("초안 서비스 테스트")
    }

    /**
     * ★ 기본값 복원이 **막다른 길**을 만들면 안 된다.
     *
     * 시드는 YAML 의 이름을 그대로 카탈로그에 심지만, 그 뒤 관리자가 상태 API 로 이름을 바꾸면
     * 둘이 갈린다. 그때 복원이 YAML 의 옛 이름을 그대로 초안에 실으면 저장은 되고 **발행에서만**
     * 카탈로그 대조에 걸린다 — 관리자는 자기가 만들지도 않은 값 때문에 발행이 막히고 고칠 방법을
     * 모른다. 이 서비스 KDoc 이 「초안은 저장됐는데 발행에서 터지는 막다른 길」이라 부르는 것이다.
     *
     * 상태 이름·카테고리의 정본은 **카탈로그**다. YAML 의 권위는 워크플로우의 **구조**
     * (어떤 상태를 쓰고 어떤 전환이 있는가)이지 표시 이름이 아니다.
     */
    @Test
    fun `기본값 복원은 상태 이름을 카탈로그에서 가져온다`() {
        val key = seedWorkflow(origin = "SEED")
        defaults.yaml =
            WorkflowYamlDto(
                key = key,
                name = "YAML 원본 이름",
                // YAML 은 옛 이름을 들고 있다. 카탈로그는 seedWorkflow 가 심은 "열림 $key" 다.
                states = listOf(StateYamlDto(key = "open", name = "Open", category = "TODO", displayOrder = 0)),
                transitions = listOf(TransitionYamlDto(to = "open", name = "이슈 생성", kind = "INITIAL")),
            )

        val restored = service.resetToDefault(ACTOR, key, baseVersion = 0)

        assertThat(restored.definition.states.single().name).isEqualTo("열림 $key")
    }

    @Test
    fun `사용자가 만든 워크플로우는 되돌릴 기본값이 없다`() {
        val key = seedWorkflow(origin = "CUSTOM")
        defaults.yaml = WorkflowYamlDto(key = key, name = "쓰이면 안 되는 값")

        assertThatThrownBy { service.resetToDefault(ACTOR, key, baseVersion = 0) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    @Test
    fun `기본값 YAML 이 없으면 복원할 수 없다`() {
        val key = seedWorkflow(origin = "SEED")
        defaults.yaml = null

        assertThatThrownBy { service.resetToDefault(ACTOR, key, baseVersion = 0) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    // ── 복원 가능 여부의 사전 고지 (FR-WF-07 D6) ──────────────────────────────

    /**
     * ★ 화면이 눌러 보고 400 을 받아 배우게 두지 않는다.
     *
     * 「기본값으로 복원」 버튼의 표시 조건은 `origin == SEED` **그리고** YAML 실재인데, `origin`
     * 은 [WorkflowVersionRow] 에만 있고 도메인 `Workflow` 와 응답 `WorkflowDto` 어디에도 없다.
     * 프론트는 CUSTOM 워크플로우에서도 버튼을 띄우고 누르면 400 을 받는다 — 화면이 할 수 없는
     * 일을 할 수 있는 것처럼 그리는 자리다.
     *
     * [DraftView.canResetToDefault] 가 그 답을 미리 싣는다. 편집기가 `GET /draft` 를 반드시
     * 부르므로 추가 왕복이 없고, `requireLive` 가 이미 origin 을 실은 행을 읽어 추가 조회도 없다.
     *
     * ★ **판정과 실행이 같은 근거를 봐야 한다.** 아래 세 케이스는 `canResetToDefault` 와
     * `resetToDefault` 의 성패가 **짝을 이루는지**를 잰다. 플래그만 따로 재면 둘이 갈린 채로
     * 초록이 되고, 그때 화면은 버튼을 감춘 채 「복원할 수 있는데 못 하는」 상태가 된다.
     */
    @Test
    fun `SEED 이고 YAML 이 있으면 복원 가능이라고 알린다`() {
        val key = seedWorkflow(origin = "SEED")
        defaults.yaml =
            WorkflowYamlDto(
                key = key,
                name = "YAML 원본 이름",
                states = listOf(StateYamlDto(key = "open", name = "열림 $key", category = "TODO", displayOrder = 0)),
                transitions = listOf(TransitionYamlDto(to = "open", name = "이슈 생성", kind = "INITIAL")),
            )

        assertThat(service.get(ACTOR, key).canResetToDefault).isTrue()
        // 짝 — 실제로 복원이 된다.
        assertThatCode { service.resetToDefault(ACTOR, key, baseVersion = 0) }.doesNotThrowAnyException()
    }

    @Test
    fun `사용자가 만든 워크플로우는 복원 불가라고 알린다`() {
        val key = seedWorkflow(origin = "CUSTOM")
        // YAML 은 있지만 origin 이 CUSTOM 이다 — 두 조건 중 하나만 어긋나도 불가여야 한다.
        defaults.yaml = WorkflowYamlDto(key = key, name = "쓰이면 안 되는 값")

        assertThat(service.get(ACTOR, key).canResetToDefault).isFalse()
        assertThatThrownBy { service.resetToDefault(ACTOR, key, baseVersion = 0) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    @Test
    fun `SEED 여도 YAML 이 없으면 복원 불가라고 알린다`() {
        // origin 만 보고 판정하면 이 케이스가 초록으로 새고, 화면은 눌러야 실패를 안다.
        val key = seedWorkflow(origin = "SEED")
        defaults.yaml = null

        assertThat(service.get(ACTOR, key).canResetToDefault).isFalse()
        assertThatThrownBy { service.resetToDefault(ACTOR, key, baseVersion = 0) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    @Test
    fun `복원 응답도 같은 플래그를 싣는다`() {
        // 복원 직후 화면이 그 응답으로 상태를 갱신한다 — 여기서 플래그가 빠지면 버튼이 사라진다.
        val key = seedWorkflow(origin = "SEED")
        defaults.yaml =
            WorkflowYamlDto(
                key = key,
                name = "YAML 원본 이름",
                states = listOf(StateYamlDto(key = "open", name = "열림 $key", category = "TODO", displayOrder = 0)),
                transitions = listOf(TransitionYamlDto(to = "open", name = "이슈 생성", kind = "INITIAL")),
            )

        assertThat(service.resetToDefault(ACTOR, key, baseVersion = 0).canResetToDefault).isTrue()
    }

    @Test
    fun `편집 권한이 없으면 초안을 저장할 수 없다`() {
        val key = seedWorkflow()
        permissions.allow = false

        try {
            assertThatThrownBy { service.save(ACTOR, key, validDraft(key), baseVersion = 0) }
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
