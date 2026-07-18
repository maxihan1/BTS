// DONE 카테고리 진입 전이에 RequiredField(resolution) validator 시드 검증 — FR-IS-07 Task B7

package com.bts.workflow.seed

import com.bts.shared.workflow.AvailableTransitionsRequest
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.shared.workflow.TransitionRequest
import com.bts.workflow.adapter.AlwaysAllowPermissionResolver
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.exception.WorkflowValidatorFailureException
import com.bts.workflow.engine.DefaultWorkflowPostActionFactory
import com.bts.workflow.engine.DefaultWorkflowValidatorFactory
import com.bts.workflow.engine.WorkflowEngine
import com.bts.workflow.expression.SpelEvaluator
import com.bts.workflow.repository.DefaultWorkflowDefinitionRepository
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
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
import org.slf4j.LoggerFactory
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
 * DONE 카테고리 진입 전이에 RequiredField(resolution) validator 가 시드되는지 검증한다.
 *
 * Testcontainers PostgreSQL + Flyway + YamlSeedService (production 구현체) 전체 스택.
 * Spring ApplicationContext 없이 의존 객체를 직접 조합한다.
 *
 * ## 검증 범위 (FR-IS-07 Task B7 명세)
 *
 * ### S1. 표준 4 워크플로우 DONE 진입 전이마다 RequiredField(resolution) validator DB 존재
 * - software-default: in_review→done, done→closed, open→closed
 * - bug-tracking: in_progress→resolved, resolved→closed
 * - simple: doing→done
 * - kanban-basic: in_progress→done
 *
 * ### S2. plan() — resolution 없이 DONE 전이 시 WorkflowValidatorFailureException
 * Given  software-default 워크플로우, issueFields 에 resolution 없음
 * When   in_review→done plan() 호출
 * Then   WorkflowValidatorFailureException, field == "resolution"
 *
 * ### S3. availableTransitions — EXECUTION 페이즈 validator 가 있어도 DONE 전이가 목록에 포함된다 (A3)
 * Given  software-default 워크플로우, from = "in_review"
 * When   availableTransitions 호출
 * Then   결과에 toStateKey == "done" 전이 포함
 *
 * ## TDD 근거
 * B7 RED — production YAML 에 validators 미추가 상태에서 S1 을 실행하면 RequiredField 가 0건이어서 실패한다.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DoneResolutionValidatorSeedTest {
    companion object {
        private val log = LoggerFactory.getLogger(DoneResolutionValidatorSeedTest::class.java)

        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_done_res_test")
                .withUsername("bts")
                .withPassword("bts_done_res_test")

        private val spelExecutor = Executors.newCachedThreadPool()

        private lateinit var dsl: DSLContext
        private lateinit var workflowRepository: WorkflowRepository
        private lateinit var definitionRepo: DefaultWorkflowDefinitionRepository
        private lateinit var engine: WorkflowEngine
        private lateinit var txTemplate: TransactionTemplate
        private lateinit var seedService: YamlSeedService

        @Suppress("LongMethod")
        @BeforeAll
        @JvmStatic
        fun setup() {
            // Flyway 2단계 — V201 이 issue_types FK 참조
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
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)

            val validatorFactory =
                DefaultWorkflowValidatorFactory(
                    permissionResolver = AlwaysAllowPermissionResolver(),
                    spelEvaluator = SpelEvaluator(executor = spelExecutor, timeoutMillis = 5000L),
                )
            val postActionFactory = DefaultWorkflowPostActionFactory()

            workflowRepository = WorkflowRepository(dsl)
            definitionRepo = DefaultWorkflowDefinitionRepository(dsl)

            val workflowCache = WorkflowCache(workflowRepository, dsl)
            engine = WorkflowEngine(workflowCache, validatorFactory, postActionFactory, definitionRepo)

            val txManager = DataSourceTransactionManager(dataSource)
            txTemplate = TransactionTemplate(txManager)

            seedService =
                YamlSeedService(
                    workflowRepository,
                    dsl,
                    DefaultResourceLoader(),
                    validatorFactory,
                    postActionFactory,
                    SchemeIssueTypeMappingRepository(dsl),
                )

            // 표준 4 워크플로우 시드
            seedService.seedAll()
        }
    }

    // ── S1. DONE 진입 전이마다 RequiredField(resolution) validator 존재 ──────────────

    /**
     * software-default: in_review→done 전이에 RequiredField(resolution) validator 가 존재한다.
     *
     * Given  software-default 워크플로우 시드 완료
     * When   findValidators("software-default", in_review→done) 조회
     * Then   RequiredField 타입 validator 가 1건 이상 존재하고, config.field == "resolution"
     */
    @Test
    @Order(1)
    fun `software-default in_review→done 전이에 RequiredField resolution validator 가 존재한다`() {
        val transition = WorkflowTransition(fromStateKey = "in_review", toStateKey = "done", name = "Approve")
        val validators = definitionRepo.findValidators("software-default", transition)

        val requiredField = validators.find { it.type == "RequiredField" }
        assertThat(requiredField)
            .`as`("in_review→done 전이에 RequiredField validator 가 없습니다. B7 YAML 추가가 필요합니다.")
            .isNotNull
        assertThat(requiredField!!.config).containsEntry("field", "resolution")

        log.info("S1-1 통과 — software-default in_review→done RequiredField 확인: {}", validators.map { it.type })
    }

    /**
     * software-default: done→closed 전이에 RequiredField(resolution) validator 가 존재한다.
     */
    @Test
    @Order(2)
    fun `software-default done→closed 전이에 RequiredField resolution validator 가 존재한다`() {
        val transition = WorkflowTransition(fromStateKey = "done", toStateKey = "closed", name = "Close")
        val validators = definitionRepo.findValidators("software-default", transition)

        val requiredField = validators.find { it.type == "RequiredField" }
        assertThat(requiredField)
            .`as`("done→closed 전이에 RequiredField validator 가 없습니다. B7 YAML 추가가 필요합니다.")
            .isNotNull
        assertThat(requiredField!!.config).containsEntry("field", "resolution")

        log.info("S1-2 통과 — software-default done→closed RequiredField 확인: {}", validators.map { it.type })
    }

    /**
     * software-default: open→closed 전이에 RequiredField(resolution) validator 가 존재한다.
     */
    @Test
    @Order(3)
    fun `software-default open→closed 전이에 RequiredField resolution validator 가 존재한다`() {
        val transition = WorkflowTransition(fromStateKey = "open", toStateKey = "closed", name = "Cancel")
        val validators = definitionRepo.findValidators("software-default", transition)

        val requiredField = validators.find { it.type == "RequiredField" }
        assertThat(requiredField)
            .`as`("open→closed 전이에 RequiredField validator 가 없습니다. B7 YAML 추가가 필요합니다.")
            .isNotNull
        assertThat(requiredField!!.config).containsEntry("field", "resolution")

        log.info("S1-3 통과 — software-default open→closed RequiredField 확인: {}", validators.map { it.type })
    }

    /**
     * bug-tracking: in_progress→resolved 전이에 RequiredField(resolution) validator 가 존재한다.
     */
    @Test
    @Order(4)
    fun `bug-tracking in_progress→resolved 전이에 RequiredField resolution validator 가 존재한다`() {
        val transition = WorkflowTransition(fromStateKey = "in_progress", toStateKey = "resolved", name = "Resolve")
        val validators = definitionRepo.findValidators("bug-tracking", transition)

        val requiredField = validators.find { it.type == "RequiredField" }
        assertThat(requiredField)
            .`as`("bug-tracking in_progress→resolved 전이에 RequiredField validator 가 없습니다. B7 YAML 추가가 필요합니다.")
            .isNotNull
        assertThat(requiredField!!.config).containsEntry("field", "resolution")

        log.info("S1-4 통과 — bug-tracking in_progress→resolved RequiredField 확인: {}", validators.map { it.type })
    }

    /**
     * bug-tracking: resolved→closed 전이에 RequiredField(resolution) validator 가 존재한다.
     */
    @Test
    @Order(5)
    fun `bug-tracking resolved→closed 전이에 RequiredField resolution validator 가 존재한다`() {
        val transition = WorkflowTransition(fromStateKey = "resolved", toStateKey = "closed", name = "Close")
        val validators = definitionRepo.findValidators("bug-tracking", transition)

        val requiredField = validators.find { it.type == "RequiredField" }
        assertThat(requiredField)
            .`as`("bug-tracking resolved→closed 전이에 RequiredField validator 가 없습니다. B7 YAML 추가가 필요합니다.")
            .isNotNull
        assertThat(requiredField!!.config).containsEntry("field", "resolution")

        log.info("S1-5 통과 — bug-tracking resolved→closed RequiredField 확인: {}", validators.map { it.type })
    }

    /**
     * simple: doing→done 전이에 RequiredField(resolution) validator 가 존재한다.
     */
    @Test
    @Order(6)
    fun `simple doing→done 전이에 RequiredField resolution validator 가 존재한다`() {
        val transition = WorkflowTransition(fromStateKey = "doing", toStateKey = "done", name = "Complete")
        val validators = definitionRepo.findValidators("simple", transition)

        val requiredField = validators.find { it.type == "RequiredField" }
        assertThat(requiredField)
            .`as`("simple doing→done 전이에 RequiredField validator 가 없습니다. B7 YAML 추가가 필요합니다.")
            .isNotNull
        assertThat(requiredField!!.config).containsEntry("field", "resolution")

        log.info("S1-6 통과 — simple doing→done RequiredField 확인: {}", validators.map { it.type })
    }

    /**
     * kanban-basic: in_progress→done 전이에 RequiredField(resolution) validator 가 존재한다.
     */
    @Test
    @Order(7)
    fun `kanban-basic in_progress→done 전이에 RequiredField resolution validator 가 존재한다`() {
        val transition = WorkflowTransition(fromStateKey = "in_progress", toStateKey = "done", name = "Finish")
        val validators = definitionRepo.findValidators("kanban-basic", transition)

        val requiredField = validators.find { it.type == "RequiredField" }
        assertThat(requiredField)
            .`as`("kanban-basic in_progress→done 전이에 RequiredField validator 가 없습니다. B7 YAML 추가가 필요합니다.")
            .isNotNull
        assertThat(requiredField!!.config).containsEntry("field", "resolution")

        log.info("S1-7 통과 — kanban-basic in_progress→done RequiredField 확인: {}", validators.map { it.type })
    }

    // ── S2. plan() — resolution 없이 DONE 전이 시 WorkflowValidatorFailureException ──

    /**
     * Given  software-default 워크플로우, in_review→done 전이에 RequiredField(resolution) validator 존재
     *         issueFields 에 resolution 없음
     * When   plan() 호출
     * Then   WorkflowValidatorFailureException, field == "resolution"
     */
    @Test
    @Order(8)
    fun `S2 - resolution 없이 DONE 전이 plan 하면 WorkflowValidatorFailureException 이 발생한다`() {
        val request =
            TransitionRequest(
                workflowKey = "software-default",
                issueKey = "BTS-B7-1",
                fromStateKey = "in_review",
                toStateKey = "done",
                actorId = "user-b7-test",
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

        log.info("S2 통과 — resolution 없는 DONE 전이 plan 시 WorkflowValidatorFailureException 발생 확인")
    }

    // ── S3. availableTransitions — EXECUTION 페이즈 validator 가 있어도 DONE 전이가 목록에 포함 ──

    /**
     * A3 요구사항: EXECUTION 페이즈 RequiredField validator 가 있어도
     * availableTransitions 결과에 DONE 전이가 포함된다.
     *
     * Given  software-default 워크플로우, from = "in_review"
     * When   availableTransitions 호출
     * Then   결과에 toStateKey == "done" 전이 포함
     */
    @Test
    @Order(9)
    fun `S3 - availableTransitions 는 EXECUTION 페이즈 RequiredField 가 있어도 DONE 전이를 목록에 포함한다`() {
        val request =
            AvailableTransitionsRequest(
                workflowKey = "software-default",
                issueKey = "BTS-B7-2",
                fromStateKey = "in_review",
                actorId = "user-b7-test",
                actorRoles = setOf("MEMBER"),
                issueFields = emptyMap(),
            )

        val result = txTemplate.execute { engine.availableTransitions(request) }

        assertThat(result).isInstanceOf(AvailableTransitionsResult.Success::class.java)
        val success = result as AvailableTransitionsResult.Success
        val toStateKeys = success.transitions.map { it.toStateKey }

        // DONE 전이(done)가 포함되어야 한다 — EXECUTION 페이즈는 목록 노출에서 skip
        assertThat(toStateKeys)
            .`as`("in_review 상태에서 done 이 availableTransitions 에 포함되어야 합니다 (A3).")
            .contains("done")

        log.info("S3 통과 — availableTransitions 에 DONE 전이 포함 확인: toStateKeys={}", toStateKeys)
    }
}
