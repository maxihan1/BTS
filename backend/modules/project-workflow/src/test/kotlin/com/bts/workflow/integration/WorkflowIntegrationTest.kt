// project-workflow BC 통합 테스트 — S1~S6 + cache invalidate (Task 35, FR-WF-01)

package com.bts.workflow.integration

import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.dto.DomainEvent
import com.bts.workflow.domain.dto.PostActionPlan
import com.bts.workflow.domain.dto.TransitionRequest
import com.bts.workflow.domain.exception.WorkflowValidatorFailureException
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.domain.spi.WorkflowPostAction
import com.bts.workflow.domain.spi.WorkflowValidator
import com.bts.workflow.engine.PostActionConfig
import com.bts.workflow.engine.ValidatorConfig
import com.bts.workflow.engine.WorkflowDefinitionRepository
import com.bts.workflow.engine.WorkflowEngine
import com.bts.workflow.engine.WorkflowPostActionFactory
import com.bts.workflow.engine.WorkflowValidatorFactory
import com.bts.workflow.expression.SpelEvaluator
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.validator.CustomExpressionValidator
import com.bts.workflow.validator.RequiredFieldValidator
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Executors

/**
 * project-workflow BC 통합 테스트 — S1~S6 + cache invalidate (S7).
 *
 * Testcontainers PostgreSQL 1 컨테이너 위에서 Flyway V001 + seed 적재 후
 * WorkflowEngine 전체 스택을 검증한다.
 * Spring ApplicationContext 없이 의존 객체를 직접 조합한다 (기존 테스트 패턴 유지).
 *
 * ### 시나리오 목록
 * - S1. happy path — 표준 워크플로우(software-default) open→in_progress 정상 전이
 * - S2. permission fail — PermissionValidator Fail 반환 시 WorkflowValidatorFailureException
 * - S3. required field fail — RequiredFieldValidator: 필수 필드 누락 → WorkflowValidatorFailureException
 * - S4. SpEL pass — CustomExpressionValidator: SpEL 표현식 true → 전이 성공
 * - S5. PostAction emitEvents — Notify PostAction 이 DomainEvent 발행
 * - S6. 동시 전이 낙관락 — 동일 요청 두 번 실행, plan() 멱등성 검증
 * - S7. cache invalidate — invalidate 후 다음 findByKey 가 DB 재조회(miss)
 *
 * 참조. FR-WF-01 Task 35 / docs/plans/2026-05-21-project-workflow-bc-fr-wf-01-fsm-1-pr.md
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WorkflowIntegrationTest {

    companion object {

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /**
         * S4 SpEL 평가용 공유 executor.
         *
         * SpelEvaluator 는 매번 newSingleThreadExecutor() 생성 시 JVM 워밍업 이전에
         * 50ms timeout 을 초과할 수 있다. 공유 executor 로 초기화 비용을 1회만 지불한다.
         * timeoutMillis 는 통합 테스트 JVM 워밍업을 허용해 5000ms 로 설정한다.
         */
        private val spelExecutor = Executors.newSingleThreadExecutor()

        private lateinit var workflowRepository: WorkflowRepository
        private lateinit var workflowCache: WorkflowCache
        private lateinit var txTemplate: TransactionTemplate
        private lateinit var dsl: org.jooq.DSLContext

        @BeforeAll
        @JvmStatic
        fun setup() {
            // Flyway — DB 스키마 변경을 버전 관리하는 도구
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

            // jOOQ DSLContext — SQL을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            workflowRepository = WorkflowRepository(dsl)
            workflowCache = WorkflowCache(workflowRepository, dsl)

            // TransactionTemplate — Spring 트랜잭션을 프로그래밍 방식으로 관리
            // WorkflowEngine.plan()은 Propagation.MANDATORY이므로 활성 트랜잭션이 필수
            val txManager = DataSourceTransactionManager(dataSource)
            txTemplate = TransactionTemplate(txManager)

            WorkflowFixtures.seedSoftwareDefault(postgres.jdbcUrl, postgres.username, postgres.password)
        }

        /**
         * 테스트용 WorkflowEngine 빌더.
         *
         * validator/postaction 설정 조회는 MockK [WorkflowDefinitionRepository] 로 대체한다.
         * WorkflowCache 는 BeforeAll 에서 초기화한 실제 인스턴스를 공유한다.
         */
        fun buildEngine(
            definitionRepo: WorkflowDefinitionRepository,
            validatorFactory: WorkflowValidatorFactory,
            postActionFactory: WorkflowPostActionFactory,
        ): WorkflowEngine = WorkflowEngine(workflowCache, validatorFactory, postActionFactory, definitionRepo)
    }

    // ── S1. happy path — open→in_progress 정상 전이 ─────────────────────────────

    /**
     * Given  software-default 워크플로우 DB 적재 완료, validator/postAction 없음
     * When   open→in_progress "Start Work" 전이 요청
     * Then   TransitionPlan.toStateKey == "in_progress", fieldChanges/emitEvents 빈 리스트
     */
    @Test
    @Order(1)
    fun `S1 - happy path - open 에서 in_progress 로 정상 전이된다`() {
        // Given
        val definitionRepo = mockk<WorkflowDefinitionRepository> {
            every { findValidators(any()) } returns emptyList()
            every { findPostActions(any()) } returns emptyList()
        }
        val engine = buildEngine(definitionRepo, mockk(), mockk())

        val request = WorkflowFixtures.softwareDefaultRequest()

        // When
        val plan = txTemplate.execute { engine.plan(request) }!!

        // Then
        assertThat(plan.toStateKey).isEqualTo("in_progress")
        assertThat(plan.fieldChanges).isEmpty()
        assertThat(plan.emitEvents).isEmpty()
    }

    // ── S2. permission fail — PermissionValidator Fail 시 예외 ──────────────────

    /**
     * Given  Permission validator 가 Fail 을 반환하도록 stub
     * When   전이 요청
     * Then   WorkflowValidatorFailureException 발생, validatorType == "permission-check"
     */
    @Test
    @Order(2)
    fun `S2 - permission fail - 권한 부족 시 WorkflowValidatorFailureException 이 발생한다`() {
        // Given
        val permissionValidator = mockk<WorkflowValidator> {
            every { type } returns "permission-check"
            every { validate(any()) } returns ValidatorResult.Fail(
                field = null,
                reason = "permission denied: TRANSITION_ISSUE",
            )
        }
        val validatorConfig = ValidatorConfig("permission-check", mapOf("permission" to "TRANSITION_ISSUE"))

        val definitionRepo = mockk<WorkflowDefinitionRepository> {
            every { findValidators(any()) } returns listOf(validatorConfig)
            every { findPostActions(any()) } returns emptyList()
        }
        val validatorFactory = mockk<WorkflowValidatorFactory> {
            every { create("permission-check", any()) } returns permissionValidator
        }
        val engine = buildEngine(definitionRepo, validatorFactory, mockk())

        val request = WorkflowFixtures.softwareDefaultRequest(actorRoles = setOf("VIEWER"))

        // When / Then
        assertThatThrownBy {
            txTemplate.execute { engine.plan(request) }
        }
            .isInstanceOf(WorkflowValidatorFailureException::class.java)
            .satisfies({ ex ->
                val failure = ex as WorkflowValidatorFailureException
                assertThat(failure.validatorType).isEqualTo("permission-check")
                assertThat(failure.reason).contains("permission denied")
            })
    }

    // ── S3. required field fail — 필수 필드 누락 시 예외 ────────────────────────

    /**
     * Given  RequiredFieldValidator("resolution") 설정, issueFields에 resolution 없음
     * When   전이 요청
     * Then   WorkflowValidatorFailureException, field == "resolution"
     */
    @Test
    @Order(3)
    fun `S3 - required field fail - 필수 필드 누락 시 WorkflowValidatorFailureException 이 발생한다`() {
        // Given — RequiredFieldValidator 실제 구현체 사용 (실제 검증 로직 경로 포함)
        val requiredFieldValidator = RequiredFieldValidator("resolution")
        val validatorConfig = ValidatorConfig("RequiredField", mapOf("field" to "resolution"))

        val definitionRepo = mockk<WorkflowDefinitionRepository> {
            every { findValidators(any()) } returns listOf(validatorConfig)
            every { findPostActions(any()) } returns emptyList()
        }
        val validatorFactory = mockk<WorkflowValidatorFactory> {
            every { create("RequiredField", any()) } returns requiredFieldValidator
        }
        val engine = buildEngine(definitionRepo, validatorFactory, mockk())

        // resolution 필드 없이 요청 (WorkflowFixtures 기본값에 resolution 없음)
        val request = WorkflowFixtures.softwareDefaultRequest(
            issueFields = mapOf("priority" to "HIGH"),
        )

        // When / Then
        assertThatThrownBy {
            txTemplate.execute { engine.plan(request) }
        }
            .isInstanceOf(WorkflowValidatorFailureException::class.java)
            .satisfies({ ex ->
                val failure = ex as WorkflowValidatorFailureException
                assertThat(failure.validatorType).isEqualTo("RequiredField")
                assertThat(failure.field).isEqualTo("resolution")
                assertThat(failure.reason).contains("required field")
            })
    }

    // ── S4. SpEL pass — CustomExpression 표현식 true 시 전이 성공 ────────────────

    /**
     * Given  CustomExpressionValidator("issue.priority == 'HIGH'") 설정
     *        issueFields["priority"] = "HIGH"
     * When   전이 요청
     * Then   SpEL true → Validator pass → 전이 성공 (TransitionPlan 반환)
     */
    @Test
    @Order(4)
    fun `S4 - SpEL pass - 커스텀 SpEL 표현식 true 평가 후 전이가 성공한다`() {
        // Given — SpelEvaluator 실제 구현체 + CustomExpressionValidator 실제 구현체
        val spelValidator = CustomExpressionValidator(
            evaluator = SpelEvaluator(executor = spelExecutor, timeoutMillis = 5000L),
            expression = "issue.priority == 'HIGH'",
        )
        val validatorConfig = ValidatorConfig(
            "CustomExpression",
            mapOf("expression" to "issue.priority == 'HIGH'"),
        )

        val definitionRepo = mockk<WorkflowDefinitionRepository> {
            every { findValidators(any()) } returns listOf(validatorConfig)
            every { findPostActions(any()) } returns emptyList()
        }
        val validatorFactory = mockk<WorkflowValidatorFactory> {
            every { create("CustomExpression", any()) } returns spelValidator
        }
        val engine = buildEngine(definitionRepo, validatorFactory, mockk())

        // priority == "HIGH" → SpEL true → pass
        val request = WorkflowFixtures.softwareDefaultRequest(
            issueFields = mapOf("priority" to "HIGH"),
        )

        // When
        val plan = txTemplate.execute { engine.plan(request) }!!

        // Then
        assertThat(plan.toStateKey).isEqualTo("in_progress")
        assertThat(plan.fieldChanges).isEmpty()
        assertThat(plan.emitEvents).isEmpty()
    }

    // ── S5. PostAction emitEvents — Notify PostAction DomainEvent 발행 ──────────

    /**
     * Given  Notify PostAction 이 "NotificationRequested" DomainEvent 를 emitEvents 에 담아 반환
     * When   전이 요청
     * Then   TransitionPlan.emitEvents 에 "NotificationRequested" 이벤트 1건 포함
     */
    @Test
    @Order(5)
    fun `S5 - PostAction emitEvents - Notify PostAction 이 전이 후 DomainEvent 를 발행한다`() {
        // Given
        val notifyEvent = DomainEvent(
            type = "NotificationRequested",
            payload = mapOf(
                "issueKey" to "BTS-1",
                "channel" to "slack",
                "recipients" to "team-dev",
            ),
        )
        val notifyPostAction = mockk<WorkflowPostAction> {
            every { type } returns "NOTIFY"
            every { evaluate(any()) } returns PostActionPlan(
                fieldChanges = emptyList(),
                emitEvents = listOf(notifyEvent),
            )
        }
        val postActionConfig = PostActionConfig("NOTIFY", mapOf("channel" to "slack", "recipients" to "team-dev"))

        val definitionRepo = mockk<WorkflowDefinitionRepository> {
            every { findValidators(any()) } returns emptyList()
            every { findPostActions(any()) } returns listOf(postActionConfig)
        }
        val postActionFactory = mockk<WorkflowPostActionFactory> {
            every { create("NOTIFY", any()) } returns notifyPostAction
        }
        val engine = buildEngine(definitionRepo, mockk(), postActionFactory)

        val request = WorkflowFixtures.softwareDefaultRequest()

        // When
        val plan = txTemplate.execute { engine.plan(request) }!!

        // Then
        assertThat(plan.emitEvents).hasSize(1)
        assertThat(plan.emitEvents.first().type).isEqualTo("NotificationRequested")
        assertThat(plan.emitEvents.first().payload["issueKey"]).isEqualTo("BTS-1")
        assertThat(plan.emitEvents.first().payload["channel"]).isEqualTo("slack")
    }

    // ── S6. 동시 전이 낙관락 시뮬 ────────────────────────────────────────────────

    /**
     * Given  version=1 의 전이 요청 두 건이 동시에 도달하는 상황
     * When   두 요청 모두 engine.plan() 호출
     * Then   plan() 은 두 번 모두 동일한 TransitionPlan 을 반환한다 (멱등성)
     *        version 충돌 처리는 호출자 BC(이슈 트래킹 BC) 책임이므로 plan() 은 관여하지 않는다
     */
    @Test
    @Order(6)
    fun `S6 - 동시 전이 낙관락 - 동일 전이 두 번 호출 시 동일 결과를 반환한다`() {
        // Given
        val definitionRepo = mockk<WorkflowDefinitionRepository> {
            every { findValidators(any()) } returns emptyList()
            every { findPostActions(any()) } returns emptyList()
        }
        val engine = buildEngine(definitionRepo, mockk(), mockk())

        val request1 = WorkflowFixtures.softwareDefaultRequest(version = 1L)
        val request2 = WorkflowFixtures.softwareDefaultRequest(version = 1L)

        // When — 두 호출 모두 실행 (호출자 BC 가 version 비교 전 단계)
        val plan1 = txTemplate.execute { engine.plan(request1) }!!
        val plan2 = txTemplate.execute { engine.plan(request2) }!!

        // Then — plan() 은 동일 결과를 반환한다 (멱등성)
        assertThat(plan1.toStateKey).isEqualTo("in_progress")
        assertThat(plan2.toStateKey).isEqualTo("in_progress")
        assertThat(plan1.toStateKey).isEqualTo(plan2.toStateKey)
    }

    // ── S7. cache invalidate ─────────────────────────────────────────────────────

    /**
     * Given  cache 에 software-default 가 이미 적재된 상태
     * When   workflowCache.invalidate("software-default") 호출
     * Then   다음 findByKey 가 cache miss → DB 재조회 → 새 객체 반환
     */
    @Test
    @Order(7)
    fun `S7 - cache invalidate - invalidate 후 다음 조회가 DB 에서 재적재된다`() {
        // Given — 첫 조회로 cache 채움
        val first = workflowCache.findByKey("software-default")
        assertThat(first).isNotNull
        assertThat(first!!.key).isEqualTo("software-default")

        // When — invalidate (cache 항목 제거)
        workflowCache.invalidate("software-default")

        // Then — 다음 조회는 cache miss → DB 재조회 → 새 객체 인스턴스 반환
        val afterInvalidate = workflowCache.findByKey("software-default")
        assertThat(afterInvalidate).isNotNull
        assertThat(afterInvalidate!!.key).isEqualTo("software-default")
        assertThat(afterInvalidate).isNotSameAs(first)

        // 재조회된 워크플로우 데이터 무결성 확인
        assertThat(afterInvalidate.states).isNotEmpty
        assertThat(afterInvalidate.transitions).isNotEmpty
        assertThat(afterInvalidate.states.map { it.key }).contains("open", "in_progress", "done")
    }
}

// ── 픽스처 빌더 — WorkflowFixtures ─────────────────────────────────────────────

/**
 * 통합 테스트 전용 픽스처 빌더.
 *
 * 표준 seed 데이터 삽입([seedSoftwareDefault])과 표준 전이 요청 생성([softwareDefaultRequest])을
 * 한 곳에 모아 여러 테스트가 재사용할 수 있도록 한다.
 */
object WorkflowFixtures {

    /**
     * software-default 워크플로우 기반 표준 전이 요청을 생성한다.
     *
     * 기본값은 open→in_progress "Start Work" 전이이며, 각 파라미터로 재정의 가능하다.
     */
    fun softwareDefaultRequest(
        fromStateKey: String = "open",
        toStateKey: String = "in_progress",
        transitionName: String = "Start Work",
        issueFields: Map<String, Any?> = mapOf("priority" to "HIGH"),
        actorRoles: Set<String> = setOf("MEMBER"),
        version: Long = 1L,
    ): TransitionRequest = TransitionRequest(
        workflowKey = "software-default",
        issueKey = "BTS-1",
        fromStateKey = fromStateKey,
        toStateKey = toStateKey,
        transitionName = transitionName,
        actorId = "user-integration-test",
        issueFields = issueFields,
        actorRoles = actorRoles,
        version = version,
    )

    /**
     * software-default 워크플로우 (5 states + 6 transitions) 를 JDBC 로 직접 DB 에 삽입한다.
     *
     * YamlSeedService 는 Spring Bean 이므로 Spring Context 없이는 직접 사용할 수 없다.
     * 통합 테스트에서는 JDBC 직접 삽입으로 seed 를 대체한다.
     * (YamlSeedServiceTest 에서 YAML 파싱 + 정합성이 이미 검증됨)
     */
    fun seedSoftwareDefault(jdbcUrl: String, username: String, password: String) {
        DriverManager.getConnection(jdbcUrl, username, password).use { conn ->
            conn.autoCommit = false

            val wfId = conn.prepareStatement(
                "INSERT INTO workflows (key, name)" +
                    " VALUES ('software-default', '소프트웨어 개발 기본 워크플로우') RETURNING id",
            ).use { stmt -> stmt.executeQuery().use { rs -> rs.next(); rs.getObject(1) as UUID } }

            val openId = insertState(conn, wfId, "open", "Open", "TODO", 1)
            val inProgressId = insertState(conn, wfId, "in_progress", "In Progress", "IN_PROGRESS", 2)
            val inReviewId = insertState(conn, wfId, "in_review", "In Review", "IN_PROGRESS", 3)
            val doneId = insertState(conn, wfId, "done", "Done", "DONE", 4)
            val closedId = insertState(conn, wfId, "closed", "Closed", "DONE", 5)

            insertTransition(conn, wfId, openId, inProgressId, "Start Work")
            insertTransition(conn, wfId, inProgressId, inReviewId, "Submit for Review")
            insertTransition(conn, wfId, inReviewId, doneId, "Approve")
            insertTransition(conn, wfId, inReviewId, inProgressId, "Request Changes")
            insertTransition(conn, wfId, doneId, closedId, "Close")
            insertTransition(conn, wfId, openId, closedId, "Cancel")

            conn.commit()
        }
    }

    private fun insertState(
        conn: Connection,
        wfId: UUID,
        key: String,
        name: String,
        category: String,
        displayOrder: Int,
    ): UUID =
        conn.prepareStatement(
            "INSERT INTO workflow_states (workflow_id, key, name, category, display_order)" +
                " VALUES (?, ?, ?, ?, ?) RETURNING id",
        ).use { stmt ->
            stmt.setObject(1, wfId)
            stmt.setString(2, key)
            stmt.setString(3, name)
            stmt.setString(4, category)
            stmt.setInt(5, displayOrder)
            stmt.executeQuery().use { rs -> rs.next(); rs.getObject(1) as UUID }
        }

    private fun insertTransition(
        conn: Connection,
        wfId: UUID,
        fromId: UUID,
        toId: UUID,
        name: String,
    ) {
        conn.prepareStatement(
            "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name)" +
                " VALUES (?, ?, ?, ?)",
        ).use { stmt ->
            stmt.setObject(1, wfId)
            stmt.setObject(2, fromId)
            stmt.setObject(3, toId)
            stmt.setString(4, name)
            stmt.executeUpdate()
        }
    }
}
