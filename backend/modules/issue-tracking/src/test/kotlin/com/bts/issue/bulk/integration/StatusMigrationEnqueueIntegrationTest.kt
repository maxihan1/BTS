// 상태 이관 큐잉 통합 테스트 — 어댑터가 범위 안에서만 큐잉하고 구조가 틀린 요청을 거부한다 (FR-WF-07 D2·D4)
@file:Suppress("MaxLineLength")

package com.bts.issue.bulk.integration

import com.bts.issue.adapter.outbound.workflow.WorkflowStatusMigrationAdapter
import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationPayload
import com.bts.issue.bulk.domain.BulkOperationStatus
import com.bts.issue.bulk.domain.BulkOperationType
import com.bts.issue.bulk.event.BulkOperationEnqueuePublisher
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.jooq.tables.references.BULK_OPERATIONS
import com.bts.issue.jooq.tables.references.BULK_OPERATION_ITEMS
import com.bts.issue.testsupport.insertWorkflowStatus
import com.bts.shared.issue.IssueStatusMigrationPort
import com.bts.shared.issue.StatusMigrationCommand
import com.bts.shared.issue.StatusMigrationMapping
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 상태 이관(STATUS_MIGRATION) 큐잉 통합 테스트 — plan Task 5.
 *
 * Testcontainers PostgreSQL(pgmq 포함)에 issue-tracking + project-workflow 마이그레이션을 적용하고
 * [WorkflowStatusMigrationAdapter] 가 큐잉 시점에 **무엇을 만들고 무엇을 거부하는지** 를 실 DB 로 확인한다.
 *
 * ## 검증 시나리오
 * - T1. 큐잉하면 `bulk_operations` 1건(STATUS_MIGRATION·PENDING) + pgmq 메시지가 생기고
 *   `bulk_operation_items` 는 **0건**, `total_count` 는 **0** 이다 (F4)
 * - T2. payload 에 `mappings` 와 `projectKeys` 가 **둘 다** 실린다 (F6)
 * - T3~T7. 구조 검증 5종 거부 — E1 · E2 · E5 · E5b · E7. 각 시나리오는 **정확히 한 가드만** 건드리며
 *   예외 메시지로 어느 가드가 발동했는지 구분된다
 * - T8. E6(같은 대상으로 여러 출발이 몰림)은 **허용**이다. E7 가드가 "키 전부 유일"로 과잉 구현되면 red 가 된다
 * - T9~T11. [BulkOperation.create] 의 빈 items 규칙 — STATUS_MIGRATION 만 허용이고
 *   BULK_EDIT / BULK_TRANSITION 은 **여전히 거부**된다 (비-공허 짝)
 * - T12~T14. 게이트 2 리뷰로 더한 거부 2종 — E17(연쇄 매핑) · E18(범위 키가 실재하지 않음 ·
 *   소프트 삭제 포함). 비-공허 짝은 T8(연쇄가 아닌 다중 매핑)과 T1(실재하는 범위 키)이다
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [StatusMigrationEnqueueIntegrationTest.TestConfig::class])
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatusMigrationEnqueueIntegrationTest {
    // ── Spring Bean 구성 ────────────────────────────────────────────────────────

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TestConfig {
        companion object {
            /** JVM 단위 singleton Testcontainers — quay.io/tembo/pg16-pgmq:latest (pgmq 확장 포함) */
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_status_migration_it")
                    .withUsername("bts")
                    .withPassword("bts_status_migration_it")
                    .apply { start() }
        }

        @Bean
        open fun dataSource(): DriverManagerDataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

        @Bean
        open fun transactionManager(ds: DriverManagerDataSource): PlatformTransactionManager = DataSourceTransactionManager(ds)

        @Bean
        open fun dslContext(ds: DriverManagerDataSource): DSLContext = DSL.using(ds, SQLDialect.POSTGRES)

        @Bean
        open fun objectMapper(): ObjectMapper =
            ObjectMapper()
                .registerKotlinModule()
                .registerModule(JavaTimeModule())

        @Bean
        open fun clock(): Clock = Clock.systemUTC()

        @Bean
        open fun bulkOperationRepository(
            dsl: DSLContext,
            objectMapper: ObjectMapper,
            clock: Clock,
        ): BulkOperationRepository = BulkOperationRepository(dsl, objectMapper, clock)

        @Bean
        open fun bulkOperationEnqueuePublisher(dsl: DSLContext): BulkOperationEnqueuePublisher = BulkOperationEnqueuePublisher(dsl)

        @Bean
        open fun workflowStatusMigrationAdapter(
            dsl: DSLContext,
            repo: BulkOperationRepository,
            enqueuePublisher: BulkOperationEnqueuePublisher,
        ): WorkflowStatusMigrationAdapter = WorkflowStatusMigrationAdapter(dsl, repo, enqueuePublisher)
    }

    // ── 주입 빈 ────────────────────────────────────────────────────────────────

    @Autowired
    lateinit var port: IssueStatusMigrationPort

    @Autowired
    lateinit var bulkRepo: BulkOperationRepository

    @Autowired
    lateinit var dsl: DSLContext

    // ── 테스트 전역 상수 ───────────────────────────────────────────────────────

    companion object {
        private val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a5")
        private const val PROJECT_KEY = "MIG"
        private const val OTHER_PROJECT_KEY = "OTHER"

        /** 어디에도 심지 않는 프로젝트 키 — 발행 요청의 오타를 흉내낸다 (E18). */
        private const val UNKNOWN_PROJECT_KEY = "NOPE"

        /** 심되 `deleted_at` 을 채워 두는 프로젝트 키 — 소프트 삭제가 「없음」으로 읽히는지 본다 (E18). */
        private const val DELETED_PROJECT_KEY = "GONE"

        /**
         * 카탈로그에 **행은 있는** 상태 키. T15 가 이 키만 잠깐 소프트 삭제했다가 되돌린다.
         *
         * T4 의 `ghost_status` 는 애초에 심은 적이 없어 `deleted_at IS NULL` 을 지워도 통과한다 —
         * 「존재한 적 없는 키」와 「지워진 키」는 다른 경로다. 둘이 짝이 되어야 그 한 줄이 지켜진다.
         */
        private const val RETIRED_STATUS_KEY = "retired_review"
        private var bootstrapped = false
    }

    // ── 생명주기 ───────────────────────────────────────────────────────────────

    @BeforeAll
    fun setUpAll() {
        if (!bootstrapped) {
            applyMigrations()
            seedStatusCatalog()
            seedProjects()
            bootstrapped = true
        }
    }

    @BeforeEach
    fun cleanBetweenTests() {
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("SELECT pgmq.purge_queue('${BulkOperationEnqueuePublisher.QUEUE_NAME}')")
                stmt.execute("DELETE FROM bulk_operation_items")
                stmt.execute("DELETE FROM bulk_operations")
            }
        }
    }

    // ── T1. 큐잉 결과 — 작업 1건 · 메시지 1건 · 항목 0건 ────────────────────────

    /**
     * T1 (F4) — 큐잉은 `bulk_operations` 1건과 pgmq 메시지만 만든다.
     *
     * ★`bulk_operation_items` 0건 · `total_count` 0 이 이 테스트의 본질이다. 큐잉 시점에 대상을
     * 확정하면 그것이 곧 「세고 나서 옮긴다」이고, 큐잉 → 실행 사이에 들어온 이슈를 버린다.
     */
    @Test
    fun `T1 - 큐잉하면 STATUS_MIGRATION PENDING 1건과 pgmq 메시지가 생기고 항목은 0건이다`() {
        val operationId = port.enqueueStatusMigration(validCommand())

        val operation = requireNotNull(bulkRepo.findById(BulkOperationId(operationId))) { "작업을 찾을 수 없음" }
        assertThat(operation.type).isEqualTo(BulkOperationType.STATUS_MIGRATION)
        assertThat(operation.status).isEqualTo(BulkOperationStatus.PENDING)
        assertThat(operation.actorId).isEqualTo(ACTOR_ID)
        assertThat(operation.totalCount).isEqualTo(0)

        assertThat(dsl.fetchCount(BULK_OPERATIONS)).isEqualTo(1)
        assertThat(dsl.fetchCount(BULK_OPERATION_ITEMS)).isEqualTo(0)
        assertThat(bulkRepo.findItemsByOperationId(BulkOperationId(operationId))).isEmpty()

        val messages = readQueueMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages.first()).contains(operationId.toString())
    }

    // ── T2. payload 왕복 — mappings 와 projectKeys 가 둘 다 실린다 ──────────────

    /**
     * T2 (F6) — 실행 시점 재해석의 입력 2개가 payload 에 모두 남는다.
     *
     * `projectKeys` 가 빠지면 워커가 범위를 알 수 없어 남의 프로젝트 이슈까지 옮긴다(G4).
     */
    @Test
    fun `T2 - payload 에 mappings 와 projectKeys 가 둘 다 실린다`() {
        val operationId = port.enqueueStatusMigration(validCommand())

        val operation = requireNotNull(bulkRepo.findById(BulkOperationId(operationId))) { "작업을 찾을 수 없음" }
        val payload = operation.payload
        assertThat(payload).isInstanceOf(BulkOperationPayload.StatusMigration::class.java)
        payload as BulkOperationPayload.StatusMigration
        assertThat(payload.mappings).containsExactlyInAnyOrderEntriesOf(
            mapOf("in_review" to "in_progress", "blocked" to "todo"),
        )
        assertThat(payload.projectKeys).containsExactlyInAnyOrder(PROJECT_KEY, OTHER_PROJECT_KEY)
    }

    // ── T3~T7. 구조 검증 5종 거부 ──────────────────────────────────────────────

    /** T3 (E1) — 출발과 대상이 같으면 옮길 것이 없는데 작업만 남는다. */
    @Test
    fun `T3 - 거부 - from 과 to 가 같으면 큐잉하지 않는다`() {
        assertRejected(
            command(mappings = listOf(StatusMigrationMapping("in_review", "in_review"))),
            expectedMessagePart = "fromStatusKey must differ from toStatusKey",
        )
    }

    /** T4 (E2) — 카탈로그에 없는 대상으로 옮기면 이 기능이 막으려던 유령 상태를 이 기능이 만든다. */
    @Test
    fun `T4 - 거부 - toStatusKey 가 상태 카탈로그에 없으면 큐잉하지 않는다`() {
        assertRejected(
            command(mappings = listOf(StatusMigrationMapping("in_review", "ghost_status"))),
            expectedMessagePart = "toStatusKey not found in status catalog",
        )
    }

    /** T5 (E5) — 매핑이 비면 옮길 규칙 자체가 없다. */
    @Test
    fun `T5 - 거부 - 매핑이 비어 있으면 큐잉하지 않는다`() {
        assertRejected(
            command(mappings = emptyList()),
            expectedMessagePart = "mappings must not be empty",
        )
    }

    /**
     * T6 (E5b · 게이트 1 C-1) — 빈 범위는 실행 시점 0건 → `COMPLETED` 로 끝난다.
     * 운영자는 「이관 완료」를 보고 상태를 지우는데 **아무것도 안 옮겨졌다**.
     */
    @Test
    fun `T6 - 거부 - projectKeys 가 비어 있으면 큐잉하지 않는다`() {
        assertRejected(
            command(projectKeys = emptySet()),
            expectedMessagePart = "projectKeys must not be empty",
        )
    }

    /** T7 (E7) — 같은 출발이 두 번이면 어느 대상인지 정할 수 없다. */
    @Test
    fun `T7 - 거부 - 같은 fromStatusKey 가 두 번 나오면 큐잉하지 않는다`() {
        assertRejected(
            command(
                mappings =
                    listOf(
                        StatusMigrationMapping("in_review", "in_progress"),
                        StatusMigrationMapping("in_review", "todo"),
                    ),
            ),
            expectedMessagePart = "duplicate fromStatusKey",
        )
    }

    // ── T8. E6 은 허용 — 막지 않는 것도 결정이다 ───────────────────────────────

    /**
     * T8 (E6) — 여러 출발이 **같은 대상**으로 몰리는 것은 허용한다. 지라도 막지 않는다(J7).
     *
     * T7 **과 T12** 의 비-공허 짝이다. 중복 판정을 「매핑 키가 전부 유일」로 과잉 구현하거나,
     * T12 의 연쇄 판정을 「대상이 둘 이상이면 거부」로 과잉 구현하면 이 테스트가 red 가 된다.
     */
    @Test
    fun `T8 - 여러 fromStatusKey 가 같은 toStatusKey 로 몰리는 것은 허용된다`() {
        val operationId =
            port.enqueueStatusMigration(
                command(
                    mappings =
                        listOf(
                            StatusMigrationMapping("in_review", "in_progress"),
                            StatusMigrationMapping("blocked", "in_progress"),
                        ),
                ),
            )

        assertThat(bulkRepo.findById(BulkOperationId(operationId))).isNotNull
    }

    // ── T12~T14. 게이트 2 리뷰 — 연쇄 매핑 · 범위 키 실재 ──────────────────────

    /**
     * T12 (E17) — 한 매핑의 **대상**이 다른 매핑의 **출발**이면 거부한다.
     *
     * `{in_review→blocked, blocked→todo}` 는 기존 가드를 전부 통과한다. 자기매핑 판정이 한 쌍
     * 안에서만 보기 때문이다. 그대로 두면 워커가 `current_state_key IN ('in_review','blocked')` 로
     * 긁어 `in_review` 이슈를 `blocked` 로 옮기고 그 항목을 `SUCCEEDED` 로 찍어 **다시 처리하지
     * 않는다.** 그런데 `blocked` 도 사라지는 상태다 — 작업은 `COMPLETED` 인데 유령 상태가 남는다.
     * 게다가 스캔 시점에 이미 `blocked` 에 있던 이슈만 `todo` 로 가므로 **결과가 순서에 의존**한다.
     *
     * 전이적으로 풀어 주지 않는다. 순환(`{a→b, b→a}`)이면 종료하지 않고, 조용히 대상을 바꾸면
     * 운영자의 실수를 감춘다. 거부가 옳다.
     */
    @Test
    fun `T12 - 거부 - 한 매핑의 대상이 다른 매핑의 출발이면 큐잉하지 않는다`() {
        assertRejected(
            command(
                mappings =
                    listOf(
                        StatusMigrationMapping("in_review", "blocked"),
                        StatusMigrationMapping("blocked", "todo"),
                    ),
            ),
            expectedMessagePart = "chained mapping",
        )
    }

    /**
     * T13 (E18) — 상태 카탈로그 확인과 **대칭**으로 범위 프로젝트 키의 실재도 확인한다.
     *
     * 오타 하나면 워커가 0건을 긁고 `total_count=0` 으로 즉시 `COMPLETED` 가 된다. 운영자는
     * 「이관 완료」를 보고 상태를 지운다 — E5b(빈 범위)가 막으려던 그 실패 양식이고 트리거만 다르다.
     * 실행 시점에는 정상 0건(E3)과 오타 0건이 구분되지 않으므로 **큐잉 시점**이 유일한 자리다.
     *
     * 살아 있는 키를 하나 섞어 둔다 — 「전부 모르는 키일 때만 거부」로 좁게 구현되면 red 가 된다.
     */
    @Test
    fun `T13 - 거부 - projectKeys 에 실재하지 않는 키가 섞이면 큐잉하지 않는다`() {
        assertRejected(
            command(projectKeys = setOf(PROJECT_KEY, UNKNOWN_PROJECT_KEY)),
            expectedMessagePart = "projectKeys not found",
        )
    }

    /**
     * T14 (E18) — 소프트 삭제된 프로젝트는 「없다」로 본다.
     *
     * 소프트 삭제에는 자동 필터가 없다(DATA.md §3). 조회에서 `deleted_at IS NULL` 을 빼면 지워진
     * 프로젝트가 범위로 통과하고 워커는 거기서 0건을 긁어 또 「이관 완료」가 된다. 이 테스트가
     * 그 한 줄의 판별식이다 — T13 만 있으면 그 줄을 지워도 초록이다.
     */
    @Test
    fun `T14 - 거부 - 소프트 삭제된 프로젝트 키는 실재하지 않는 것으로 본다`() {
        assertRejected(
            command(projectKeys = setOf(DELETED_PROJECT_KEY)),
            expectedMessagePart = "projectKeys not found",
        )
    }

    /**
     * T15 (E2 · 게이트 2 리뷰 ④-3) — **소프트 삭제된 상태로는 옮길 수 없다**.
     *
     * 상태 카탈로그 조회의 `deleted_at IS NULL` 을 지키는 판별식이다. T4 는 애초에 심은 적 없는
     * 키(`ghost_status`)를 쓰므로 그 한 줄을 지워도 초록이다 — 행이 없으면 조건과 무관하게 안 잡힌다.
     * 「행은 있는데 지워진」 키라야 그 줄만이 판정을 만든다.
     *
     * 지워진 상태로 옮기면 이 기능이 막으려던 유령 상태를 이 기능이 만든다 — T4 와 **같은 사유**이므로
     * 거부 메시지도 같아야 한다.
     *
     * 비-공허 짝 — `deleted_at` 을 되돌리면 같은 커맨드가 통과한다. 「이 키는 늘 거부」 구현을 배제하고,
     * 원복이 실제로 됐음을 같은 테스트가 증명한다.
     */
    @Test
    fun `T15 - 거부 - 소프트 삭제된 상태 키는 카탈로그에 없는 것으로 본다`() {
        val cmd = command(mappings = listOf(StatusMigrationMapping("in_review", RETIRED_STATUS_KEY)))

        setStatusDeleted(RETIRED_STATUS_KEY, deleted = true)
        try {
            assertRejected(cmd, expectedMessagePart = "toStatusKey not found in status catalog")
        } finally {
            setStatusDeleted(RETIRED_STATUS_KEY, deleted = false)
        }

        // 비-공허 짝 — 살아 있으면 같은 커맨드가 큐잉된다.
        val operationId = port.enqueueStatusMigration(cmd)
        assertThat(bulkRepo.findById(BulkOperationId(operationId)))
            .describedAs("deleted_at 을 되돌리면 같은 대상 상태가 다시 받아들여져야 한다")
            .isNotNull
    }

    // ── T9~T11. BulkOperation.create 의 빈 items 규칙 (비-공허 짝) ──────────────

    /**
     * T9 — STATUS_MIGRATION 만 빈 items 로 만들어진다.
     *
     * 대상이 큐잉 시점이 아니라 **실행 시점**에 확정되기 때문이다(F15 · 「옮기면서 센다」).
     */
    @Test
    fun `T9 - BulkOperation create 는 STATUS_MIGRATION 의 빈 items 를 허용한다`() {
        val operation =
            BulkOperation.create(
                id = BulkOperationId(UUID.randomUUID()),
                actorId = ACTOR_ID,
                type = BulkOperationType.STATUS_MIGRATION,
                items = emptyList(),
                payload = BulkOperationPayload.StatusMigration(emptyMap(), emptySet()),
            )

        assertThat(operation.items).isEmpty()
        assertThat(operation.totalCount).isEqualTo(0)
    }

    /** T10 — ★비-공허 짝. BULK_EDIT 는 빈 items 를 **여전히** 거부한다. */
    @Test
    fun `T10 - BulkOperation create 는 BULK_EDIT 의 빈 items 를 여전히 거부한다`() {
        assertThatThrownBy {
            BulkOperation.create(
                id = BulkOperationId(UUID.randomUUID()),
                actorId = ACTOR_ID,
                type = BulkOperationType.BULK_EDIT,
                items = emptyList(),
                payload = BulkOperationPayload.Edit(priority = 3, impact = null),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("items must not be empty")
    }

    /** T11 — ★비-공허 짝. BULK_TRANSITION 도 빈 items 를 **여전히** 거부한다. */
    @Test
    fun `T11 - BulkOperation create 는 BULK_TRANSITION 의 빈 items 를 여전히 거부한다`() {
        assertThatThrownBy {
            BulkOperation.create(
                id = BulkOperationId(UUID.randomUUID()),
                actorId = ACTOR_ID,
                type = BulkOperationType.BULK_TRANSITION,
                items = emptyList(),
                payload = BulkOperationPayload.Transition(toStateKey = "done"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("items must not be empty")
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /** 구조 검증을 전부 통과하는 기준 커맨드. 개별 테스트가 한 축만 흔든다. */
    private fun validCommand(): StatusMigrationCommand = command()

    private fun command(
        projectKeys: Set<String> = setOf(PROJECT_KEY, OTHER_PROJECT_KEY),
        mappings: List<StatusMigrationMapping> =
            listOf(
                StatusMigrationMapping("in_review", "in_progress"),
                StatusMigrationMapping("blocked", "todo"),
            ),
    ): StatusMigrationCommand = StatusMigrationCommand(actorUserId = ACTOR_ID, projectKeys = projectKeys, mappings = mappings)

    /**
     * 거부 시나리오 공통 단언 — 예외 메시지로 **어느 가드가 발동했는지** 구분하고,
     * 부작용(작업 행·큐 메시지)이 0 임을 함께 확인한다.
     */
    private fun assertRejected(
        cmd: StatusMigrationCommand,
        expectedMessagePart: String,
    ) {
        // assertThatThrownBy 는 「안 던졌다」를 자기 안에서 터뜨려 describedAs 가 붙지 않는다.
        // 거부 자체가 사라지는 회귀에서 red 가 어느 가드를 기대했는지 말하게 하려고 runCatching 으로 받는다.
        val thrown: Throwable? = runCatching { port.enqueueStatusMigration(cmd) }.exceptionOrNull()
        assertThat(thrown)
            .describedAs("큐잉이 거부되고 예외 메시지에 \"%s\" 가 담겨야 한다", expectedMessagePart)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(expectedMessagePart)

        assertThat(dsl.fetchCount(BULK_OPERATIONS)).isEqualTo(0)
        assertThat(readQueueMessages()).isEmpty()
    }

    /**
     * 전역 상태 카탈로그(`statuses`) 1행의 `deleted_at` 을 켜고 끈다 (T15 전용).
     *
     * `statuses` 는 project-workflow 소유라 issue-tracking 의 jOOQ 생성 대상이 아니다.
     * [WorkflowStatusMigrationAdapter] 가 그 테이블을 읽는 방식과 같이 [DSL.table]/[DSL.field] 로
     * 참조한다 — SQL 문자열 결합을 만들지 않는다(DATA.md §5).
     */
    private fun setStatusDeleted(
        statusKey: String,
        deleted: Boolean,
    ) {
        val statuses = DSL.table("statuses")
        val key = DSL.field("key", String::class.java)
        val deletedAt = DSL.field("deleted_at", OffsetDateTime::class.java)
        val affected =
            if (deleted) {
                dsl.update(statuses).set(deletedAt, OffsetDateTime.now()).where(key.eq(statusKey)).execute()
            } else {
                dsl.update(statuses).setNull(deletedAt).where(key.eq(statusKey)).execute()
            }
        check(affected == 1) { "상태 픽스처 갱신 실패: $statusKey (affected=$affected)" }
    }

    /** pgmq `q_bulk_operations` 큐의 메시지 본문 목록. */
    private fun readQueueMessages(): List<String> =
        dsl.fetch(
            "SELECT * FROM pgmq.read(?, ?, ?)",
            BulkOperationEnqueuePublisher.QUEUE_NAME,
            1,
            10,
        ).map { it.get("message", String::class.java) }

    /** Flyway — issue-tracking(bulk_operations·pgmq) + project-workflow(statuses 카탈로그) 단일 pass. */
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

    /**
     * 상태 카탈로그 픽스처 — **이 테스트가 자기 상태를 직접 심는다.**
     *
     * 공용 dev DB 나 마이그레이션 시드에 기대면 「데이터가 있다」가 가짜 그린을 만든다.
     * E2(카탈로그 부재) 판정이 실제로 카탈로그를 보는지 확인하려면 심은 것만 존재해야 한다.
     */
    private fun seedStatusCatalog() {
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.autoCommit = false
            val workflowId: UUID =
                conn.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES ('migration-test-wf', 'Migration Test') " +
                        "ON CONFLICT (key) WHERE project_id IS NULL AND deleted_at IS NULL" +
                        " DO UPDATE SET name = EXCLUDED.name RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }
            insertWorkflowStatus(conn, workflowId, "todo", "To Do", "TODO", 0)
            insertWorkflowStatus(conn, workflowId, "in_progress", "In Progress", "IN_PROGRESS", 1)
            insertWorkflowStatus(conn, workflowId, "in_review", "In Review", "IN_PROGRESS", 2)
            insertWorkflowStatus(conn, workflowId, "blocked", "Blocked", "IN_PROGRESS", 3)
            // T15 전용 — 살아 있는 채로 심고 그 테스트만 잠깐 소프트 삭제한다.
            insertWorkflowStatus(conn, workflowId, RETIRED_STATUS_KEY, "Retired Review", "IN_PROGRESS", 4)
            conn.commit()
        }
    }

    /**
     * 프로젝트 픽스처 — 범위 키 실재 판정(E18)의 대조군이다.
     *
     * 살아 있는 [PROJECT_KEY]·[OTHER_PROJECT_KEY] 와 소프트 삭제된 [DELETED_PROJECT_KEY] 를 함께
     * 심는다. 살아 있는 쪽이 없으면 T1·T2·T8 이 통과할 수 없고(비-공허 짝이 사라진다), 삭제된 쪽이
     * 없으면 `deleted_at IS NULL` 을 지워도 red 가 되는 테스트가 없다.
     */
    private fun seedProjects() {
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.autoCommit = false
            listOf(
                Triple(PROJECT_KEY, "Status Migration Scope", false),
                Triple(OTHER_PROJECT_KEY, "Status Migration Other Scope", false),
                Triple(DELETED_PROJECT_KEY, "Status Migration Deleted Scope", true),
            ).forEach { (key, name, deleted) ->
                val deletedAt = if (deleted) "NOW()" else "NULL"
                conn.prepareStatement(
                    "INSERT INTO projects (key, name, deleted_at) VALUES (?, ?, $deletedAt) ON CONFLICT (key) DO NOTHING",
                ).use { stmt ->
                    stmt.setString(1, key)
                    stmt.setString(2, name)
                    stmt.executeUpdate()
                }
            }
            conn.commit()
        }
    }
}
