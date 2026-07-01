// V603 마이그레이션 검증 — outbound_webhooks(구독) + webhook_deliveries(발송 이력) 테이블 스키마 확인 (FR-API-03 PR2)

package com.bts.search.webhook

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * Flyway V600~V603 마이그레이션 적용 후 FR-API-03 PR2 의 두 테이블을 검증한다.
 * - outbound_webhooks — 아웃바운드 webhook 구독(소프트삭제·OCC version·event_filter TEXT[] GIN).
 * - webhook_deliveries — 발송 이력(append-only, audit_logs 동류 — deleted_at 없음. 발송 로직은 PR3).
 *
 * **pgmq 이미지 강제** — 마이그레이션 체인 중 V602 가 `CREATE EXTENSION pgmq` + `pgmq.create()` 를
 * 사용하므로 pgmq 확장 바이너리가 없는 `postgres:16-alpine` 에서는 체인이 실패한다. 따라서 pgmq 가
 * 사전 설치된 `quay.io/tembo/pg16-pgmq:latest` 를 사용한다 (ADR 2026-05-22-pgmq-postgres-image,
 * ExportJobsSchemaMigrationTest 동일 패턴). `asCompatibleSubstituteFor("postgres")` 로
 * Testcontainers 이미지 호환성 검증을 우회한다.
 *
 * **JVM 단위 singleton container 패턴** — companion object `.apply { start() }` 로 JVM 라이프사이클에 바인딩.
 * Ryuk 의 JVM 종료 시 자동 정리에 위임해 동시 suite flaky 를 회피한다
 * (메모리 concurrent-testcontainers-suite-flaky / ExportJobsSchemaMigrationTest 동일 패턴).
 *
 * 검증 범위 (plan Task 2 / spec §데이터 모델 V603 / DATA.md §4.1 TIMESTAMPTZ 강제·FK 인덱스).
 * - outbound_webhooks 테이블 + 12개 컬럼, event_filter=TEXT[], secret_encrypted=text NULL,
 *   deleted_at=timestamptz NULL, version=bigint NOT NULL, created_at/updated_at=timestamptz NOT NULL.
 * - webhook_deliveries 테이블 + 9개 컬럼(deleted_at 없음=append-only), status=varchar NOT NULL,
 *   response_code=integer NULL, created_at=timestamptz NOT NULL.
 * - event_filter GIN 인덱스 + enabled 부분 인덱스 존재.
 * - webhook_deliveries → outbound_webhooks FK + webhook_id FK 인덱스 존재.
 *
 * 정보 스키마(information_schema / pg_indexes / pg_constraint) 조회로 단언한다.
 * SQL 문자열 결합 없이 prepared statement(? 바인딩)를 사용한다 (NEVER-3).
 */
class OutboundWebhookSchemaMigrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — 마이그레이션 체인의 V602 pgmq 요구로 tembo 이미지 사용.
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_outbound_webhooks_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        // outbound_webhooks 가 보유해야 하는 12개 컬럼 (spec §데이터 모델 V603 DDL 정합).
        private val OUTBOUND_WEBHOOKS_COLUMNS =
            listOf(
                "id",
                "name",
                "url",
                "secret_encrypted",
                "event_filter",
                "project_key",
                "enabled",
                "created_by",
                "created_at",
                "updated_at",
                "deleted_at",
                "version",
            )

        // webhook_deliveries 가 보유해야 하는 9개 컬럼 (append-only — deleted_at 없음).
        private val WEBHOOK_DELIVERIES_COLUMNS =
            listOf(
                "id",
                "webhook_id",
                "event_type",
                "status",
                "response_code",
                "attempt_count",
                "error_detail",
                "created_at",
                "delivered_at",
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

    // 배열 컬럼 판정용 — TEXT[] 는 data_type='ARRAY', udt_name='_text' 로 노출된다.
    @Suppress("NestedBlockDepth")
    private fun columnUdtName(
        tableName: String,
        columnName: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT udt_name FROM information_schema.columns" +
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

    // 인덱스 정의(indexdef)를 반환 — GIN 여부 판정용.
    @Suppress("NestedBlockDepth")
    private fun indexDef(indexName: String): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    // child → parent 외래키 개수 — FK 존재 검증용(제약 이름 자동생성이라 테이블 쌍으로 조회).
    @Suppress("NestedBlockDepth")
    private fun foreignKeyCount(
        childTable: String,
        parentTable: String,
    ): Int =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM pg_constraint c" +
                    " JOIN pg_class child ON c.conrelid = child.oid" +
                    " JOIN pg_class parent ON c.confrelid = parent.oid" +
                    " WHERE c.contype = 'f' AND child.relname = ? AND parent.relname = ?",
            ).use { stmt ->
                stmt.setString(1, childTable)
                stmt.setString(2, parentTable)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    // ── outbound_webhooks 테이블 / 컬럼 검증 ────────────────────────────────────

    @Test
    fun `V603 outbound_webhooks 테이블 존재`() {
        assertThat(tableExists("outbound_webhooks")).isTrue()
    }

    @Test
    fun `V603 outbound_webhooks 12개 컬럼 존재`() {
        assertThat(columnsOf("outbound_webhooks"))
            .containsExactlyInAnyOrderElementsOf(OUTBOUND_WEBHOOKS_COLUMNS)
    }

    @Test
    fun `V603 outbound_webhooks id 는 uuid PK NOT NULL`() {
        assertThat(columnDataType("outbound_webhooks", "id")).isEqualTo("uuid")
        assertThat(columnIsNullable("outbound_webhooks", "id")).isEqualTo("NO")
    }

    @Test
    fun `V603 outbound_webhooks event_filter 는 TEXT 배열 NOT NULL`() {
        // TEXT[] → data_type='ARRAY', udt_name='_text'. PR3 매칭(&& overlap) 대비 GIN 인덱스 대상.
        assertThat(columnDataType("outbound_webhooks", "event_filter")).isEqualTo("ARRAY")
        assertThat(columnUdtName("outbound_webhooks", "event_filter")).isEqualTo("_text")
        assertThat(columnIsNullable("outbound_webhooks", "event_filter")).isEqualTo("NO")
    }

    @Test
    fun `V603 outbound_webhooks secret_encrypted 는 text NULL 허용`() {
        // 원문 비저장 — AES-256-GCM 암호문만. secret 없는 구독은 NULL.
        assertThat(columnDataType("outbound_webhooks", "secret_encrypted")).isEqualTo("text")
        assertThat(columnIsNullable("outbound_webhooks", "secret_encrypted")).isEqualTo("YES")
    }

    @Test
    fun `V603 outbound_webhooks deleted_at 는 timestamptz NULL 허용 (소프트삭제)`() {
        assertThat(columnDataType("outbound_webhooks", "deleted_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("outbound_webhooks", "deleted_at")).isEqualTo("YES")
    }

    @Test
    fun `V603 outbound_webhooks version 는 bigint NOT NULL (OCC)`() {
        assertThat(columnDataType("outbound_webhooks", "version")).isEqualTo("bigint")
        assertThat(columnIsNullable("outbound_webhooks", "version")).isEqualTo("NO")
    }

    @Test
    fun `V603 outbound_webhooks created_by 는 uuid NOT NULL`() {
        assertThat(columnDataType("outbound_webhooks", "created_by")).isEqualTo("uuid")
        assertThat(columnIsNullable("outbound_webhooks", "created_by")).isEqualTo("NO")
    }

    @Test
    fun `V603 outbound_webhooks enabled 는 boolean NOT NULL`() {
        assertThat(columnDataType("outbound_webhooks", "enabled")).isEqualTo("boolean")
        assertThat(columnIsNullable("outbound_webhooks", "enabled")).isEqualTo("NO")
    }

    @Test
    fun `V603 outbound_webhooks created_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("outbound_webhooks", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("outbound_webhooks", "created_at")).isEqualTo("NO")
    }

    @Test
    fun `V603 outbound_webhooks updated_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("outbound_webhooks", "updated_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("outbound_webhooks", "updated_at")).isEqualTo("NO")
    }

    // ── outbound_webhooks 인덱스 검증 ──────────────────────────────────────────

    @Test
    fun `V603 event_filter GIN 인덱스 존재`() {
        val def = indexDef("idx_outbound_webhooks_event_filter_gin")
        assertThat(def).isNotNull()
        assertThat(def!!.lowercase()).contains("using gin")
    }

    @Test
    fun `V603 enabled 부분 인덱스 존재 (deleted_at IS NULL)`() {
        val def = indexDef("idx_outbound_webhooks_enabled_notdeleted")
        assertThat(def).isNotNull()
        assertThat(def!!.lowercase()).contains("deleted_at is null")
    }

    // ── webhook_deliveries 테이블 / 컬럼 검증 ───────────────────────────────────

    @Test
    fun `V603 webhook_deliveries 테이블 존재`() {
        assertThat(tableExists("webhook_deliveries")).isTrue()
    }

    @Test
    fun `V603 webhook_deliveries 9개 컬럼 존재 (deleted_at 없음 append-only)`() {
        assertThat(columnsOf("webhook_deliveries"))
            .containsExactlyInAnyOrderElementsOf(WEBHOOK_DELIVERIES_COLUMNS)
        // append-only 로그(audit_logs 동류) — 소프트삭제 컬럼 미보유를 명시적으로 단언.
        assertThat(columnsOf("webhook_deliveries")).doesNotContain("deleted_at")
    }

    @Test
    fun `V603 webhook_deliveries status 는 varchar NOT NULL`() {
        assertThat(columnDataType("webhook_deliveries", "status")).isEqualTo("character varying")
        assertThat(columnIsNullable("webhook_deliveries", "status")).isEqualTo("NO")
    }

    @Test
    fun `V603 webhook_deliveries response_code 는 integer NULL 허용`() {
        assertThat(columnDataType("webhook_deliveries", "response_code")).isEqualTo("integer")
        assertThat(columnIsNullable("webhook_deliveries", "response_code")).isEqualTo("YES")
    }

    @Test
    fun `V603 webhook_deliveries created_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("webhook_deliveries", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("webhook_deliveries", "created_at")).isEqualTo("NO")
    }

    @Test
    fun `V603 webhook_deliveries delivered_at 은 timestamptz NULL 허용`() {
        assertThat(columnDataType("webhook_deliveries", "delivered_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("webhook_deliveries", "delivered_at")).isEqualTo("YES")
    }

    // ── webhook_deliveries FK / 인덱스 검증 ────────────────────────────────────

    @Test
    fun `V603 webhook_deliveries webhook_id 는 outbound_webhooks FK`() {
        assertThat(foreignKeyCount("webhook_deliveries", "outbound_webhooks")).isEqualTo(1)
    }

    @Test
    fun `V603 webhook_id FK 인덱스 존재`() {
        // PostgreSQL 은 FK 인덱스를 자동 생성하지 않는다 (DATA.md §4.1#6 — 명시 인덱스 필수).
        assertThat(indexDef("idx_webhook_deliveries_webhook_id")).isNotNull()
    }
}
