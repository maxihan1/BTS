// WorkflowEngine production 결선 통합 테스트 — validator 거부/availableTransitions/PostAction/YAML 시드 4개 시나리오 검증

package com.bts.workflow.engine

import com.bts.shared.workflow.AvailableTransitionsRequest
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.shared.workflow.TransitionRequest
import com.bts.workflow.adapter.AlwaysAllowPermissionResolver
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.exception.WorkflowValidatorFailureException
import com.bts.workflow.expression.SpelEvaluator
import com.bts.workflow.port.outbound.PermissionResolver
import com.bts.workflow.repository.DefaultWorkflowDefinitionRepository
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.seed.PostActionYamlDto
import com.bts.workflow.seed.StateYamlDto
import com.bts.workflow.seed.TransitionYamlDto
import com.bts.workflow.seed.ValidatorYamlDto
import com.bts.workflow.seed.WorkflowYamlDto
import com.bts.workflow.seed.YamlSeedService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.concurrent.Executors

/**
 * WorkflowEngine production 결선 통합 테스트.
 *
 * Testcontainers PostgreSQL + production 구현체(DefaultWorkflowValidatorFactory,
 * DefaultWorkflowPostActionFactory, DefaultWorkflowDefinitionRepository) 전체 스택 결선 후
 * 아래 4개 시나리오를 검증한다.
 *
 * Spring ApplicationContext 없이 의존 객체를 직접 조합한다 (기존 테스트 패턴 유지).
 * WorkflowEngine.plan() 은 Propagation.MANDATORY 이므로 TransactionTemplate 으로 감싼다.
 *
 * ### 시나리오 목록
 * - S1. validator 거부 — RequiredField(EXECUTION) 가 걸린 전이를 resolution 없이 plan() 시
 *       WorkflowValidatorFailureException 발생.
 * - S2. availableTransitions EXECUTION 페이즈 포함 — 동일 전이가 availableTransitions 결과에 포함됨
 *       (EXECUTION 페이즈는 목록에서 skip 하므로 버튼 표시).
 * - S3. PostAction plan 누적 — SET_FIELD PostAction 이 TransitionPlan.fieldChanges 에,
 *       NOTIFY PostAction 이 TransitionPlan.emitEvents 에 누적됨.
 * - S4. YAML seed → repo 조회 — 시드한 validator/post_action 을
 *       DefaultWorkflowDefinitionRepository.findValidators/findPostActions 로 조회 확인.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WorkflowEngineWiringIntegrationTest {
    companion object {
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_wiring_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /**
         * SpelEvaluator 공유 executor.
         * CustomExpressionValidator 를 사용하는 경우에 備해 충분한 timeout 을 설정한다.
         */
        private val spelExecutor = Executors.newCachedThreadPool()

        private lateinit var dsl: DSLContext
        private lateinit var workflowRepository: WorkflowRepository
        private lateinit var workflowCache: WorkflowCache
        private lateinit var permissionResolver: PermissionResolver
        private lateinit var spelEvaluator: SpelEvaluator
        private lateinit var validatorFactory: DefaultWorkflowValidatorFactory
        private lateinit var postActionFactory: DefaultWorkflowPostActionFactory
        private lateinit var definitionRepo: DefaultWorkflowDefinitionRepository
        private lateinit var engine: WorkflowEngine
        private lateinit var txTemplate: TransactionTemplate
        private lateinit var seedService: YamlSeedService

        /** 테스트용 워크플로우 키 */
        private const val WIRING_WF_KEY = "wiring-test"

        @BeforeAll
        @JvmStatic
        fun setup() {
            // 2-phase Flyway — V201 이 issue_types FK 참조
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .target("200")
                .load()
                .migrate()

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS issue_types (
                            id          BIGSERIAL    PRIMARY KEY,
                            key         VARCHAR(30)  NOT NULL UNIQUE,
                            name        VARCHAR(255) NOT NULL,
                            is_standard BOOLEAN      NOT NULL DEFAULT FALSE,
                            created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                            updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                            deleted_at  TIMESTAMPTZ
                        )
                        """.trimIndent(),
                    )
                }
            }

            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .load()
                .migrate()

            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

            // production 구현체 직접 조합
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            workflowRepository = WorkflowRepository(dsl)
            workflowCache = WorkflowCache(workflowRepository, dsl)
            permissionResolver = AlwaysAllowPermissionResolver()
            spelEvaluator = SpelEvaluator(executor = spelExecutor, timeoutMillis = 5000L)
            validatorFactory = DefaultWorkflowValidatorFactory(permissionResolver, spelEvaluator)
            postActionFactory = DefaultWorkflowPostActionFactory()
            definitionRepo = DefaultWorkflowDefinitionRepository(dsl)
            engine = WorkflowEngine(workflowCache, validatorFactory, postActionFactory, definitionRepo)

            val txManager = DataSourceTransactionManager(dataSource)
            txTemplate = TransactionTemplate(txManager)

            val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
            // TestConfig 에 이미 factory 빈 있음 — 동일 인스턴스를 시드 서비스에 주입
            seedService = YamlSeedService(workflowRepository, dsl, DefaultResourceLoader(), yamlMapper, validatorFactory, postActionFactory)

            // 테스트용 워크플로우 시드
            // open → done: RequiredField("resolution") + SET_FIELD("assignee") + NOTIFY
            // open → closed: validator/postAction 없음 (단순 전이)
            seedTestWorkflow()
        }

        /**
         * wiring-test 워크플로우를 직접 시드한다.
         *
         * open → done 전이: RequiredField validator + SET_FIELD/NOTIFY postAction
         * open → closed 전이: validator/postAction 없음
         */
        private fun seedTestWorkflow() {
            val dto =
                WorkflowYamlDto(
                    key = WIRING_WF_KEY,
                    name = "결선 검증용 테스트 워크플로우",
                    description = "WorkflowEngineWiringIntegrationTest 전용 픽스처",
                    states =
                        listOf(
                            StateYamlDto(key = "open", name = "Open", category = "TODO", displayOrder = 1),
                            StateYamlDto(key = "done", name = "Done", category = "DONE", displayOrder = 2),
                            StateYamlDto(key = "closed", name = "Closed", category = "DONE", displayOrder = 3),
                        ),
                    transitions =
                        listOf(
                            TransitionYamlDto(
                                from = "open",
                                to = "done",
                                name = "Complete",
                                validators =
                                    listOf(
                                        ValidatorYamlDto(
                                            type = "RequiredField",
                                            config = mapOf("field" to "resolution"),
                                        ),
                                    ),
                                postActions =
                                    listOf(
                                        PostActionYamlDto(
                                            type = "SET_FIELD",
                                            config = mapOf("field" to "assignee", "value" to "actor"),
                                        ),
                                        PostActionYamlDto(
                                            type = "NOTIFY",
                                            config = mapOf("channel" to "slack", "recipients" to "team-dev"),
                                        ),
                                    ),
                            ),
                            TransitionYamlDto(
                                from = "open",
                                to = "closed",
                                name = "Cancel",
                            ),
                        ),
                )

            seedService.seedSingle(dto)
        }
    }

    // ── S1. validator 거부 — RequiredField(EXECUTION) + resolution 없음 → 예외 ──────

    /**
     * Given   open → done 전이에 RequiredField("resolution") validator 등록 (EXECUTION 페이즈)
     *         issueFields 에 resolution 없음
     * When    plan() 호출
     * Then    WorkflowValidatorFailureException 발생, field == "resolution"
     *
     * production 구현체 3종(DefaultWorkflowValidatorFactory, DefaultWorkflowDefinitionRepository,
     * DefaultWorkflowPostActionFactory) 모두 실제 인스턴스를 사용한다.
     */
    @Test
    @Order(1)
    fun `S1 - RequiredField EXECUTION 게이트가 걸린 전이를 resolution 없이 plan 하면 WorkflowValidatorFailureException 이 발생한다`() {
        val request =
            TransitionRequest(
                workflowKey = WIRING_WF_KEY,
                issueKey = "BTS-W1",
                fromStateKey = "open",
                toStateKey = "done",
                actorId = "user-wiring-test",
                actorRoles = setOf("MEMBER"),
                // resolution 필드 없음 — RequiredFieldValidator 가 거부해야 한다
                issueFields = mapOf("priority" to "HIGH"),
                version = 1L,
            )

        assertThatThrownBy {
            txTemplate.execute { engine.plan(request) }
        }
            .isInstanceOf(WorkflowValidatorFailureException::class.java)
            .satisfies({ ex ->
                val failure = ex as WorkflowValidatorFailureException
                assertThat(failure.validatorType).isEqualTo("RequiredField")
                assertThat(failure.field).isEqualTo("resolution")
            })
    }

    // ── S2. availableTransitions — EXECUTION 페이즈 전이가 목록에 포함됨 ─────────────

    /**
     * Given   open → done 전이에 RequiredField(EXECUTION 페이즈) validator 등록
     * When    availableTransitions(from = "open") 호출
     * Then    결과에 open → done 전이 포함 (EXECUTION 페이즈는 목록 노출에서 skip 됨)
     *         open → closed 전이도 포함
     *
     * AVAILABILITY 페이즈 validator 가 없으면 EXECUTION 게이트 전이도 버튼에 표시된다.
     */
    @Test
    @Order(2)
    fun `S2 - availableTransitions 는 EXECUTION 페이즈 게이트 전이도 목록에 포함한다`() {
        val request =
            AvailableTransitionsRequest(
                workflowKey = WIRING_WF_KEY,
                issueKey = "BTS-W2",
                fromStateKey = "open",
                actorId = "user-wiring-test",
                actorRoles = setOf("MEMBER"),
                issueFields = emptyMap(),
            )

        val result = txTemplate.execute { engine.availableTransitions(request) }

        assertThat(result).isInstanceOf(AvailableTransitionsResult.Success::class.java)
        val success = result as AvailableTransitionsResult.Success
        val toStateKeys = success.transitions.map { it.toStateKey }

        // open → done (EXECUTION 페이즈 RequiredField 있지만 목록에는 포함)
        assertThat(toStateKeys).contains("done")
        // open → closed (validator 없음 — 기본 포함)
        assertThat(toStateKeys).contains("closed")
    }

    // ── S3. PostAction plan 누적 ─────────────────────────────────────────────────

    /**
     * Given   open → done 전이에 SET_FIELD("assignee") + NOTIFY("slack") postAction 등록
     *         issueFields 에 resolution 포함 (RequiredField 통과)
     * When    plan() 호출
     * Then    TransitionPlan.fieldChanges 에 SET_FIELD 결과 누적
     *         TransitionPlan.emitEvents 에 NOTIFY 결과 누적
     *
     * production PostActionFactory 가 SET_FIELD/NOTIFY 인스턴스를 올바르게 생성하는지 검증한다.
     */
    @Test
    @Order(3)
    fun `S3 - SET_FIELD 와 NOTIFY PostAction 이 TransitionPlan 의 fieldChanges 와 emitEvents 에 각각 누적된다`() {
        val request =
            TransitionRequest(
                workflowKey = WIRING_WF_KEY,
                issueKey = "BTS-W3",
                fromStateKey = "open",
                toStateKey = "done",
                actorId = "user-wiring-test",
                actorRoles = setOf("MEMBER"),
                // resolution 포함 — RequiredFieldValidator 통과
                issueFields = mapOf("priority" to "HIGH", "resolution" to "Fixed"),
                version = 1L,
            )

        val plan = txTemplate.execute { engine.plan(request) }!!

        // SET_FIELD postAction 이 fieldChanges 에 누적
        assertThat(plan.fieldChanges).isNotEmpty
        val fieldChange = plan.fieldChanges.first()
        assertThat(fieldChange.field).isEqualTo("assignee")

        // NOTIFY postAction 이 emitEvents 에 누적
        assertThat(plan.emitEvents).isNotEmpty
        val event = plan.emitEvents.first()
        assertThat(event.type).isEqualTo("NotificationRequested")
        assertThat(event.payload["channel"]).isEqualTo("slack")
    }

    // ── S4. YAML seed → repo 조회 ─────────────────────────────────────────────────

    /**
     * Given   seedTestWorkflow() 로 wiring-test 워크플로우 적재 완료
     * When    DefaultWorkflowDefinitionRepository.findValidators("wiring-test", open→done)
     *         DefaultWorkflowDefinitionRepository.findPostActions("wiring-test", open→done)
     * Then    validators: [RequiredField] 1건
     *         postActions: [SET_FIELD, NOTIFY] 2건, display_order 순서 보존
     *
     * YAML seed 가 DB 에 올바르게 적재되고 DefaultWorkflowDefinitionRepository 가 올바르게 조회하는지 검증.
     */
    @Test
    @Order(4)
    fun `S4 - YAML 시드한 validator 와 post_action 을 DefaultWorkflowDefinitionRepository 로 조회할 수 있다`() {
        val transition = WorkflowTransition(fromStateKey = "open", toStateKey = "done", name = "Complete")

        val validators = definitionRepo.findValidators(WIRING_WF_KEY, transition)
        val postActions = definitionRepo.findPostActions(WIRING_WF_KEY, transition)

        // validators: RequiredField 1건
        assertThat(validators).hasSize(1)
        assertThat(validators[0].type).isEqualTo("RequiredField")
        assertThat(validators[0].config).containsEntry("field", "resolution")

        // postActions: SET_FIELD → NOTIFY 순서 (display_order 보존)
        assertThat(postActions).hasSize(2)
        assertThat(postActions[0].type).isEqualTo("SET_FIELD")
        assertThat(postActions[0].config).containsEntry("field", "assignee")
        assertThat(postActions[1].type).isEqualTo("NOTIFY")
        assertThat(postActions[1].config).containsEntry("channel", "slack")
    }
}
