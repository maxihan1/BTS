// IssueEventPublisher Testcontainers 통합 테스트 — pgmq.send 후 q_issue_events 큐에 메시지 1건 + JSON 필드 검증

package com.bts.issue.event

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

    // prod IssueEventPublisher 가 주입받는 Spring Boot 기본 ObjectMapper 와 동일 설정을 재현한다 —
    // WRITE_DATES_AS_TIMESTAMPS=off 로 occurredAt 을 숫자가 아닌 ISO-8601 문자열로 직렬화한다.
    // (소비자 search WebhookDispatchWorker 가 occurredAt 을 문자열로 파싱하므로 계약상 필수, CONCERN-2)
    fun buildObjectMapper(): ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

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

                // 아래 wire 필드명은 소비자 search WebhookDispatchWorker 가 파싱한다 —
                // 소비자 갱신 없이 리네임 금지(모듈 간 계약 정본, CONCERN-1).
                json.get("type").asText() shouldBe "issue.created"
                json.get("issueKey").asText() shouldBe "ATLAS-1"
                json.get("projectKey").asText() shouldBe "ATLAS"
                json.get("summary").asText() shouldBe "첫 번째 이슈"
                // occurredAt 은 숫자 타임스탬프가 아닌 ISO-8601 문자열이어야 한다(소비자 파싱 계약, CONCERN-2).
                json.get("occurredAt").asText() shouldBe "2026-01-01T00:00:00Z"
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

                // 아래 wire 필드명은 소비자 search WebhookDispatchWorker 가 파싱한다 —
                // 소비자 갱신 없이 리네임 금지(모듈 간 계약 정본, CONCERN-1).
                json.get("type").asText() shouldBe "issue.transitioned"
                json.get("issueKey").asText() shouldBe "ATLAS-2"
                json.get("fromState").asText() shouldBe "open"
                json.get("toState").asText() shouldBe "IN_PROGRESS"
                // occurredAt 은 숫자 타임스탬프가 아닌 ISO-8601 문자열이어야 한다(소비자 파싱 계약, CONCERN-2).
                json.get("occurredAt").asText() shouldBe "2026-01-01T00:00:00Z"
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

    // ── FR-API-03 PR3 Task 2 — q_webhook_events dual-send ──────────────────────
    //
    // PUBLISHABLE 2종(issue.created / issue.transitioned)은 q_issue_events 뿐 아니라
    // q_webhook_events 에도 send 되어야 하고, 나머지 5종은 q_issue_events 에만 send 되어야 한다.
    // mock DSLContext 로 send 호출 인자(큐 이름)를 검증한다 — Testcontainers 실행 없이 빠르게
    // exhaustive 분류 회귀를 잡기 위함(판정 로직은 IssueEventPublisher 내부 private when).
    //
    // "q_webhook_events" 는 아직 프로덕션 코드에 상수로 존재하지 않으므로(GREEN 단계에서 추가)
    // 리터럴 문자열로 검증한다 — 컴파일은 항상 성공하고, RED 단계에서는 실제 assertion 이 실패한다.
    describe("IssueEventPublisher.publish — dual-send (FR-API-03 PR3 Task 2)") {

        val mapper = buildObjectMapper()

        fun mockDsl(): DSLContext {
            val dsl = mockk<DSLContext>()
            every { dsl.execute(any<String>(), any<String>(), any<String>()) } returns 1
            return dsl
        }

        context("PUBLISHABLE 이벤트(issue.created / issue.transitioned)") {

            it("IssueCreated 발행 시 q_issue_events 와 q_webhook_events 두 큐 모두에 send 한다") {
                val dsl = mockDsl()
                val publisher = IssueEventPublisher(dsl, mapper)

                val event =
                    IssueCreated(
                        issueKey = IssueKey("ATLAS-101"),
                        projectKey = "ATLAS",
                        summary = "dual-send 테스트",
                        reporterId = ActorId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                        actorId = ActorId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    )

                publisher.publish(event)

                verify(exactly = 1) {
                    dsl.execute(any<String>(), IssueEventPublisher.QUEUE_NAME, any<String>())
                }
                verify(exactly = 1) {
                    dsl.execute(any<String>(), "q_webhook_events", any<String>())
                }
            }

            it("IssueTransitioned 발행 시 q_issue_events 와 q_webhook_events 두 큐 모두에 send 한다") {
                val dsl = mockDsl()
                val publisher = IssueEventPublisher(dsl, mapper)

                val event =
                    IssueTransitioned(
                        issueKey = IssueKey("ATLAS-102"),
                        fromState = "open",
                        toState = "in_progress",
                        actorId = ActorId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    )

                publisher.publish(event)

                verify(exactly = 1) {
                    dsl.execute(any<String>(), IssueEventPublisher.QUEUE_NAME, any<String>())
                }
                verify(exactly = 1) {
                    dsl.execute(any<String>(), "q_webhook_events", any<String>())
                }
            }
        }

        context("non-PUBLISHABLE 이벤트(issue.updated 등 5종)는 q_issue_events 에만 send 한다") {

            it("IssueUpdated 발행 시 q_webhook_events 에는 send 하지 않는다") {
                val dsl = mockDsl()
                val publisher = IssueEventPublisher(dsl, mapper)

                publisher.publish(
                    IssueUpdated(
                        issueKey = IssueKey("ATLAS-103"),
                        fields = setOf("summary"),
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )

                verify(exactly = 1) {
                    dsl.execute(any<String>(), IssueEventPublisher.QUEUE_NAME, any<String>())
                }
                verify(exactly = 0) {
                    dsl.execute(any<String>(), "q_webhook_events", any<String>())
                }
            }

            it("IssueSoftDeleted 발행 시 q_webhook_events 에는 send 하지 않는다") {
                val dsl = mockDsl()
                val publisher = IssueEventPublisher(dsl, mapper)

                publisher.publish(
                    IssueSoftDeleted(
                        issueKey = IssueKey("ATLAS-104"),
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )

                verify(exactly = 1) {
                    dsl.execute(any<String>(), IssueEventPublisher.QUEUE_NAME, any<String>())
                }
                verify(exactly = 0) {
                    dsl.execute(any<String>(), "q_webhook_events", any<String>())
                }
            }

            it("IssueMentioned 발행 시 q_webhook_events 에는 send 하지 않는다") {
                val dsl = mockDsl()
                val publisher = IssueEventPublisher(dsl, mapper)

                publisher.publish(
                    IssueMentioned(
                        issueKey = IssueKey("ATLAS-105"),
                        projectKey = "ATLAS",
                        mentionedUserIds = listOf(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                        actorId = ActorId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                        sourceField = "description",
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )

                verify(exactly = 1) {
                    dsl.execute(any<String>(), IssueEventPublisher.QUEUE_NAME, any<String>())
                }
                verify(exactly = 0) {
                    dsl.execute(any<String>(), "q_webhook_events", any<String>())
                }
            }

            it("IssueDueSoon 발행 시 q_webhook_events 에는 send 하지 않는다") {
                val dsl = mockDsl()
                val publisher = IssueEventPublisher(dsl, mapper)

                publisher.publish(
                    IssueDueSoon(
                        issueKey = "ATLAS-106",
                        projectKey = "ATLAS",
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )

                verify(exactly = 1) {
                    dsl.execute(any<String>(), IssueEventPublisher.QUEUE_NAME, any<String>())
                }
                verify(exactly = 0) {
                    dsl.execute(any<String>(), "q_webhook_events", any<String>())
                }
            }

            it("IssueOverdue 발행 시 q_webhook_events 에는 send 하지 않는다") {
                val dsl = mockDsl()
                val publisher = IssueEventPublisher(dsl, mapper)

                publisher.publish(
                    IssueOverdue(
                        issueKey = "ATLAS-107",
                        projectKey = "ATLAS",
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )

                verify(exactly = 1) {
                    dsl.execute(any<String>(), IssueEventPublisher.QUEUE_NAME, any<String>())
                }
                verify(exactly = 0) {
                    dsl.execute(any<String>(), "q_webhook_events", any<String>())
                }
            }
        }
    }

    // ── FR-AT-01 Task 10 — q_automation_events fan-out ──────────────────────
    //
    // automation 관심 이벤트(issue.created / issue.updated / issue.commented)는 q_issue_events 뿐
    // 아니라 q_automation_events 에도 fan-out 되어야 하고, 나머지(issue.transitioned 등)는
    // q_automation_events 로 send 되지 않아야 한다(ADR 2026-07-10-fr-at-01-automation-triggers D2).
    // "q_automation_events" 와 IssueCommented 는 아직 프로덕션 코드에 존재하지 않으므로(GREEN 단계에서
    // 추가) 리터럴 문자열로 검증한다 — RED 단계에서는 컴파일 실패(IssueCommented 미정의)로 실패한다.
    describe("IssueEventPublisher.publish — automation fan-out (FR-AT-01 Task 10)") {

        val mapper = buildObjectMapper()

        fun mockDsl(): DSLContext {
            val dsl = mockk<DSLContext>()
            every { dsl.execute(any<String>(), any<String>(), any<String>()) } returns 1
            return dsl
        }

        context("automation 대상 이벤트(issue.created / issue.updated / issue.commented)") {

            it("IssueCreated 발행 시 q_automation_events 에도 send 한다") {
                val dsl = mockDsl()
                val publisher = IssueEventPublisher(dsl, mapper)

                publisher.publish(
                    IssueCreated(
                        issueKey = IssueKey("ATLAS-201"),
                        projectKey = "ATLAS",
                        summary = "automation fan-out 테스트",
                        reporterId = ActorId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                        actorId = ActorId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )

                verify(exactly = 1) {
                    dsl.execute(any<String>(), "q_automation_events", any<String>())
                }
            }

            it("IssueUpdated 발행 시 q_automation_events 에도 send 한다") {
                val dsl = mockDsl()
                val publisher = IssueEventPublisher(dsl, mapper)

                publisher.publish(
                    IssueUpdated(
                        issueKey = IssueKey("ATLAS-202"),
                        fields = setOf("summary"),
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )

                verify(exactly = 1) {
                    dsl.execute(any<String>(), "q_automation_events", any<String>())
                }
            }

            it("IssueCommented 발행 시 q_issue_events 와 q_automation_events 두 큐 모두에 send 한다") {
                val dsl = mockDsl()
                val publisher = IssueEventPublisher(dsl, mapper)

                publisher.publish(
                    IssueCommented(
                        issueKey = IssueKey("ATLAS-203"),
                        projectKey = "ATLAS",
                        commentId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
                        actorId = ActorId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )

                verify(exactly = 1) {
                    dsl.execute(any<String>(), IssueEventPublisher.QUEUE_NAME, any<String>())
                }
                verify(exactly = 1) {
                    dsl.execute(any<String>(), "q_automation_events", any<String>())
                }
                verify(exactly = 0) {
                    dsl.execute(any<String>(), "q_webhook_events", any<String>())
                }
            }
        }

        context("automation 비대상 이벤트는 q_automation_events 로 send 되지 않는다") {

            it("IssueTransitioned 발행 시 q_automation_events 에는 send 하지 않는다") {
                val dsl = mockDsl()
                val publisher = IssueEventPublisher(dsl, mapper)

                publisher.publish(
                    IssueTransitioned(
                        issueKey = IssueKey("ATLAS-204"),
                        fromState = "open",
                        toState = "in_progress",
                        actorId = ActorId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )

                verify(exactly = 0) {
                    dsl.execute(any<String>(), "q_automation_events", any<String>())
                }
            }

            it("IssueSoftDeleted 발행 시 q_automation_events 에는 send 하지 않는다") {
                val dsl = mockDsl()
                val publisher = IssueEventPublisher(dsl, mapper)

                publisher.publish(
                    IssueSoftDeleted(
                        issueKey = IssueKey("ATLAS-205"),
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )

                verify(exactly = 0) {
                    dsl.execute(any<String>(), "q_automation_events", any<String>())
                }
            }

            it("IssueMentioned 발행 시 q_automation_events 에는 send 하지 않는다") {
                val dsl = mockDsl()
                val publisher = IssueEventPublisher(dsl, mapper)

                publisher.publish(
                    IssueMentioned(
                        issueKey = IssueKey("ATLAS-206"),
                        projectKey = "ATLAS",
                        mentionedUserIds = listOf(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                        actorId = ActorId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                        sourceField = "description",
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )

                verify(exactly = 0) {
                    dsl.execute(any<String>(), "q_automation_events", any<String>())
                }
            }

            it("IssueDueSoon 발행 시 q_automation_events 에는 send 하지 않는다") {
                val dsl = mockDsl()
                val publisher = IssueEventPublisher(dsl, mapper)

                publisher.publish(
                    IssueDueSoon(
                        issueKey = "ATLAS-207",
                        projectKey = "ATLAS",
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )

                verify(exactly = 0) {
                    dsl.execute(any<String>(), "q_automation_events", any<String>())
                }
            }

            it("IssueOverdue 발행 시 q_automation_events 에는 send 하지 않는다") {
                val dsl = mockDsl()
                val publisher = IssueEventPublisher(dsl, mapper)

                publisher.publish(
                    IssueOverdue(
                        issueKey = "ATLAS-208",
                        projectKey = "ATLAS",
                        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )

                verify(exactly = 0) {
                    dsl.execute(any<String>(), "q_automation_events", any<String>())
                }
            }
        }
    }
})
