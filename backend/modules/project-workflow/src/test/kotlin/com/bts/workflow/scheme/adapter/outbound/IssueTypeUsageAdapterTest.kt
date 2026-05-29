// IssueTypeUsageAdapter Testcontainers 통합 테스트 — workflow_scheme_issue_type_mappings count 검증

package com.bts.workflow.scheme.adapter.outbound

import com.bts.workflow.scheme.domain.WorkflowSchemeId
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * [IssueTypeUsageAdapter] Testcontainers 통합 테스트.
 *
 * `workflow_scheme_issue_type_mappings` 테이블에서 특정 issue_type_id 가 참조된
 * 매핑 수를 반환하는 [IssueTypeUsageAdapter.countSchemeMappings] 를 검증한다.
 *
 * ## 시나리오
 * - 매핑 N건 INSERT → N 반환
 * - 매핑 0건 → 0 반환
 *
 * Spring AOP 없이 직접 인스턴스화 환경. [WorkflowSchemeEventPublisherIntegrationTest] 와
 * 동일한 Testcontainers singleton 패턴 및 마이그레이션 전략을 사용한다.
 */
class IssueTypeUsageAdapterTest : DescribeSpec({

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

    fun newDsl(): Pair<Connection, DSLContext> {
        val conn = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        return conn to DSL.using(conn, SQLDialect.POSTGRES)
    }

    /**
     * 테스트용 workflow INSERT 헬퍼.
     *
     * Testcontainers 환경에서는 YamlSeedService(ApplicationReadyEvent)가 실행되지 않아
     * workflows 테이블이 비어있다. 직접 INSERT 해 FK 를 만족시킨다.
     */
    fun insertWorkflow(dsl: DSLContext): UUID {
        val workflowId = UUID.randomUUID()
        dsl
            .insertInto(DSL.table("workflows"))
            .columns(
                DSL.field("id", UUID::class.java),
                DSL.field("key", String::class.java),
                DSL.field("name", String::class.java),
            )
            .values(workflowId, "test-workflow-${workflowId.toString().take(8)}", "테스트 워크플로우")
            .execute()
        return workflowId
    }

    /**
     * 테스트용 workflow_scheme mapping INSERT 헬퍼.
     *
     * V201 seed 로 삽입된 표준 스킴(id=1~4) 이 존재하므로 schemeId=1 을 재활용한다.
     * [insertWorkflow] 로 생성한 workflow_id 를 FK 로 사용한다.
     */
    fun insertMapping(
        dsl: DSLContext,
        schemeId: Long,
        issueTypeId: Long,
    ) {
        val workflowId = insertWorkflow(dsl)

        dsl
            .insertInto(DSL.table("workflow_scheme_issue_type_mappings"))
            .columns(
                DSL.field("scheme_id", Long::class.java),
                DSL.field("issue_type_id", Long::class.java),
                DSL.field("workflow_id", UUID::class.java),
            )
            .values(schemeId, issueTypeId, workflowId)
            .execute()
    }

    fun deleteTestMappings(
        dsl: DSLContext,
        issueTypeId: Long,
    ) {
        dsl
            .deleteFrom(DSL.table("workflow_scheme_issue_type_mappings"))
            .where(DSL.field("issue_type_id", Long::class.java).eq(issueTypeId))
            .execute()
    }

    describe("IssueTypeUsageAdapter.countSchemeMappings") {

        it("매핑 3건 INSERT 후 countSchemeMappings 는 3 을 반환한다") {
            val (conn, dsl) = newDsl()
            conn.use {
                // 테스트용 issue_type 삽입 — key는 VARCHAR(30) 제약으로 UUID 짧게 사용 (8자 hex)
                val shortId = UUID.randomUUID().toString().replace("-", "").take(8)
                val issueTypeId =
                    dsl
                        .insertInto(DSL.table("issue_types"))
                        .columns(
                            DSL.field("key", String::class.java),
                            DSL.field("name", String::class.java),
                        )
                        .values("t-cnt-$shortId", "테스트 카운트 타입")
                        .returningResult(DSL.field("id", Long::class.java))
                        .fetchOne()
                        ?.get(DSL.field("id", Long::class.java))
                        ?: error("issue_type INSERT 실패")

                // V201 seed 표준 스킴 id=1,2,3 에 각각 매핑 삽입
                insertMapping(dsl, schemeId = 1L, issueTypeId = issueTypeId)
                insertMapping(dsl, schemeId = 2L, issueTypeId = issueTypeId)
                insertMapping(dsl, schemeId = 3L, issueTypeId = issueTypeId)

                val adapter = IssueTypeUsageAdapter(dsl)
                val count = adapter.countSchemeMappings(issueTypeId)

                count shouldBe 3L

                // 정리
                deleteTestMappings(dsl, issueTypeId)
                dsl.deleteFrom(DSL.table("issue_types"))
                    .where(DSL.field("id", Long::class.java).eq(issueTypeId))
                    .execute()
            }
        }

        it("매핑 0건이면 countSchemeMappings 는 0 을 반환한다") {
            val (conn, dsl) = newDsl()
            conn.use {
                val shortId = UUID.randomUUID().toString().replace("-", "").take(8)
                val issueTypeId =
                    dsl
                        .insertInto(DSL.table("issue_types"))
                        .columns(
                            DSL.field("key", String::class.java),
                            DSL.field("name", String::class.java),
                        )
                        .values("t-zero-$shortId", "테스트 제로 타입")
                        .returningResult(DSL.field("id", Long::class.java))
                        .fetchOne()
                        ?.get(DSL.field("id", Long::class.java))
                        ?: error("issue_type INSERT 실패")

                val adapter = IssueTypeUsageAdapter(dsl)
                val count = adapter.countSchemeMappings(issueTypeId)

                count shouldBe 0L

                // 정리
                dsl.deleteFrom(DSL.table("issue_types"))
                    .where(DSL.field("id", Long::class.java).eq(issueTypeId))
                    .execute()
            }
        }
    }
}) {
    companion object {
        /**
         * V200 + V201 마이그레이션 적용.
         *
         * [WorkflowSchemeEventPublisherIntegrationTest.applyMigrations] 와 동일 전략.
         * cross-BC FK (issue_type_id → issue_types.id) 를 위해
         * issue-tracking + project-workflow 마이그레이션을 함께 적용한다.
         */
        private fun applyMigrations(postgres: PostgreSQLContainer<*>) {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .target("200")
                .load()
                .migrate()

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

            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .load()
                .migrate()
        }
    }
}
