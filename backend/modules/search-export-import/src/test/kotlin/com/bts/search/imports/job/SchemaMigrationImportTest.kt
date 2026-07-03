// V604~V605 마이그레이션 검증 — import_jobs 테이블 + 18컬럼 + status/format CHECK + 2 인덱스 + q_import_jobs pgmq 큐 존재 확인 (FR-IM-01)

package com.bts.search.imports.job

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * Flyway V600~V604 마이그레이션 적용 후 import_jobs 테이블(FR-IM-01 CSV/JSON Import) + q_import_jobs 큐를 검증한다.
 *
 * **선례 V602 export_jobs 1:1 미러** — ExportJobsSchemaMigrationTest 구조를 그대로 복제하되 import 컬럼으로 조정한다.
 *
 * **pgmq 이미지 강제** — V604 는 `CREATE EXTENSION pgmq` + `SELECT pgmq.create('q_import_jobs')` 를
 * 사용하므로 pgmq 확장 바이너리가 없는 `postgres:16-alpine` 에서는 마이그레이션이 실패한다
 * (`could not open extension control file`, `IF NOT EXISTS` 로도 회피 불가 — 바이너리 부재). 따라서
 * pgmq 가 사전 설치된 `quay.io/tembo/pg16-pgmq:latest` 를 사용한다 (ADR 2026-05-22-pgmq-postgres-image,
 * ExportJobsSchemaMigrationTest 동일 패턴). `asCompatibleSubstituteFor("postgres")` 로
 * Testcontainers 이미지 호환성 검증을 우회한다.
 *
 * **JVM 단위 singleton container 패턴** — companion object `.apply { start() }` 로 JVM 라이프사이클에 바인딩.
 * `@Container` 라이프사이클 대신 Ryuk 의 JVM 종료 시 자동 정리에 위임해 동시 suite flaky 를 회피한다
 * (메모리 concurrent-testcontainers-suite-flaky / ExportJobsSchemaMigrationTest 동일 패턴).
 *
 * 검증 범위 (FR-IM-01 plan Task 1 / DATA.md §4 TIMESTAMPTZ 강제).
 * - import_jobs 테이블 존재 + 18개 컬럼 (plan DDL 정합, V605 attachments_object_key 포함)
 * - id = uuid PK NOT NULL
 * - source_object_key = text NOT NULL (업로드 원본 MinIO 키)
 * - dry_run = boolean NOT NULL (검증 전용 실행 여부)
 * - attachments_object_key = character varying(500) NULL 허용 (V605 — 첨부 zip MinIO 키, CSV/zip 미첨부 시 null)
 * - created_at = timestamptz NOT NULL (DATA.md §4.1#4 — TIMESTAMP without tz 금지)
 * - expires_at / started_at / completed_at = timestamptz NULL 허용 (종단/claim 시점에만 채움)
 * - chk_import_jobs_status / chk_import_jobs_format CHECK 제약 존재 + 위반 INSERT 거부
 * - 인덱스 idx_import_jobs_requester / idx_import_jobs_expires 존재
 * - pgmq 큐 q_import_jobs 존재 (pgmq.list_queues() 확인)
 *
 * 정보 스키마(information_schema / pg_indexes / pg_constraint) + pgmq.list_queues() 조회로 단언한다.
 * SQL 문자열 결합 없이 prepared statement(? 바인딩)를 사용한다 (NEVER-3).
 */
class SchemaMigrationImportTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V604 의 pgmq 확장 + pgmq.create() 요구로 tembo 이미지 사용.
        // asCompatibleSubstituteFor("postgres"): Testcontainers 이미지 호환성 검증 우회. ADR 2026-05-22-pgmq-postgres-image.
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        /**
         * JVM 단위 singleton PostgreSQL(pgmq) container.
         * `.apply { start() }` 로 JVM 시작 시점에 한 번만 기동되며, Ryuk 이 JVM 종료 시 자동 정리한다.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_import_jobs_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        // import_jobs 가 보유해야 하는 18개 컬럼 (plan §데이터 모델 DDL 정합, V605 attachments_object_key 포함).
        private val IMPORT_JOBS_COLUMNS =
            listOf(
                "id",
                "project_key",
                "format",
                "source_object_key",
                "dry_run",
                "requester_user_id",
                "status",
                "progress",
                "total_rows",
                "succeeded_rows",
                "failed_rows",
                "error_code",
                "error_log_object_key",
                "attachments_object_key",
                "expires_at",
                "created_at",
                "started_at",
                "completed_at",
            )

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/search-export-import")
                .load()
                .migrate()
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

    @Suppress("NestedBlockDepth")
    private fun columnsOf(tableName: String): List<String> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT column_name FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    val cols = mutableListOf<String>()
                    while (rs.next()) cols.add(rs.getString(1))
                    cols
                }
            }
        }

    @Suppress("NestedBlockDepth")
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

    @Suppress("NestedBlockDepth")
    private fun columnIsNullable(
        tableName: String,
        columnName: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
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

    // 주어진 이름의 제약(constraint)이 존재하는지 확인 — CHECK 제약 검증용.
    private fun constraintExists(constraintName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = ?",
            ).use { stmt ->
                stmt.setString(1, constraintName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // pgmq 큐 존재 확인 — pgmq.list_queues() 조회 (ExportJobsSchemaMigrationTest 동일 패턴).
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

    // import_jobs 한 행 INSERT — CHECK 제약 위반 유도용. status/format 을 명시 지정해 위반 셀을 주입한다.
    // id / project_key / format / source_object_key / requester_user_id 는 DEFAULT 가 없어 명시 지정.
    private fun insertImportJob(
        format: String,
        status: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO import_jobs" +
                    " (id, project_key, format, source_object_key, requester_user_id, status)" +
                    " VALUES (?, ?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setString(2, "ATLAS")
                stmt.setString(3, format)
                stmt.setString(4, "imports/atlas/source.csv")
                stmt.setObject(5, UUID.randomUUID())
                stmt.setString(6, status)
                stmt.executeUpdate()
            }
        }
    }

    // ── 테이블 / 컬럼 존재 검증 ────────────────────────────────────────────────

    @Test
    fun `V604 import_jobs 테이블 존재`() {
        assertThat(tableExists("import_jobs")).isTrue()
    }

    @Test
    fun `V604-V605 import_jobs 18개 컬럼 존재`() {
        assertThat(columnsOf("import_jobs"))
            .containsExactlyInAnyOrderElementsOf(IMPORT_JOBS_COLUMNS)
    }

    @Test
    fun `V604 import_jobs id 는 uuid PK NOT NULL`() {
        assertThat(columnDataType("import_jobs", "id")).isEqualTo("uuid")
        assertThat(columnIsNullable("import_jobs", "id")).isEqualTo("NO")
    }

    @Test
    fun `V604 import_jobs requester_user_id 는 uuid NOT NULL`() {
        assertThat(columnDataType("import_jobs", "requester_user_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("import_jobs", "requester_user_id")).isEqualTo("NO")
    }

    @Test
    fun `V604 import_jobs source_object_key 는 text NOT NULL`() {
        assertThat(columnDataType("import_jobs", "source_object_key")).isEqualTo("text")
        assertThat(columnIsNullable("import_jobs", "source_object_key")).isEqualTo("NO")
    }

    @Test
    fun `V604 import_jobs dry_run 은 boolean NOT NULL`() {
        assertThat(columnDataType("import_jobs", "dry_run")).isEqualTo("boolean")
        assertThat(columnIsNullable("import_jobs", "dry_run")).isEqualTo("NO")
    }

    @Test
    fun `V605 import_jobs attachments_object_key 는 character varying(500) NULL 허용`() {
        assertThat(columnDataType("import_jobs", "attachments_object_key")).isEqualTo("character varying")
        assertThat(columnIsNullable("import_jobs", "attachments_object_key")).isEqualTo("YES")
    }

    @Test
    fun `V604 import_jobs total_rows 는 bigint NULL 허용`() {
        assertThat(columnDataType("import_jobs", "total_rows")).isEqualTo("bigint")
        assertThat(columnIsNullable("import_jobs", "total_rows")).isEqualTo("YES")
    }

    @Test
    fun `V604 import_jobs succeeded_rows 는 bigint NOT NULL`() {
        assertThat(columnDataType("import_jobs", "succeeded_rows")).isEqualTo("bigint")
        assertThat(columnIsNullable("import_jobs", "succeeded_rows")).isEqualTo("NO")
    }

    @Test
    fun `V604 import_jobs failed_rows 는 bigint NOT NULL`() {
        assertThat(columnDataType("import_jobs", "failed_rows")).isEqualTo("bigint")
        assertThat(columnIsNullable("import_jobs", "failed_rows")).isEqualTo("NO")
    }

    @Test
    fun `V604 import_jobs progress 는 integer NOT NULL`() {
        assertThat(columnDataType("import_jobs", "progress")).isEqualTo("integer")
        assertThat(columnIsNullable("import_jobs", "progress")).isEqualTo("NO")
    }

    // ── timestamptz 강제 검증 (DATA.md §4 — TIMESTAMP without tz 금지) ──────────

    @Test
    fun `V604 import_jobs created_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("import_jobs", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("import_jobs", "created_at")).isEqualTo("NO")
    }

    @Test
    fun `V604 import_jobs expires_at 은 timestamptz NULL 허용`() {
        assertThat(columnDataType("import_jobs", "expires_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("import_jobs", "expires_at")).isEqualTo("YES")
    }

    @Test
    fun `V604 import_jobs started_at 은 timestamptz NULL 허용`() {
        assertThat(columnDataType("import_jobs", "started_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("import_jobs", "started_at")).isEqualTo("YES")
    }

    @Test
    fun `V604 import_jobs completed_at 은 timestamptz NULL 허용`() {
        assertThat(columnDataType("import_jobs", "completed_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("import_jobs", "completed_at")).isEqualTo("YES")
    }

    // ── CHECK 제약 검증 (status / format) ──────────────────────────────────────

    @Test
    fun `V604 chk_import_jobs_status CHECK 제약 존재`() {
        assertThat(constraintExists("chk_import_jobs_status")).isTrue()
    }

    @Test
    fun `V604 chk_import_jobs_format CHECK 제약 존재`() {
        assertThat(constraintExists("chk_import_jobs_format")).isTrue()
    }

    @Test
    fun `V604 허용 외 status 값 INSERT 는 CHECK 위반`() {
        // status IN ('PENDING','RUNNING','COMPLETED','FAILED') 외 값은 거부되어야 한다.
        assertThatThrownBy { insertImportJob(format = "CSV", status = "BOGUS") }
            .hasMessageContaining("chk_import_jobs_status")
    }

    @Test
    fun `V604 허용 외 format 값 INSERT 는 CHECK 위반`() {
        // format IN ('CSV','JSON') 외 값은 거부되어야 한다.
        assertThatThrownBy { insertImportJob(format = "XLSX", status = "PENDING") }
            .hasMessageContaining("chk_import_jobs_format")
    }

    // ── 인덱스 검증 ────────────────────────────────────────────────────────────

    @Test
    fun `V604 idx_import_jobs_requester 인덱스 존재`() {
        assertThat(indexExists("idx_import_jobs_requester")).isTrue()
    }

    @Test
    fun `V604 idx_import_jobs_expires 인덱스 존재`() {
        assertThat(indexExists("idx_import_jobs_expires")).isTrue()
    }

    // ── pgmq 큐 존재 검증 ──────────────────────────────────────────────────────

    @Test
    fun `V604 q_import_jobs 큐 존재`() {
        assertThat(pgmqQueueExists("q_import_jobs")).isTrue()
    }
}
