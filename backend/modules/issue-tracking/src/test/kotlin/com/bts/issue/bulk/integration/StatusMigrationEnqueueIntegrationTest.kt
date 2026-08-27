// 상태 이관 큐잉 통합 테스트 — 어댑터가 범위 안에서만 큐잉하고 구조가 틀린 요청을 거부한다 (FR-WF-07 D2·D4)

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
        open fun dataSource(): DriverManagerDataSource =
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

        @Bean
        open fun transactionManager(dataSource: DriverManagerDataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        open fun dslContext(dataSource: DriverManagerDataSource): DSLContext =
            DSL.using(dataSource, SQLDialect.POSTGRES)

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
        open fun bulkOperationEnqueuePublisher(dsl: DSLContext): BulkOperationEnqueuePublisher =
            BulkOperationEnqueuePublisher(dsl)

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
        private var bootstrapped = false
    }

    // ── 생명주기 ───────────────────────────────────────────────────────────────

    @BeforeAll
    fun setUpAll() {
        if (!bootstrapped) {
            applyMigrations()
            seedStatusCatalog()
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
     * T7 의 비-공허 짝이다. 중복 판정을 「매핑 키가 전부 유일」로 과잉 구현하면 이 테스트가 red 가 된다.
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
    ): StatusMigrationCommand =
        StatusMigrationCommand(actorUserId = ACTOR_ID, projectKeys = projectKeys, mappings = mappings)

    /**
     * 거부 시나리오 공통 단언 — 예외 메시지로 **어느 가드가 발동했는지** 구분하고,
     * 부작용(작업 행·큐 메시지)이 0 임을 함께 확인한다.
     */
    private fun assertRejected(
        cmd: StatusMigrationCommand,
        expectedMessagePart: String,
    ) {
        assertThatThrownBy { port.enqueueStatusMigration(cmd) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(expectedMessagePart)

        assertThat(dsl.fetchCount(BULK_OPERATIONS)).isEqualTo(0)
        assertThat(readQueueMessages()).isEmpty()
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
                        "ON CONFLICT (key) WHERE deleted_at IS NULL DO UPDATE SET name = EXCLUDED.name RETURNING id",
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
            conn.commit()
        }
    }
}
