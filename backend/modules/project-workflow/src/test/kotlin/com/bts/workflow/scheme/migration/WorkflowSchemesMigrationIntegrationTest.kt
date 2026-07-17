// project-workflow V004 마이그레이션 검증 — workflow_schemes 3 테이블 + pgmq 큐 + 4 표준 seed 존재 확인

package com.bts.workflow.scheme.migration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * Flyway V001~V004 마이그레이션 적용 후 workflow_schemes 3 테이블 + pgmq 큐 + 4 표준 seed 를 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 검증 범위.
 * - workflow_schemes 4 row 존재 (software-scheme / bug-tracking-scheme / simple-scheme / kanban-scheme)
 * - 4 row 전부 is_default = true
 * - project_workflow_scheme_assignments 0 row (application 레이어 UPSERT 동작, EC-1)
 * - workflow_scheme_issue_type_mappings 4 row 존재 (default mapping, issue_type_id IS NULL, 각 스킴 1건)
 * - pgmq queue q_workflow_scheme_events 존재 (pgmq.list_queues() 확인)
 * - partial UNIQUE INDEX ix_scheme_default_mapping 존재
 * - ix_workflow_schemes_key_active 부분 인덱스 존재
 * - TIMESTAMPTZ 타입 강제 (DATA.md §4)
 *
 * 이미지 선택 이유.
 * V004 마이그레이션이 pgmq 확장 + SELECT pgmq.create() 를 사용하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 *
 * 참조. spec §5.1 / FR-WF-02 / D13 결정 (issue-tracking V003 → project-workflow V004 순서).
 */
@Testcontainers
class WorkflowSchemesMigrationIntegrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V004 pgmq 확장 + pgmq.create() 요구로 인해 tembo 이미지 사용.
        // asCompatibleSubstituteFor("postgres"): Testcontainers 이미지 호환성 검증 우회.
        // ADR 2026-05-22-pgmq-postgres-image 와 동일 패턴.
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            // D13 결정: 프로덕션에서는 issue-tracking V003 → project-workflow V201 (이전 V004) 순서 (단일 DB).
            // 본 PR 의 cross-BC dep (implementation(project(":modules:issue-tracking"))) 으로
            // testRuntimeClasspath 에 issue-tracking 마이그레이션도 포함되어 V001~V003 동시 적용 가능.
            //
            // 해결 전략 (2단계):
            //   1단계: Flyway target=200 으로 V200 (project-workflow init) 까지 적용
            //          (cross-BC dep 으로 issue-tracking V001~V003 도 동시 적용 — issue_types 진짜 테이블 V003 으로 생성).
            //   2단계: issue_types 스텁 IF NOT EXISTS 안전판 → Flyway migrate 재실행 (V201 적용).
            //
            // Flyway 가 이미 schema history 를 갖고 있으면 "non-empty schema" 오류 없이
            // 남은 마이그레이션만 추가 실행한다.
            val flyway =
                Flyway.configure()
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .placeholderReplacement(false)
                    .locations(
                        "classpath:db/migration/issue-tracking",
                        "classpath:db/migration/project-workflow",
                    )
                    .target("200")
                    .load()
            flyway.migrate()

            // issue_types 스텁 — cross-BC FK (issue_type_id REFERENCES issue_types(id)) 통과용.
            // 프로덕션: issue-tracking V003 이 먼저 실행해 실제 테이블 존재.
            // 테스트: project-workflow 모듈 Flyway 만 실행되므로 스텁으로 대체.
            createIssueTypesStub()

            // V002~V004 실행 (target 제거 → LATEST)
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .load()
                .migrate()

            // YamlSeedService 는 Spring ApplicationReadyEvent 에서 workflows 테이블을 채운다.
            // 통합 테스트는 Spring 컨텍스트 없이 실행되므로 4 표준 workflow seed 를 직접 INSERT.
            // V004 의 default mapping seed (JOIN workflows) 는 Flyway migrate 시점에 workflows 가
            // 비어 있어 0건 삽입됨. seed INSERT 후 mapping 을 수동으로 삽입해 검증한다.
            seedWorkflowsAndMappings()
        }

        /**
         * issue_types 스텁 테이블 생성 — 테스트 전용 first-pass FK 안전판.
         *
         * 프로덕션. issue-tracking Flyway V003 이 issue_types 테이블을 생성 (project-workflow V200/V201 이전).
         * 테스트. cross-BC classpath 의 issue-tracking V003 가 testRuntimeOnly 의존으로 적용 가능하나,
         * 본 IT 가 V200/V201 단계까지의 Flyway 동작을 단계별 검증하므로 V003 적용 전 시점에 FK 선언이 통과해야 한다.
         * V201 의 workflow_scheme_issue_type_mappings.issue_type_id BIGINT REFERENCES issue_types(id)
         * FK 선언을 first-pass 에 통과시키기 위해 최소 schema 의 스텁 테이블을 생성. IF NOT EXISTS 로 멱등 보장 —
         * V003 가 이후 적용되어 실제 issue_types 가 생성되어도 안전.
         */
        private fun createIssueTypesStub() {
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
        }

        private fun seedWorkflowsAndMappings() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                // 4 표준 workflow seed (YamlSeedService 역할 대체 — 테스트 전용)
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO workflows (key, name, description) VALUES
                            ('software-default', '소프트웨어 개발 기본 워크플로우',  NULL),
                            ('bug-tracking',     '버그 추적 워크플로우',             NULL),
                            ('simple',           '단순 워크플로우',                  NULL),
                            ('kanban-basic',     '칸반 기본 워크플로우',             NULL)
                        ON CONFLICT (key) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }

            // RED (task-4): default mapping 백필을 아직 호출하지 않는다 — V004 migrate 시점의
            // JOIN INSERT 는 workflows 가 비어 있어 0건이었으므로, workflow seed 만으로는
            // workflow_scheme_issue_type_mappings 가 여전히 0행이다. 판별자.
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun tableExists(tableName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables" +
                    " WHERE table_schema = 'public' AND table_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    private fun indexExists(indexName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM pg_indexes" +
                    " WHERE schemaname = 'public' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    private fun columnDataType(
        tableName: String,
        columnName: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT data_type FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun countRows(
        tableName: String,
        whereClause: String = "",
    ): Int =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val sql = "SELECT COUNT(*) FROM $tableName${if (whereClause.isNotEmpty()) " WHERE $whereClause" else ""}"
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun schemeKeyExists(key: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM workflow_schemes WHERE key = ?",
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    private fun pgmqQueueExists(queueName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM pgmq.list_queues() WHERE queue_name = ?",
            ).use { stmt ->
                stmt.setString(1, queueName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // ── 테이블 3개 존재 검증 ──────────────────────────────────────────────────

    @Test
    fun `V004 workflow_schemes 테이블 존재`() {
        assertThat(tableExists("workflow_schemes")).isTrue()
    }

    @Test
    fun `V004 project_workflow_scheme_assignments 테이블 존재`() {
        assertThat(tableExists("project_workflow_scheme_assignments")).isTrue()
    }

    @Test
    fun `V004 workflow_scheme_issue_type_mappings 테이블 존재`() {
        assertThat(tableExists("workflow_scheme_issue_type_mappings")).isTrue()
    }

    // ── workflow_schemes 4 표준 seed 검증 ─────────────────────────────────────

    @Test
    fun `V004 workflow_schemes 에 정확히 4 row 존재`() {
        assertThat(countRows("workflow_schemes")).isEqualTo(4)
    }

    @Test
    fun `V004 모든 4 row 의 is_default 는 true`() {
        assertThat(countRows("workflow_schemes", "is_default = true")).isEqualTo(4)
    }

    @Test
    fun `V004 software-scheme key 존재`() {
        assertThat(schemeKeyExists("software-scheme")).isTrue()
    }

    @Test
    fun `V004 bug-tracking-scheme key 존재`() {
        assertThat(schemeKeyExists("bug-tracking-scheme")).isTrue()
    }

    @Test
    fun `V004 simple-scheme key 존재`() {
        assertThat(schemeKeyExists("simple-scheme")).isTrue()
    }

    @Test
    fun `V004 kanban-scheme key 존재`() {
        assertThat(schemeKeyExists("kanban-scheme")).isTrue()
    }

    // ── project_workflow_scheme_assignments 빈 검증 (EC-1) ───────────────────

    @Test
    fun `V004 project_workflow_scheme_assignments 는 0 row`() {
        assertThat(countRows("project_workflow_scheme_assignments")).isEqualTo(0)
    }

    // ── workflow_scheme_issue_type_mappings default mapping 4건 검증 ──────────

    @Test
    fun `Flyway + 백필 후 기본 매핑 4행이 실재한다 (R6 — 손수 심지 않음)`() {
        assertThat(countRows("workflow_scheme_issue_type_mappings")).isEqualTo(4)
    }

    @Test
    fun `V004 workflow_scheme_issue_type_mappings 4 row 모두 issue_type_id IS NULL`() {
        assertThat(countRows("workflow_scheme_issue_type_mappings", "issue_type_id IS NULL")).isEqualTo(4)
    }

    // ── pgmq 큐 존재 검증 ────────────────────────────────────────────────────

    @Test
    fun `V004 q_workflow_scheme_events 큐 존재`() {
        assertThat(pgmqQueueExists("q_workflow_scheme_events")).isTrue()
    }

    // ── 인덱스 존재 검증 ─────────────────────────────────────────────────────

    @Test
    fun `V004 ix_workflow_schemes_key_active 부분 인덱스 존재`() {
        assertThat(indexExists("ix_workflow_schemes_key_active")).isTrue()
    }

    @Test
    fun `V004 ix_scheme_default_mapping partial UNIQUE INDEX 존재`() {
        assertThat(indexExists("ix_scheme_default_mapping")).isTrue()
    }

    @Test
    fun `V004 ix_pwsa_scheme 인덱스 존재`() {
        assertThat(indexExists("ix_pwsa_scheme")).isTrue()
    }

    // ── TIMESTAMPTZ 타입 검증 (DATA.md §4) ───────────────────────────────────

    @Test
    fun `V004 workflow_schemes created_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("workflow_schemes", "created_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V004 workflow_schemes updated_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("workflow_schemes", "updated_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V004 workflow_schemes deleted_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("workflow_schemes", "deleted_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V004 project_workflow_scheme_assignments assigned_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("project_workflow_scheme_assignments", "assigned_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V202 project_workflow_scheme_assignments project_id 는 UUID 타입`() {
        // V202 BIGINT → UUID 정정 검증. projects.id (UUID) 와 타입 일치.
        assertThat(columnDataType("project_workflow_scheme_assignments", "project_id"))
            .isEqualTo("uuid")
    }

    @Test
    fun `V004 workflow_scheme_issue_type_mappings created_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("workflow_scheme_issue_type_mappings", "created_at"))
            .isEqualTo("timestamp with time zone")
    }
}
