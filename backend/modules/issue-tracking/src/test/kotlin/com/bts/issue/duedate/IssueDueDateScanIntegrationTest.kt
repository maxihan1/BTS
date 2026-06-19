// 마감일 스캔 스케줄러 발행 end-to-end 통합 테스트 — FR-PL-02 Task 4

package com.bts.issue.duedate

import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * 마감일 스캔 스케줄러 발행 end-to-end 통합 테스트.
 *
 * FR-PL-02 Task 4 — [IssueDueDateScanWorker.scan] 이 시드 이슈를 올바르게 분류해
 * [IssueEventPublisher.QUEUE_NAME] 큐에 정확한 이벤트만 발행하는지 검증한다.
 *
 * ## 설계 근거
 * [IssueDueEventEmitter] 는 [org.springframework.transaction.annotation.Transactional]
 * 프록시가 필요하고, [IssueEventPublisher] 는 MANDATORY 트랜잭션을 요구한다.
 * 따라서 Spring 컨텍스트로 진짜 빈을 조립해 사용한다 (IssueMentionPublishIntegrationTest 동형).
 *
 * [IssueDueDateScanWorker] 는 [Clock.fixed] 를 주입해 수동 생성한다. emitter 는 Spring 빈을
 * autowire 하므로 @Transactional 프록시가 유지된다.
 *
 * ## 고정 기준 시각
 * [FIXED_TODAY] = 2026-06-19 (UTC 자정 기준). KST 와 UTC 기준 같은 날이므로 시간대 차이로
 * 발생하는 경계 케이스를 배제하고 쿼리 조건 정확성에만 집중한다.
 *
 * ## 시드 이슈 5종
 * - S1: due_date = today+1 (내일), 열림 → [IssueDueSoon] 발행 기대
 * - S2: due_date = today-5 (5일 전), 열림 → [IssueOverdue] 발행 기대
 * - S3: due_date = today-5, resolution_id 있음(종료) → 발행 없음
 * - S4: due_date = today-5, deleted_at 있음(소프트삭제) → 발행 없음
 * - S5: due_date = null → 발행 없음
 * - S6: due_date = today (당일) → 발행 없음 (임박=today+1, 지연=<today 둘 다 아님)
 *
 * ## 큐 읽기
 * scan() 호출 후 pgmq.read 로 q_issue_events 전체를 읽어 type 별로 집계한다.
 * 시드는 직접 INSERT — createIssue 경유 금지 (IssueCreated 이벤트가 큐에 섞여 단언 오염).
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueDueDateScanIntegrationTest.TestConfig::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueDueDateScanIntegrationTest {
    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TestConfig {
        companion object {
            /** JVM 단위 singleton Testcontainers — quay.io/tembo/pg16-pgmq:latest (pgmq 확장 내장) */
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_due_scan_test")
                    .withUsername("bts")
                    .withPassword("bts_due_scan_test")
                    .apply { start() }
        }

        @Bean
        open fun dataSource(): DriverManagerDataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )

        @Bean
        open fun transactionManager(dataSource: DriverManagerDataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        open fun dslContext(dataSource: DriverManagerDataSource): DSLContext {
            return DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @Bean
        open fun objectMapper(): ObjectMapper =
            ObjectMapper()
                .registerKotlinModule()
                .registerModule(JavaTimeModule())

        @Bean
        open fun issueRepository(dsl: DSLContext): IssueRepository = IssueRepository(dsl)

        @Bean
        open fun issueEventPublisher(
            dsl: DSLContext,
            objectMapper: ObjectMapper,
        ): IssueEventPublisher = IssueEventPublisher(dsl, objectMapper)

        @Bean
        open fun issueDueEventEmitter(publisher: IssueEventPublisher): IssueDueEventEmitter {
            return IssueDueEventEmitter(publisher)
        }
    }

    companion object {
        /**
         * 고정 기준 시각 — UTC 2026-06-19T00:00:00Z.
         * KST(Asia/Seoul) 기준으로도 2026-06-19이므로 시간대 경계 케이스가 없다.
         */
        val FIXED_INSTANT: Instant = Instant.parse("2026-06-19T00:00:00Z")

        /**
         * Clock.fixed — 워커의 "오늘" 날짜를 FIXED_TODAY 로 고정.
         * ZoneOffset.UTC 로 고정했으므로 KST 변환 후에도 2026-06-19 (UTC 자정 = KST 09:00).
         */
        val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)

        /** 스캔 기준 날짜 (KST 기준 오늘 — FIXED_CLOCK 에서 도출) */
        val FIXED_TODAY: LocalDate = LocalDate.of(2026, 6, 19)

        /** occurredAt = today.atStartOfDay(UTC) — 워커 로직과 동일 */
        val EXPECTED_OCCURRED_AT: Instant = FIXED_TODAY.atStartOfDay(ZoneOffset.UTC).toInstant()

        private const val PROJECT_KEY = "DSCAN"
    }

    @Autowired
    lateinit var issueRepository: IssueRepository

    @Autowired
    lateinit var issueDueEventEmitter: IssueDueEventEmitter

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private var migrated = false
    private var projectId: UUID? = null
    private var taskTypeId: Long? = null

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            Flyway.configure()
                .dataSource(
                    TestConfig.postgres.jdbcUrl,
                    TestConfig.postgres.username,
                    TestConfig.postgres.password,
                )
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .load()
                .migrate()
            migrated = true
        }
        seedProject()
        resolveTaskTypeId()
    }

    @BeforeEach
    fun cleanState() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("SELECT pgmq.purge_queue('q_issue_events')")
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    // ── 메인 시나리오 ────────────────────────────────────────────────────────────

    /**
     * scan() 실행 결과 S1(due_soon)·S2(overdue) 2건만 q_issue_events 에 발행되고,
     * S3(종료)·S4(삭제)·S5(null)·S6(당일) 관련 이벤트는 없음을 검증한다.
     *
     * 발행 vs 소비 멱등 구분: 이 테스트는 발행 정확성만 검증한다.
     * 같은 날 scan 2회 → 큐에 2배를 버그로 단언하지 않는다 (소비측 dedupKey 책임).
     */
    @Test
    fun `scan - 5종 시드 기준 due_soon 1건 overdue 1건만 발행된다`() {
        val today = FIXED_TODAY
        val s1Key = insertIssue(seq = 1L, dueDate = today.plusDays(1))
        val s2Key = insertIssue(seq = 2L, dueDate = today.minusDays(5))
        val s3Key = insertIssue(seq = 3L, dueDate = today.minusDays(5))
        setResolution(s3Key)
        val s4Key = insertIssue(seq = 4L, dueDate = today.minusDays(5))
        softDelete(s4Key)
        insertIssue(seq = 5L, dueDate = null)
        insertIssue(seq = 6L, dueDate = today)

        // 워커를 Clock.fixed 로 수동 생성 — emitter 는 Spring 빈 (프록시 유지)
        val worker = IssueDueDateScanWorker(issueRepository, issueDueEventEmitter, FIXED_CLOCK)
        worker.scan()

        val messages = readAllQueueMessages()

        val dueSoonMessages = messages.filter { it.get("type").asText() == "issue.due_soon" }
        val overdueMessages = messages.filter { it.get("type").asText() == "issue.overdue" }

        assertThat(dueSoonMessages)
            .describedAs("due_soon 이벤트는 정확히 1건이어야 한다 (S1만)")
            .hasSize(1)
        assertThat(overdueMessages)
            .describedAs("overdue 이벤트는 정확히 1건이어야 한다 (S2만)")
            .hasSize(1)

        assertThat(dueSoonMessages[0].get("issueKey").asText())
            .describedAs("due_soon issueKey 가 S1 과 일치해야 한다")
            .isEqualTo(s1Key)
        assertThat(dueSoonMessages[0].get("projectKey").asText())
            .describedAs("due_soon projectKey 가 $PROJECT_KEY 이어야 한다")
            .isEqualTo(PROJECT_KEY)
        assertThat(parseOccurredAt(dueSoonMessages[0].get("occurredAt")))
            .describedAs("due_soon occurredAt 이 today.atStartOfDay(UTC) 이어야 한다")
            .isEqualTo(EXPECTED_OCCURRED_AT)

        assertThat(overdueMessages[0].get("issueKey").asText())
            .describedAs("overdue issueKey 가 S2 와 일치해야 한다")
            .isEqualTo(s2Key)
        assertThat(overdueMessages[0].get("projectKey").asText())
            .describedAs("overdue projectKey 가 $PROJECT_KEY 이어야 한다")
            .isEqualTo(PROJECT_KEY)
        assertThat(parseOccurredAt(overdueMessages[0].get("occurredAt")))
            .describedAs("overdue occurredAt 이 today.atStartOfDay(UTC) 이어야 한다")
            .isEqualTo(EXPECTED_OCCURRED_AT)

        // S3·S4·S5·S6 관련 이벤트가 큐에 없음 (종료·삭제·null·당일 모두 제외)
        val allIssueKeys = messages.mapNotNull { it.get("issueKey")?.asText() }.toSet()
        assertThat(allIssueKeys).doesNotContain(s3Key, s4Key)
    }

    /**
     * scan() 을 동일 Clock 으로 2회 호출해도 occurredAt 이 동일함을 확인한다.
     *
     * 멱등 보장 범위: 같은 날 2회 스캔 시 큐에 2건 쌓이는 것은 정상 (발행 중복).
     * 소비측 dedupKey 가 날짜 단위이므로 최종 알림은 1회다.
     * 여기서는 occurredAt 이 두 호출 모두 동일한지만 단언한다.
     */
    @Test
    fun `scan - 동일 Clock 으로 2회 호출 시 occurredAt 이 동일하다`() {
        insertIssue(seq = 1L, dueDate = FIXED_TODAY.plusDays(1))

        val worker = IssueDueDateScanWorker(issueRepository, issueDueEventEmitter, FIXED_CLOCK)
        worker.scan()
        worker.scan()

        val messages = readAllQueueMessages()
        val dueSoonMessages = messages.filter { it.get("type").asText() == "issue.due_soon" }

        assertThat(dueSoonMessages).hasSizeGreaterThanOrEqualTo(2)
        val occurredAts = dueSoonMessages.map { parseOccurredAt(it.get("occurredAt")) }.toSet()
        assertThat(occurredAts)
            .describedAs("두 scan 호출 모두 동일한 occurredAt 이어야 한다")
            .containsOnly(EXPECTED_OCCURRED_AT)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * occurredAt JSON 노드를 [Instant] 로 변환한다.
     *
     * Jackson 의 [com.fasterxml.jackson.databind.ObjectMapper] 기본 설정에서
     * [java.time.Instant] 는 에포크 초(epoch seconds) 숫자로 직렬화된다.
     * [com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS] 가
     * 기본 true 이기 때문이다. 운영 앱은 스프링 부트가 이 설정을 false 로 강제하지만
     * 이 테스트의 경량 TestConfig 는 스프링 부트 자동구성을 사용하지 않으므로 숫자로 수신된다.
     * isNumber() 분기로 두 형식을 모두 처리한다.
     */
    private fun parseOccurredAt(node: com.fasterxml.jackson.databind.JsonNode): Instant {
        return if (node.isNumber) {
            Instant.ofEpochSecond(node.asLong())
        } else {
            Instant.parse(node.asText())
        }
    }

    /**
     * 이슈를 직접 INSERT 한다.
     *
     * createIssue 서비스 경유 금지 — IssueCreated 이벤트가 큐에 섞여 단언이 오염된다.
     * due_date 는 caller 지정값을 직접 세팅한다.
     *
     * @param seq key_sequence 값. "$PROJECT_KEY-$seq" 형태의 이슈 키로 삽입된다.
     * @param dueDate 마감일. null 이면 due_date IS NULL 로 삽입된다.
     * @return 삽입된 이슈 키 문자열 (예: "DSCAN-1").
     */
    private fun insertIssue(
        seq: Long,
        dueDate: LocalDate?,
    ): String {
        val issueKey = "$PROJECT_KEY-$seq"
        val pid = requireNotNull(projectId) { "projectId 미초기화 — seedProject() 확인" }
        val typeId = requireNotNull(taskTypeId) { "taskTypeId 미초기화 — resolveTaskTypeId() 확인" }
        val sql =
            "INSERT INTO issues " +
                "(key, project_id, summary, reporter_id, current_state_key, version, type_id, due_date) " +
                "VALUES (?, ?, ?, ?, 'open', 1, ?, ?)"
        conn().use { c ->
            c.prepareStatement(sql).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, pid)
                stmt.setString(3, "due scan seed $seq")
                stmt.setObject(4, UUID.fromString("00000000-0000-4000-8000-000000000099"))
                stmt.setLong(5, typeId)
                if (dueDate != null) {
                    stmt.setObject(6, dueDate)
                } else {
                    stmt.setNull(6, java.sql.Types.DATE)
                }
                stmt.executeUpdate()
            }
        }
        return issueKey
    }

    /**
     * resolution_id 를 임의 UUID 로 설정해 이슈를 "종료" 상태로 만든다.
     * 시드 전용 헬퍼 — 도메인 전이를 거치지 않는다.
     */
    private fun setResolution(issueKey: String) {
        conn().use { c ->
            c.prepareStatement("UPDATE issues SET resolution_id = ? WHERE key = ?").use { stmt ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setString(2, issueKey)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * deleted_at 을 NOW() 로 설정해 이슈를 소프트 삭제한다.
     * 시드 전용 헬퍼.
     */
    private fun softDelete(issueKey: String) {
        conn().use { c ->
            c.prepareStatement("UPDATE issues SET deleted_at = NOW() WHERE key = ?").use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * q_issue_events 에 쌓인 모든 메시지를 읽어 Jackson JSON 노드 목록으로 반환한다.
     *
     * pgmq.read raw SQL — DATA.md §5 예외 허용 (? 바인딩).
     * visibility_timeout=1, qty=100 — 100건 이하 단언용.
     */
    private fun readAllQueueMessages(): List<com.fasterxml.jackson.databind.JsonNode> {
        val connection =
            DriverManager.getConnection(
                TestConfig.postgres.jdbcUrl,
                TestConfig.postgres.username,
                TestConfig.postgres.password,
            )
        val dsl = DSL.using(connection, SQLDialect.POSTGRES)
        val records = dsl.fetch("SELECT * FROM pgmq.read('q_issue_events', 1, 100)")
        return records.map { record ->
            val body = record.get("message", String::class.java)
            objectMapper.readTree(body)
        }
    }

    private fun seedProject() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Due Scan Integration Test Project")
                stmt.executeUpdate()
            }
            c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "$PROJECT_KEY 프로젝트 조회 실패" }
                    projectId = rs.getObject(1) as UUID
                }
            }
        }
    }

    private fun resolveTaskTypeId() {
        conn().use { c ->
            c.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                    taskTypeId = rs.getLong(1)
                }
            }
        }
    }

    private fun conn() =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
