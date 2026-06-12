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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * IssueEventPublisher 통합 테스트.
 *
 * quay.io/tembo/pg16-pgmq:latest 컨테이너를 Testcontainers 로 기동해
 * 실제 pgmq 큐에 이벤트가 enqueue 되는지 검증한다.
 *
 * Spring AOP 없이 직접 인스턴스화 환경이므로 @Transactional(MANDATORY) 런타임 강제는
 * 리플렉션 어노테이션 확인으로 대체한다. 실제 커넥션 공유를 위해 단일 JDBC Connection 위에
 * DSLContext 와 수동 트랜잭션을 구성한다.
 */
class IssueEventPublisherTest : DescribeSpec({

    // ── Testcontainers: quay.io/tembo/pg16-pgmq:latest ──────────────────────
    val temboImage =
        DockerImageName
            .parse("quay.io/tembo/pg16-pgmq:latest")
            .asCompatibleSubstituteFor("postgres")

    val postgres =
        PostgreSQLContainer(temboImage)
            .withDatabaseName("bts_test")
            .withUsername("test")
            .withPassword("test")

    beforeSpec {
        postgres.start()

        // V001 + V002 통합 SQL 적용 (init_codegen.sql 재사용)
        Class.forName("org.postgresql.Driver")
        val initSql =
            IssueEventPublisherTest::class.java
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

    // ── 공통 헬퍼 ────────────────────────────────────────────────────────────

    fun newConnection(): Connection =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password,
        )

    fun buildObjectMapper(): ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())

    /**
     * 단일 [conn] 위에 DSLContext 를 구성하고 트랜잭션을 열어 [block] 을 실행한 뒤 커밋한다.
     *
     * publisher 내부 DSLContext 와 트랜잭션이 같은 커넥션을 공유해야
     * pgmq.send 결과가 커밋 후 pgmq.read 로 확인 가능하다.
     */
    fun withTransaction(
        conn: Connection,
        block: (DSLContext) -> Unit,
    ) {
        val savedAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            val dsl = DSL.using(conn, SQLDialect.POSTGRES)
            block(dsl)
            conn.commit()
        } catch (ex: Exception) {
            conn.rollback()
            throw ex
        } finally {
            conn.autoCommit = savedAutoCommit
        }
    }

    // pgmq.read 로 큐에서 메시지 1건 읽기 (visibility timeout = 1초, qty = 1)
    fun readOneMessage(conn: Connection): org.jooq.Record? {
        val dsl = DSL.using(conn, SQLDialect.POSTGRES)
        return dsl.fetch("SELECT * FROM pgmq.read('q_issue_events', 1, 1)").firstOrNull()
    }

    // 큐 비우기 — 각 테스트 독립성 보장
    fun purgeQueue(conn: Connection) {
        DSL.using(conn, SQLDialect.POSTGRES)
            .execute("SELECT pgmq.purge_queue('q_issue_events')")
    }

    // ── 테스트 케이스 ─────────────────────────────────────────────────────────

    describe("IssueEventPublisher.publish") {

        it("IssueCreated 발행 후 q_issue_events 에 메시지 1건이 존재하며 type=issue.created 와 issueKey 를 포함한다") {
            val mapper = buildObjectMapper()

            newConnection().use { conn ->
                purgeQueue(conn)

                val event =
                    IssueCreated(
                        issueKey = IssueKey("ATLAS-1"),
                        projectKey = "ATLAS",
                        summary = "첫 번째 이슈",
                        reporterId = ActorId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                        actorId = ActorId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    )

                // publisher 와 트랜잭션이 같은 커넥션을 공유 — pgmq.send 결과가 트랜잭션 내에서 가시
                withTransaction(conn) { dsl ->
                    val publisher = IssueEventPublisher(dsl, mapper)
                    publisher.publish(event)
                }

                // 커밋 후 읽기 — visibility_timeout=1 이면 1초 동안 잠금되므로 별도 커넥션으로 read
                val readConn = newConnection()
                val record = readOneMessage(readConn)
                readConn.close()

                record shouldNotBe null

                val body = record!!.get("message", String::class.java)
                val json = mapper.readTree(body)

                json.get("type").asText() shouldBe "issue.created"
                json.get("issueKey").asText() shouldBe "ATLAS-1"
                json.get("projectKey").asText() shouldBe "ATLAS"
                json.get("summary").asText() shouldBe "첫 번째 이슈"
            }
        }

        it("IssueTransitioned 발행 후 q_issue_events 에 메시지 1건이 존재하며 type=issue.transitioned 를 포함한다") {
            val mapper = buildObjectMapper()

            newConnection().use { conn ->
                purgeQueue(conn)

                val event =
                    IssueTransitioned(
                        issueKey = IssueKey("ATLAS-2"),
                        fromState = "open",
                        toState = "IN_PROGRESS",
                        actorId = ActorId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    )

                withTransaction(conn) { dsl ->
                    val publisher = IssueEventPublisher(dsl, mapper)
                    publisher.publish(event)
                }

                val readConn = newConnection()
                val record = readOneMessage(readConn)
                readConn.close()

                record shouldNotBe null

                val body = record!!.get("message", String::class.java)
                val json = mapper.readTree(body)

                json.get("type").asText() shouldBe "issue.transitioned"
                json.get("issueKey").asText() shouldBe "ATLAS-2"
                json.get("fromState").asText() shouldBe "open"
                json.get("toState").asText() shouldBe "IN_PROGRESS"
            }
        }

        it("Propagation.MANDATORY — publish 메서드에 @Transactional(MANDATORY) 어노테이션이 선언되어 있다") {
            // Spring AOP 프록시 없는 직접 인스턴스화 환경에서는 @Transactional 이 적용되지 않는다.
            // 어노테이션 존재 자체를 리플렉션으로 검증 — 런타임 강제(MANDATORY)는 Spring 컨텍스트 통합 테스트 범위.
            val method = IssueEventPublisher::class.java.getMethod("publish", IssueDomainEvent::class.java)
            val txAnnotation =
                method.getAnnotation(
                    org.springframework.transaction.annotation.Transactional::class.java,
                )

            txAnnotation shouldNotBe null
            txAnnotation!!.propagation shouldBe org.springframework.transaction.annotation.Propagation.MANDATORY
        }
    }
})
