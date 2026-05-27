// WorkflowSchemeEventPublisher Testcontainers 통합 테스트 — pgmq q_workflow_scheme_events 큐 publish 3 이벤트 + JSON 라운드트립 검증

package com.bts.workflow.scheme.adapter.outbound

import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.bts.workflow.scheme.event.WorkflowSchemeAssignedEvent
import com.bts.workflow.scheme.event.WorkflowSchemeDeletedEvent
import com.bts.workflow.scheme.event.WorkflowSchemeDomainEvent
import com.bts.workflow.scheme.event.WorkflowSchemeUpdatedEvent
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.flywaydb.core.Flyway
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
 * [WorkflowSchemeEventPublisher] Testcontainers 통합 테스트.
 *
 * quay.io/tembo/pg16-pgmq:latest 컨테이너를 기동해 실제 pgmq 큐에 3 이벤트 타입을 publish 하고
 * pgmq.read 로 메시지를 읽어 Jackson JSON 라운드트립을 검증한다.
 *
 * Spring AOP 없이 직접 인스턴스화 환경이므로 @Transactional(MANDATORY) 런타임 강제는
 * 리플렉션 어노테이션 확인으로 대체한다. 실제 커넥션 공유를 위해 단일 JDBC Connection 위에
 * DSLContext 와 수동 트랜잭션을 구성한다.
 *
 * 이미지 선택 이유.
 * V004 마이그레이션이 pgmq 확장 + SELECT pgmq.create() 를 사용하므로 postgres:16-alpine 사용 불가.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 *
 * 참조. PR #17 IssueEventPublisherTest 패턴 일치 (issue-tracking BC).
 */
class WorkflowSchemeEventPublisherIntegrationTest : DescribeSpec({

    // ── Testcontainers: quay.io/tembo/pg16-pgmq:latest ──────────────────────
    // pgmq 확장 사전 설치 이미지 — V004 pgmq.create() 요구로 postgres:16-alpine 사용 불가
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
        Class.forName("org.postgresql.Driver")
        applyMigrations(postgres)
    }

    afterSpec {
        postgres.stop()
    }

    // ── 공통 헬퍼 ────────────────────────────────────────────────────────────

    fun newConnection(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    fun buildObjectMapper(): ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .findAndRegisterModules()

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

    /** pgmq.read 로 큐에서 메시지 1건 읽기 (visibility timeout = 1초, qty = 1). */
    fun readOneMessage(conn: Connection): org.jooq.Record? {
        val dsl = DSL.using(conn, SQLDialect.POSTGRES)
        return dsl.fetch(
            "SELECT * FROM pgmq.read(?, 1, 1)",
            WorkflowSchemeEventPublisher.QUEUE_NAME,
        ).firstOrNull()
    }

    /** 큐 비우기 — 각 테스트 독립성 보장. */
    fun purgeQueue(conn: Connection) {
        DSL.using(conn, SQLDialect.POSTGRES)
            .execute("SELECT pgmq.purge_queue(?)", WorkflowSchemeEventPublisher.QUEUE_NAME)
    }

    // ── WorkflowSchemeAssignedEvent 검증 ─────────────────────────────────────

    describe("WorkflowSchemeEventPublisher.publish") {

        it("WorkflowSchemeAssignedEvent 발행 후 q_workflow_scheme_events 에 메시지 1건이 존재하며 type=WorkflowSchemeAssigned 를 포함한다") {
            val mapper = buildObjectMapper()

            newConnection().use { conn ->
                purgeQueue(conn)

                val event =
                    WorkflowSchemeAssignedEvent(
                        schemeId = WorkflowSchemeId(1L),
                        projectId = UUID.fromString("00000000-0000-0000-0000-000000000042"),
                        assignedBy = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        occurredAt = Instant.parse("2026-05-26T00:00:00Z"),
                    )

                withTransaction(conn) { dsl ->
                    val publisher = WorkflowSchemeEventPublisher(dsl, mapper)
                    publisher.publish(event)
                }

                val readConn = newConnection()
                val record = readOneMessage(readConn)
                readConn.close()

                record shouldNotBe null

                val body = record!!.get("message", String::class.java)
                val json = mapper.readTree(body)

                json.get("type").asText() shouldBe "WorkflowSchemeAssigned"
                json.get("projectId").asText() shouldBe "00000000-0000-0000-0000-000000000042"
            }
        }

        it("WorkflowSchemeAssignedEvent JSON 라운드트립 — pgmq.read 역직렬화 후 원본과 동일하다") {
            val mapper = buildObjectMapper()

            val original =
                WorkflowSchemeAssignedEvent(
                    schemeId = WorkflowSchemeId(2L),
                    projectId = UUID.fromString("00000000-0000-0000-0000-000000000099"),
                    assignedBy = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                    occurredAt = Instant.parse("2026-05-26T12:00:00Z"),
                )

            newConnection().use { conn ->
                purgeQueue(conn)

                withTransaction(conn) { dsl ->
                    val publisher = WorkflowSchemeEventPublisher(dsl, mapper)
                    publisher.publish(original)
                }

                val readConn = newConnection()
                val record = readOneMessage(readConn)
                readConn.close()

                record shouldNotBe null

                val body = record!!.get("message", String::class.java)
                val restored = mapper.readValue(body, WorkflowSchemeDomainEvent::class.java)

                restored shouldBe original
            }
        }

        // ── WorkflowSchemeUpdatedEvent 검증 ──────────────────────────────────

        it("WorkflowSchemeUpdatedEvent 발행 후 q_workflow_scheme_events 에 메시지 1건이 존재하며 type=WorkflowSchemeUpdated 를 포함한다") {
            val mapper = buildObjectMapper()

            newConnection().use { conn ->
                purgeQueue(conn)

                val event =
                    WorkflowSchemeUpdatedEvent(
                        schemeId = WorkflowSchemeId(3L),
                        field = "name",
                        occurredAt = Instant.parse("2026-05-26T00:00:00Z"),
                    )

                withTransaction(conn) { dsl ->
                    val publisher = WorkflowSchemeEventPublisher(dsl, mapper)
                    publisher.publish(event)
                }

                val readConn = newConnection()
                val record = readOneMessage(readConn)
                readConn.close()

                record shouldNotBe null

                val body = record!!.get("message", String::class.java)
                val json = mapper.readTree(body)

                json.get("type").asText() shouldBe "WorkflowSchemeUpdated"
                json.get("field").asText() shouldBe "name"
            }
        }

        it("WorkflowSchemeUpdatedEvent JSON 라운드트립 — pgmq.read 역직렬화 후 원본과 동일하다") {
            val mapper = buildObjectMapper()

            val original =
                WorkflowSchemeUpdatedEvent(
                    schemeId = WorkflowSchemeId(4L),
                    field = "description",
                    occurredAt = Instant.parse("2026-05-26T06:00:00Z"),
                )

            newConnection().use { conn ->
                purgeQueue(conn)

                withTransaction(conn) { dsl ->
                    val publisher = WorkflowSchemeEventPublisher(dsl, mapper)
                    publisher.publish(original)
                }

                val readConn = newConnection()
                val record = readOneMessage(readConn)
                readConn.close()

                record shouldNotBe null

                val body = record!!.get("message", String::class.java)
                val restored = mapper.readValue(body, WorkflowSchemeDomainEvent::class.java)

                restored shouldBe original
            }
        }

        // ── WorkflowSchemeDeletedEvent 검증 ──────────────────────────────────

        it("WorkflowSchemeDeletedEvent 발행 후 q_workflow_scheme_events 에 메시지 1건이 존재하며 type=WorkflowSchemeDeleted 를 포함한다") {
            val mapper = buildObjectMapper()

            newConnection().use { conn ->
                purgeQueue(conn)

                val event =
                    WorkflowSchemeDeletedEvent(
                        schemeId = WorkflowSchemeId(5L),
                        occurredAt = Instant.parse("2026-05-26T00:00:00Z"),
                    )

                withTransaction(conn) { dsl ->
                    val publisher = WorkflowSchemeEventPublisher(dsl, mapper)
                    publisher.publish(event)
                }

                val readConn = newConnection()
                val record = readOneMessage(readConn)
                readConn.close()

                record shouldNotBe null

                val body = record!!.get("message", String::class.java)
                val json = mapper.readTree(body)

                json.get("type").asText() shouldBe "WorkflowSchemeDeleted"
            }
        }

        it("WorkflowSchemeDeletedEvent JSON 라운드트립 — pgmq.read 역직렬화 후 원본과 동일하다") {
            val mapper = buildObjectMapper()

            val original =
                WorkflowSchemeDeletedEvent(
                    schemeId = WorkflowSchemeId(6L),
                    occurredAt = Instant.parse("2026-05-26T18:00:00Z"),
                )

            newConnection().use { conn ->
                purgeQueue(conn)

                withTransaction(conn) { dsl ->
                    val publisher = WorkflowSchemeEventPublisher(dsl, mapper)
                    publisher.publish(original)
                }

                val readConn = newConnection()
                val record = readOneMessage(readConn)
                readConn.close()

                record shouldNotBe null

                val body = record!!.get("message", String::class.java)
                val restored = mapper.readValue(body, WorkflowSchemeDomainEvent::class.java)

                restored shouldBe original
            }
        }

        // ── Propagation.MANDATORY 어노테이션 검증 ────────────────────────────

        it("Propagation.MANDATORY — publish 메서드에 @Transactional(MANDATORY) 어노테이션이 선언되어 있다") {
            // Spring AOP 프록시 없는 직접 인스턴스화 환경에서는 @Transactional 이 적용되지 않는다.
            // 어노테이션 존재 자체를 리플렉션으로 검증 — 런타임 강제(MANDATORY)는 Spring 컨텍스트 통합 테스트 범위.
            val method =
                WorkflowSchemeEventPublisher::class.java.getMethod(
                    "publish",
                    WorkflowSchemeDomainEvent::class.java,
                )
            val txAnnotation =
                method.getAnnotation(org.springframework.transaction.annotation.Transactional::class.java)

            txAnnotation shouldNotBe null
            txAnnotation!!.propagation shouldBe org.springframework.transaction.annotation.Propagation.MANDATORY
        }
    }
}) {
    companion object {
        /**
         * V200 + V201 마이그레이션 적용 (project-workflow init + workflow_schemes).
         *
         * Cross-BC FK 패턴 (WorkflowSchemesMigrationIntegrationTest 와 동일).
         * 1단계: Flyway target=200 으로 V200 까지 적용 (cross-BC dep 으로 issue-tracking V001~V003 동시 적용).
         * 2단계: issue_types 스텁 테이블 IF NOT EXISTS — V003 가 진짜 테이블을 만들면 no-op.
         * 3단계: Flyway migrate 재실행 (V201 적용).
         */
        private fun applyMigrations(postgres: PostgreSQLContainer<*>) {
            // 1단계: V200 (project-workflow init) 까지 적용
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration")
                .target("200")
                .load()
                .migrate()

            // 2단계: 스텁 테이블 — cross-BC FK 통과용 (V001/V003 이 먼저 실행되면 no-op)
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
                    // V202 FK (project_id → projects.id ON DELETE CASCADE) 를 위해 projects 스텁 보장.
                    // issue-tracking V001 이 먼저 실행되면 no-op.
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS projects (
                            id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                            key           VARCHAR(10)  NOT NULL UNIQUE,
                            name          VARCHAR(255) NOT NULL,
                            key_sequence  BIGINT       NOT NULL DEFAULT 0,
                            created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                            updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                            deleted_at    TIMESTAMPTZ
                        )
                        """.trimIndent(),
                    )
                }
            }

            // 3단계: V002~V004 적용
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
    }
}
