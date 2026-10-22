// DONE 카테고리 진입 전환의 해결 결과 필수 시드 검증 — 화면이 정본이고 RequiredField(resolution) 검증기는 없다 (FR-IS-07 B7 · FR-WF-01 D11)

package com.bts.workflow.seed

import com.bts.shared.workflow.AvailableTransitionsRequest
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.shared.workflow.TransitionRequest
import com.bts.workflow.adapter.AlwaysAllowPermissionResolver
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.DraftScreenFieldDto
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.TransitionKind
import com.bts.workflow.domain.exception.WorkflowValidatorFailureException
import com.bts.workflow.engine.DefaultWorkflowPostActionFactory
import com.bts.workflow.engine.DefaultWorkflowValidatorFactory
import com.bts.workflow.engine.WorkflowEngine
import com.bts.workflow.expression.SpelEvaluator
import com.bts.workflow.repository.DefaultWorkflowDefinitionRepository
import com.bts.workflow.repository.TransitionScreenRepository
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import com.bts.workflow.testsupport.SharedPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.util.concurrent.Executors

/**
 * DONE 카테고리 진입 전환의 해결 결과 필수가 시드되는지 검증한다 — D11 부터 검증기가 아니라 전환 화면이다.
 *
 * SharedPostgres(Testcontainers 공용 PostgreSQL) + YamlSeedService (production 구현체) 전체 스택.
 * Spring ApplicationContext 없이 의존 객체를 직접 조합한다.
 *
 * ## 검증 범위 (FR-IS-07 Task B7 명세)
 *
 * ### S1. 표준 4 워크플로우 어느 전환에도 RequiredField(resolution) validator 가 없다 (FR-WF-01 D11)
 *
 * ### S2. plan() — resolution 없이 DONE 전환 시 WorkflowValidatorFailureException
 * Given  software-default 워크플로우, issueFields 에 resolution 없음
 * When   in_review→done plan() 호출
 * Then   WorkflowValidatorFailureException(TransitionScreen), field == "resolution"
 *
 * ### S3. availableTransitions — EXECUTION 페이즈 validator 가 있어도 DONE 전환이 목록에 포함된다 (A3)
 * Given  software-default 워크플로우, from = "in_review"
 * When   availableTransitions 호출
 * Then   결과에 toStateKey == "done" 전환 포함
 *
 * ## TDD 근거
 * B7 RED — production YAML 에 validators 미추가 상태에서 실패하던 자리다. D11 부터 강제는 화면(S4)이 하고 S1 은 검증기가 없음을 잰다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DoneResolutionValidatorSeedTest {
    companion object {
        private val log = LoggerFactory.getLogger(DoneResolutionValidatorSeedTest::class.java)

        val postgres = SharedPostgres.freshDatabase()

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

    // ── S1. 해결 결과 검증기는 시드되지 않는다 — 필수의 정본은 화면이다 (FR-WF-01 D11) ──────────────

    /**
     * Maxi 2026-10-22 「검증기를 걷는다」. 표준 4 워크플로우 어느 전환에도 `RequiredField(resolution)` 이 없다.
     * 강제는 S4 의 화면 「해결 결과 필수」가 하고, S2 가 그 강제를 잰다.
     */
    @Test
    @Order(1)
    fun `S1 - 표준 워크플로우 어느 전환에도 RequiredField resolution 검증기가 없다`() {
        listOf("software-default", "bug-tracking", "simple", "kanban-basic").forEach { key ->
            val workflow = workflowRepository.findByKey(key) ?: error("시드된 워크플로우가 없다: $key")
            workflow.transitions.forEach { transition ->
                assertThat(definitionRepo.findValidators(key, transition))
                    .`as`("$key '${transition.name}' 의 검증기")
                    .noneMatch { it.type == "RequiredField" && it.config["field"] == "resolution" }
            }
        }
    }

    // ── S2. plan() — resolution 없이 DONE 전환 시 WorkflowValidatorFailureException ──

    /**
     * Given  software-default 워크플로우, in_review→done 전환에 해결 결과 필수 화면 존재
     *         issueFields 에 resolution 없음
     * When   plan() 호출
     * Then   WorkflowValidatorFailureException, field == "resolution"
     */
    @Test
    @Order(8)
    fun `S2 - resolution 없이 DONE 전환 plan 하면 WorkflowValidatorFailureException 이 발생한다`() {
        val request =
            TransitionRequest(
                workflowKey = "software-default",
                issueKey = "BTS-B7-1",
                fromStateKey = "in_review",
                toStateKey = "done",
                actorId = "user-b7-test",
                actorRoles = setOf("MEMBER"),
                // resolution 필드 없음 — 화면의 「해결 결과 필수」가 거부해야 한다
                issueFields = mapOf("priority" to "HIGH"),
                version = 1L,
            )

        assertThatThrownBy {
            txTemplate.execute { engine.plan(request) }
        }
            .isInstanceOf(WorkflowValidatorFailureException::class.java)
            .satisfies({ ex ->
                val failure = ex as WorkflowValidatorFailureException
                // 검증기가 아니라 화면 필수가 막는다 (D11)
                assertThat(failure.validatorType).isEqualTo("TransitionScreen")
                assertThat(failure.field).isEqualTo("resolution")
            })

        log.info("S2 통과 — resolution 없는 DONE 전환 plan 시 WorkflowValidatorFailureException 발생 확인")
    }

    // ── S3. availableTransitions — EXECUTION 페이즈 validator 가 있어도 DONE 전환이 목록에 포함 ──

    /**
     * A3 요구사항: EXECUTION 페이즈 RequiredField validator 가 있어도
     * availableTransitions 결과에 DONE 전환이 포함된다.
     *
     * Given  software-default 워크플로우, from = "in_review"
     * When   availableTransitions 호출
     * Then   결과에 toStateKey == "done" 전환 포함
     */
    @Test
    @Order(9)
    fun `S3 - availableTransitions 는 해결 결과 필수 화면이 있어도 DONE 전환을 목록에 포함한다`() {
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

        // DONE 전환(done)가 포함되어야 한다 — EXECUTION 페이즈는 목록 노출에서 skip
        assertThat(toStateKeys)
            .`as`("in_review 상태에서 done 이 availableTransitions 에 포함되어야 합니다 (A3).")
            .contains("done")

        log.info("S3 통과 — availableTransitions 에 DONE 전환 포함 확인: toStateKeys={}", toStateKeys)
    }

    // ── S4. 전환 화면 (FR-WF-01 D10) — 빈 DB 도 V212 백필과 같은 화면을 갖는다 ──────────

    /**
     * 표준 4 워크플로우에서 DONE 범주로 들어가는 비-INITIAL 전환은 전부, 그리고 그것만 「해결 결과 필수」
     * 화면을 갖는다. 기존 DB 는 V212 가 같은 규칙으로 채우므로 둘이 갈리면 설치마다 해결 모달이 다르게 뜬다.
     */
    @Test
    @Order(10)
    fun `S4 - DONE 으로 들어가는 전환만 해결 결과 필수 화면을 갖는다`() {
        val screenRepository = TransitionScreenRepository(dsl)
        listOf("software-default", "bug-tracking", "simple", "kanban-basic").forEach { key ->
            val workflow = workflowRepository.findByKey(key) ?: error("시드된 워크플로우가 없다: $key")
            val doneKeys = workflow.states.filter { it.category == StateCategory.DONE }.map { it.key }.toSet()
            val screens =
                screenRepository.findByWorkflowId(workflowRepository.findIdByKey(key) ?: error("id 없음: $key"))

            workflow.transitions.forEach { transition ->
                val expected =
                    if (transition.kind != TransitionKind.INITIAL && transition.toStateKey in doneKeys) {
                        listOf(DraftScreenFieldDto("resolution", required = true))
                    } else {
                        emptyList()
                    }
                assertThat(screens[transition.id].orEmpty())
                    .`as`("$key '${transition.name}' 의 전환 화면")
                    .isEqualTo(expected)
            }
        }
    }
}
