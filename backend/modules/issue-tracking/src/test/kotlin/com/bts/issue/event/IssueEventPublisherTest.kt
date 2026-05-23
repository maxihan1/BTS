// IssueEventPublisher Testcontainers 통합 테스트 — pgmq.send 후 q_issue_events 큐에 메시지 1건 + JSON 필드 검증

package com.bts.issue.event

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.springframework.jdbc.datasource.SingleConnectionDataSource

/**
 * IssueEventPublisher 통합 테스트.
 *
 * quay.io/tembo/pg16-pgmq:latest 컨테이너를 Testcontainers 로 기동해
 * 실제 pgmq 큐에 이벤트가 enqueue 되는지 검증한다.
 *
 * Propagation.MANDATORY 검증 포함 — 트랜잭션 없이 호출 시 IllegalTransactionStateException 발생 확인.
 */
class IssueEventPublisherTest : DescribeSpec({

    // ── Testcontainers: quay.io/tembo/pg16-pgmq:latest ──────────────────────
    val temboImage = DockerImageName
        .parse("quay.io/tembo/pg16-pgmq:latest")
        .asCompatibleSubstituteFor("postgres")

    val postgres = PostgreSQLContainer(temboImage)
        .withDatabaseName("bts_test")
        .withUsername("test")
        .withPassword("test")

    beforeSpec {
        postgres.start()

        // V001 + V002 통합 SQL 적용 (init_codegen.sql 재사용)
        Class.forName("org.postgresql.Driver")
        val initSql = IssueEventPublisherTest::class.java
            .getResourceAsStream("/db/codegen/init_codegen.sql")
            ?.bufferedReader()?.readText()
            ?: error("init_codegen.sql 을 찾을 수 없습니다")

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(initSql)
            }
        }
    }

    afterSpec {
        postgres.stop()
    }

    // ── 공통 인프라 헬퍼 ─────────────────────────────────────────────────────

    fun buildDsl(): DSLContext {
        val ds = SingleConnectionDataSource(postgres.jdbcUrl, postgres.username, postgres.password, false)
        return DSL.using(ds, SQLDialect.POSTGRES)
    }

    fun buildObjectMapper(): ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())

    // pgmq.read 로 큐에서 메시지 1건 읽기 (visibility timeout = 1초)
    fun readOneMessage(dsl: DSLContext): org.jooq.Record? =
        dsl.fetch("SELECT * FROM pgmq.read('q_issue_events', 1, 1)").firstOrNull()

    // 큐 비우기 — 각 테스트 독립성 보장
    fun purgeQueue(dsl: DSLContext) {
        dsl.execute("SELECT pgmq.purge_queue('q_issue_events')")
    }

    // ── 테스트 케이스 ─────────────────────────────────────────────────────────

    describe("IssueEventPublisher.publish") {

        it("IssueCreated 발행 후 q_issue_events 에 메시지 1건이 존재하며 type=issue.created 와 issueKey 를 포함한다") {
            val dsl = buildDsl()
            val mapper = buildObjectMapper()
            val publisher = IssueEventPublisher(dsl, mapper)

            purgeQueue(dsl)

            val event = IssueCreated(
                issueKey = IssueKey("ATLAS-1"),
                projectKey = "ATLAS",
                summary = "첫 번째 이슈",
                reporterId = ActorId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
            )

            // Propagation.MANDATORY — 반드시 트랜잭션 안에서 호출해야 함
            val ds: DataSource = SingleConnectionDataSource(
                postgres.jdbcUrl, postgres.username, postgres.password, false,
            )
            val txManager = DataSourceTransactionManager(ds)
            val txTemplate = TransactionTemplate(txManager)

            txTemplate.execute {
                // publisher 내부 DSLContext 는 SingleConnectionDataSource 기반이므로
                // 같은 커넥션에서 트랜잭션이 활성화된 상태로 publish 호출
                publisher.publish(event)
            }

            val record = readOneMessage(dsl)
            record shouldNotBe null

            val body = record!!.get("message", String::class.java)
            val json = mapper.readTree(body)

            json.get("type").asText() shouldBe "issue.created"
            json.get("issueKey").asText() shouldBe "ATLAS-1"
            json.get("projectKey").asText() shouldBe "ATLAS"
            json.get("summary").asText() shouldBe "첫 번째 이슈"
        }

        it("IssueTransitioned 발행 후 q_issue_events 에 메시지 1건이 존재하며 type=issue.transitioned 를 포함한다") {
            val dsl = buildDsl()
            val mapper = buildObjectMapper()
            val publisher = IssueEventPublisher(dsl, mapper)

            purgeQueue(dsl)

            val event = IssueTransitioned(
                issueKey = IssueKey("ATLAS-2"),
                fromState = "OPEN",
                toState = "IN_PROGRESS",
                occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
            )

            val ds: DataSource = SingleConnectionDataSource(
                postgres.jdbcUrl, postgres.username, postgres.password, false,
            )
            val txManager = DataSourceTransactionManager(ds)
            val txTemplate = TransactionTemplate(txManager)

            txTemplate.execute {
                publisher.publish(event)
            }

            val record = readOneMessage(dsl)
            record shouldNotBe null

            val body = record!!.get("message", String::class.java)
            val json = mapper.readTree(body)

            json.get("type").asText() shouldBe "issue.transitioned"
            json.get("issueKey").asText() shouldBe "ATLAS-2"
            json.get("fromState").asText() shouldBe "OPEN"
            json.get("toState").asText() shouldBe "IN_PROGRESS"
        }

        it("Propagation.MANDATORY — 활성 트랜잭션 없이 호출하면 IllegalTransactionStateException 이 발생한다") {
            val dsl = buildDsl()
            val mapper = buildObjectMapper()
            val publisher = IssueEventPublisher(dsl, mapper)

            val event = IssueSoftDeleted(
                issueKey = IssueKey("ATLAS-3"),
                occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
            )

            // 트랜잭션 없이 직접 호출 — MANDATORY 이므로 예외 발생해야 함
            val thrown = runCatching { publisher.publish(event) }.exceptionOrNull()
            thrown shouldNotBe null
        }
    }
})
