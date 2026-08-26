// validator CRUD ↔ 엔진 통합 테스트 — 서비스로 건 규칙이 실제 전환을 막고, 풀면 통과한다 (Testcontainers)

package com.bts.workflow.validator

import com.bts.shared.workflow.AvailableTransitionsRequest
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.shared.workflow.TransitionRequest
import com.bts.workflow.adapter.AlwaysAllowPermissionResolver
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.exception.WorkflowValidatorFailureException
import com.bts.workflow.engine.DefaultWorkflowPostActionFactory
import com.bts.workflow.engine.DefaultWorkflowValidatorFactory
import com.bts.workflow.engine.WorkflowEngine
import com.bts.workflow.expression.SpelEvaluator
import com.bts.workflow.repository.DefaultWorkflowDefinitionRepository
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import com.bts.workflow.seed.StateYamlDto
import com.bts.workflow.seed.TransitionYamlDto
import com.bts.workflow.seed.WorkflowYamlDto
import com.bts.workflow.seed.YamlSeedService
import com.bts.workflow.transition.TransitionKeyResolver
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Executors

/**
 * FR-WF-06 FR-8 · C5 — 「저장한 규칙이 실제 전환에서 동작한다」.
 *
 * CRUD 가 DB 에 행을 넣는 것만으로는 부족하다. 엔진이 그 행을 읽어 전환을 실제로 막아야 한다.
 * 그래서 규칙은 **[ValidatorAdminService] 경유**로만 걸고 푼다 — 리포지토리에 직접 INSERT 하면
 * 저장 경로를 태우지 않은 채 통과해, 이 테스트가 증명하려는 것의 절반이 빠진다.
 *
 * ### 두 phase 를 서로 다른 경로로 덮는다
 * [RequiredFieldValidator] 의 phase 는 EXECUTION 이라 [WorkflowEngine.availableTransitions] 에서는
 * **평가되지 않는다**. 그것을 걸어 놓고 목록 조회로 검증하면 규칙이 없어도 초록이다 — 도달 불가
 * 조합을 지키는 가짜 그린이다(메모리 `unreachable-state-fixture-is-fake-green`). 그래서
 * `RequiredField` 는 [WorkflowEngine.plan] 실행 경로로, `not-status-category`
 * ([NotStatusCategoryValidator], 기본 AVAILABILITY) 는 목록 경로로 각각 검증한다.
 *
 * ### 캐시 무효화를 넣지 않는다
 * validator 는 캐싱되지 않는다. [WorkflowCache] 가 담는 것은 `Workflow` aggregate 뿐이고 전환
 * 실행·열거 시 [DefaultWorkflowDefinitionRepository] 가 DB 를 직접 친다. 여기서 `invalidate` 를
 * 부르면 캐시 때문에 안 보이던 결함까지 가려 버린다.
 *
 * ### 한 테스트 안에서 왕복시킨다 (create → 차단 단언 → delete → 통과 단언)
 * 「걸면 막힌다」와 「지우면 풀린다」를 다른 테스트로 나누면, 후자의 최종 단언이 **규칙이 애초에
 * 그 전환에 붙지 않았어도 참**이 된다 — 규칙 없는 기본 상태에서 이미 성립하는 값이라
 * 「delete 가 동작했다」와 「create 가 엉뚱한 전환에 붙었다」를 구별하지 못한다. 왕복으로 접으면
 * create 와 delete 중 어느 쪽이 죽어도 그 자리에서 red 다.
 *
 * ### 픽스처 격리
 * 전환 2개를 [BeforeAll] 에 심고 phase 별로 하나씩 쓴다. 시나리오마다 새 전환을 만들면 캐시된
 * `Workflow` 에 그 전환이 없어 열거·실행이 빗나간다. 건 규칙은 [clearRules] 가 매번 되푼다.
 */
@Testcontainers
class ValidatorEngineIntegrationTest {
    companion object {
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest").asCompatibleSubstituteFor("postgres")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_validator_engine_test")
                .withUsername("bts")
                .withPassword("bts_test")

        private const val WF_KEY = "validator-engine-wf"
        private const val FROM_STATE = "open"
        private const val EXECUTION_TO_STATE = "done"
        private const val AVAILABILITY_TO_STATE = "closed"

        // `not-status-category` 는 **출발** 상태 카테고리를 본다.
        private const val FROM_CATEGORY = "TODO"

        // `CustomExpression` 은 이 경로에서 쓰지 않지만 팩토리가 SpelEvaluator 를 요구한다.
        private val spelExecutor = Executors.newCachedThreadPool()

        lateinit var service: ValidatorAdminService
        lateinit var engine: WorkflowEngine
        lateinit var txTemplate: TransactionTemplate

        /** phase 별 전용 전환. 값은 경로 세그먼트와 같은 전환 id 문자열이다. */
        lateinit var executionTransitionKey: String
        lateinit var availabilityTransitionKey: String

        @BeforeAll
        @JvmStatic
        fun setup() {
            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)

            // V201+ 가 issue_types 를 FK 참조한다. 그 스텁을 사이에 끼우려 마이그레이션을 2단계로 나눈다.
            flyway().target("200").load().migrate()
            createIssueTypesStub()
            flyway().load().migrate()
            val validatorFactory =
                DefaultWorkflowValidatorFactory(AlwaysAllowPermissionResolver(), SpelEvaluator(spelExecutor))
            val workflowRepository = WorkflowRepository(dsl)
            engine =
                WorkflowEngine(
                    WorkflowCache(workflowRepository, dsl),
                    validatorFactory,
                    DefaultWorkflowPostActionFactory(),
                    DefaultWorkflowDefinitionRepository(dsl),
                )
            txTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))
            service =
                ValidatorAdminService(
                    ValidatorRepository(dsl, ObjectMapper()),
                    validatorFactory,
                    TransitionKeyResolver(dsl),
                )
            seedWorkflow(dsl, validatorFactory)
            executionTransitionKey = seededTransitionKey(workflowRepository, EXECUTION_TO_STATE)
            availabilityTransitionKey = seededTransitionKey(workflowRepository, AVAILABILITY_TO_STATE)
        }

        private fun flyway() =
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking", "classpath:db/migration/project-workflow")

        /**
         * issue_types 스텁 — V201+ FK 통과용. `DATA.md §5` 가 SQL 문자열을 jOOQ 에 넘기는 형태를
         * 이름으로 지목해 금지하므로, 형제 `ValidatorRepositoryIntegrationTest` 와 같이 JDBC 로 친다.
         */
        private fun createIssueTypesStub() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    """
                    CREATE TABLE IF NOT EXISTS issue_types (
                        id BIGSERIAL PRIMARY KEY, key VARCHAR(30) NOT NULL UNIQUE,
                        name VARCHAR(255) NOT NULL, is_standard BOOLEAN NOT NULL DEFAULT FALSE,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)
                    """.trimIndent(),
                ).use { it.execute() }
            }
        }

        /** 상태 3개 · 전환 2개. **전환에 규칙을 달지 않는다** — 규칙은 전부 서비스가 걸어야 한다. */
        private fun seedWorkflow(
            dsl: DSLContext,
            validatorFactory: DefaultWorkflowValidatorFactory,
        ) {
            YamlSeedService(
                dsl,
                DefaultResourceLoader(),
                validatorFactory,
                DefaultWorkflowPostActionFactory(),
                SchemeIssueTypeMappingRepository(dsl),
            ).seedSingle(
                WorkflowYamlDto(
                    key = WF_KEY,
                    name = "validator 엔진 통합 테스트 워크플로우",
                    description = "ValidatorEngineIntegrationTest 전용 픽스처",
                    states =
                        listOf(
                            StateYamlDto(FROM_STATE, "Open", FROM_CATEGORY, 1),
                            StateYamlDto(EXECUTION_TO_STATE, "Done", "DONE", 2),
                            StateYamlDto(AVAILABILITY_TO_STATE, "Closed", "DONE", 3),
                        ),
                    transitions =
                        listOf(
                            TransitionYamlDto(from = FROM_STATE, to = EXECUTION_TO_STATE, name = "완료"),
                            TransitionYamlDto(from = FROM_STATE, to = AVAILABILITY_TO_STATE, name = "종료"),
                        ),
                ),
            )
        }

        /**
         * 시드가 심은 전환의 **DB 가 정한 id**. 규칙 경로도 엔진도 전환을 id 로 해석하므로, 픽스처가
         * UUID 를 지어내면 규칙이 아무 전환에도 안 붙고 아래 단언이 통째로 공허해진다.
         */
        private fun seededTransitionKey(
            repository: WorkflowRepository,
            toStateKey: String,
        ): String {
            val workflow = repository.findByKey(WF_KEY) ?: error("시드된 워크플로우가 없다: $WF_KEY")
            val match =
                workflow.transitions.firstOrNull { it.fromStateKey == FROM_STATE && it.toStateKey == toStateKey }
            return (match ?: error("시드된 전환이 없다: $WF_KEY $FROM_STATE→$toStateKey")).id.toString()
        }
    }

    /** 시나리오가 건 규칙을 전부 되푼다. 잔여 규칙이 남으면 다음 시나리오가 실행 순서에 기대게 된다. */
    @AfterEach
    fun clearRules() {
        listOf(executionTransitionKey, availabilityTransitionKey).forEach { transitionKey ->
            service.listForTransition(WF_KEY, transitionKey).forEach { row ->
                service.delete(WF_KEY, transitionKey, row.id)
            }
        }
    }

    /**
     * Given `open → done` 전환에 규칙이 하나도 없다.
     * When  `RequiredField("resolution")` 을 서비스로 걸었다가 다시 서비스로 지우고, 그 사이사이
     *       resolution 없이 그 전환을 **실행**한다.
     * Then  걸린 동안은 [WorkflowValidatorFailureException] 으로 차단되고, 지운 뒤에는 계획이 나온다.
     */
    @Test
    fun `RequiredField 를 걸면 실행이 차단되고 지우면 통과한다`() {
        val ruleId = applyRule(executionTransitionKey, "RequiredField", mapOf("field" to "resolution"))

        assertThatThrownBy { txTemplate.execute { engine.plan(executionRequest()) } }
            .isInstanceOf(WorkflowValidatorFailureException::class.java)
            .hasFieldOrPropertyWithValue("validatorType", "RequiredField")
            .hasFieldOrPropertyWithValue("field", "resolution")

        service.delete(WF_KEY, executionTransitionKey, ruleId)

        val plan = txTemplate.execute { engine.plan(executionRequest()) }!!
        assertThat(plan.toStateKey).isEqualTo(EXECUTION_TO_STATE)
    }

    /**
     * Given `open → closed` 전환에 규칙이 하나도 없다.
     * When  출발 카테고리(`TODO`)를 막는 `not-status-category` 를 걸었다가 다시 지우고, 그 사이사이
     *       전환 **목록**을 조회한다.
     * Then  걸린 동안은 그 전환만 목록에서 빠지고, 지운 뒤에는 다시 나온다. 다른 전환이 내내 남는
     *       것까지 함께 확인해 「목록이 통째로 비었다」가 초록으로 통과하는 것을 막는다.
     */
    @Test
    fun `not-status-category 를 걸면 목록에서 사라지고 지우면 다시 나온다`() {
        val ruleId =
            applyRule(availabilityTransitionKey, "not-status-category", mapOf("category" to FROM_CATEGORY))

        assertThat(availableToStateKeys())
            .contains(EXECUTION_TO_STATE)
            .doesNotContain(AVAILABILITY_TO_STATE)

        service.delete(WF_KEY, availabilityTransitionKey, ruleId)

        assertThat(availableToStateKeys()).contains(EXECUTION_TO_STATE, AVAILABILITY_TO_STATE)
    }

    /**
     * 규칙 하나를 **서비스 경유**로 건다. 두 시나리오의 유일한 규칙 생성 지점이라, 아래 `create`
     * 한 줄을 죽이면 둘 다 「규칙 없음」으로 떨어져 red 가 된다.
     */
    private fun applyRule(
        transitionKey: String,
        type: String,
        config: Map<String, Any?>,
    ): UUID = service.create(WF_KEY, transitionKey, type, config, 0).id

    /** resolution 이 비어 있는 `open → done` 전환 실행 요청. */
    private fun executionRequest(): TransitionRequest =
        TransitionRequest(
            workflowKey = WF_KEY,
            issueKey = "BTS-VE1",
            fromStateKey = FROM_STATE,
            toStateKey = EXECUTION_TO_STATE,
            actorId = "user-validator-engine",
            actorRoles = setOf("MEMBER"),
            issueFields = mapOf("priority" to "HIGH"),
            version = 1L,
        )

    /** `open` 에서 열거되는 전환의 도착 상태 키 목록. */
    private fun availableToStateKeys(): List<String> {
        val request =
            AvailableTransitionsRequest(
                workflowKey = WF_KEY,
                fromStateKey = FROM_STATE,
                issueKey = "BTS-VE2",
                actorId = "user-validator-engine",
                actorRoles = setOf("MEMBER"),
                issueFields = emptyMap(),
            )
        val result = txTemplate.execute { engine.availableTransitions(request) }
        return (result as? AvailableTransitionsResult.Success)
            ?.transitions
            ?.map { it.toStateKey }
            ?: error("워크플로우를 못 찾았다: $WF_KEY (result=$result)")
    }
}
