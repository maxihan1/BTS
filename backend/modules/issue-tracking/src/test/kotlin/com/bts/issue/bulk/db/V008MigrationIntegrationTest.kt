// issue-tracking V008 마이그레이션 검증 — bulk_operations/items 테이블 및 pgmq 큐 존재 확인

package com.bts.issue.bulk.db

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
 * Flyway V001~V008 마이그레이션 적용 후 V008 변경사항을 검증한다.
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL 에서 직접 실행한다.
 *
 * 검증 범위.
 * (a) bulk_operations 테이블 존재 — actor_id UUID NOT NULL 컬럼 포함
 * (b) bulk_operation_items 테이블 존재
 * (c) bulk_operation_items UNIQUE(bulk_operation_id, issue_key) 제약 존재
 * (d) q_bulk_operations pgmq 큐 존재
 * (e) q_bulk_operation_events pgmq 큐 존재
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * V007MigrationIntegrationTest 와 동일 결정.
 */
@Testcontainers
class V008MigrationIntegrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V002 pgmq 확장 요구로 인해 tembo 이미지 사용.
        // asCompatibleSubstituteFor("postgres"): Testcontainers 이미지 호환성 검증 우회.
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
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .load()
                .migrate()
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun conn() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun tableExists(tableName: String): Boolean =
        conn().use { c ->
            c.prepareStatement(
                "SELECT 1 FROM information_schema.tables" +
                    " WHERE table_schema = 'public' AND table_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }

    // JDBC use{} 표준 중첩(conn→stmt→rs) — 테스트 헬퍼라 분해 이득 없음
    @Suppress("NestedBlockDepth")
    private fun columnInfo(
        tableName: String,
        columnName: String,
    ): Pair<String?, String?> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT data_type, is_nullable FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) to rs.getString(2) else null to null
                }
            }
        }

    private fun uniqueConstraintExists(
        tableName: String,
        columnNames: List<String>,
    ): Boolean =
        conn().use { c ->
            // pg_constraint + pg_attribute 조인으로 정확한 컬럼 조합 검증.
            // attname 은 name 타입 — text 로 캐스트하여 비교.
            c.prepareStatement(
                """
                SELECT 1
                  FROM pg_constraint con
                  JOIN pg_class rel ON rel.oid = con.conrelid
                  JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                 WHERE nsp.nspname = 'public'
                   AND rel.relname = ?
                   AND con.contype = 'u'
                   AND ARRAY(
                         SELECT attname::text
                           FROM pg_attribute
                          WHERE attrelid = con.conrelid
                            AND attnum = ANY(con.conkey)
                          ORDER BY attname
                       ) = ?::text[]
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setArray(2, c.createArrayOf("text", columnNames.sorted().toTypedArray()))
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }

    private fun pgmqQueueExists(queueName: String): Boolean =
        conn().use { c ->
            c.prepareStatement(
                "SELECT 1 FROM pgmq.meta WHERE queue_name = ?",
            ).use { stmt ->
                stmt.setString(1, queueName)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }

    // ── (a) bulk_operations 테이블 존재 ──────────────────────────────────────

    @Test
    fun `V008 bulk_operations 테이블이 존재한다`() {
        assertThat(tableExists("bulk_operations"))
            .describedAs("bulk_operations 테이블이 존재해야 한다")
            .isTrue()
    }

    @Test
    fun `V008 bulk_operations actor_id 컬럼이 uuid NOT NULL 이다`() {
        val (dataType, isNullable) = columnInfo("bulk_operations", "actor_id")
        assertThat(dataType)
            .describedAs("bulk_operations.actor_id 는 data_type=uuid 이어야 한다")
            .isEqualTo("uuid")
        assertThat(isNullable)
            .describedAs("bulk_operations.actor_id 는 NOT NULL 이어야 한다 — is_nullable=NO")
            .isEqualTo("NO")
    }

    // ── (b) bulk_operation_items 테이블 존재 ─────────────────────────────────

    @Test
    fun `V008 bulk_operation_items 테이블이 존재한다`() {
        assertThat(tableExists("bulk_operation_items"))
            .describedAs("bulk_operation_items 테이블이 존재해야 한다")
            .isTrue()
    }

    // ── (c) UNIQUE(bulk_operation_id, issue_key) 제약 ─────────────────────────

    @Test
    fun `V008 bulk_operation_items 에 UNIQUE bulk_operation_id + issue_key 제약이 존재한다`() {
        assertThat(uniqueConstraintExists("bulk_operation_items", listOf("bulk_operation_id", "issue_key")))
            .describedAs("bulk_operation_items(bulk_operation_id, issue_key) UNIQUE 제약이 존재해야 한다")
            .isTrue()
    }

    // ── (d) q_bulk_operations pgmq 큐 존재 ───────────────────────────────────

    @Test
    fun `V008 q_bulk_operations pgmq 큐가 존재한다`() {
        assertThat(pgmqQueueExists("q_bulk_operations"))
            .describedAs("q_bulk_operations pgmq 큐가 존재해야 한다")
            .isTrue()
    }

    // ── (e) q_bulk_operation_events pgmq 큐 존재 ─────────────────────────────

    @Test
    fun `V008 q_bulk_operation_events pgmq 큐가 존재한다`() {
        assertThat(pgmqQueueExists("q_bulk_operation_events"))
            .describedAs("q_bulk_operation_events pgmq 큐가 존재해야 한다")
            .isTrue()
    }

    // ── (f) CHECK 제약 — 잘못된 값 거부 ──────────────────────────────────────

    // 공통 INSERT 헬퍼: bulk_operations 에 최소 유효 row 하나를 삽입하고 UUID 반환.
    private fun insertValidBulkOperation(): String =
        conn().use { c ->
            c.prepareStatement(
                """
                INSERT INTO bulk_operations
                    (operation_type, status, actor_id, payload, total_count, created_at)
                VALUES
                    ('BULK_EDIT', 'PENDING', gen_random_uuid(), '{}', 1, now())
                RETURNING id::text
                """.trimIndent(),
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    @Test
    fun `V008 bulk_operations operation_type 에 허용되지 않는 값을 삽입하면 CHECK 위반 예외가 발생한다`() {
        org.junit.jupiter.api.assertThrows<java.sql.SQLException> {
            conn().use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO bulk_operations
                        (operation_type, status, actor_id, payload, total_count, created_at)
                    VALUES
                        ('INVALID_TYPE', 'PENDING', gen_random_uuid(), '{}', 1, now())
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            }
        }.also { ex ->
            assertThat(ex.message).containsIgnoringCase("check")
        }
    }

    @Test
    fun `V008 bulk_operations status 에 허용되지 않는 값을 삽입하면 CHECK 위반 예외가 발생한다`() {
        org.junit.jupiter.api.assertThrows<java.sql.SQLException> {
            conn().use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO bulk_operations
                        (operation_type, status, actor_id, payload, total_count, created_at)
                    VALUES
                        ('BULK_EDIT', 'garbage', gen_random_uuid(), '{}', 1, now())
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            }
        }.also { ex ->
            assertThat(ex.message).containsIgnoringCase("check")
        }
    }

    @Test
    fun `V008 bulk_operations 카운터에 음수를 삽입하면 CHECK 위반 예외가 발생한다`() {
        org.junit.jupiter.api.assertThrows<java.sql.SQLException> {
            conn().use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO bulk_operations
                        (operation_type, status, actor_id, payload, total_count, created_at)
                    VALUES
                        ('BULK_EDIT', 'PENDING', gen_random_uuid(), '{}', -1, now())
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            }
        }.also { ex ->
            assertThat(ex.message).containsIgnoringCase("check")
        }
    }

    @Test
    fun `V008 bulk_operation_items status 에 허용되지 않는 값을 삽입하면 CHECK 위반 예외가 발생한다`() {
        val bulkOpId = insertValidBulkOperation()
        org.junit.jupiter.api.assertThrows<java.sql.SQLException> {
            conn().use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO bulk_operation_items
                        (bulk_operation_id, issue_key, status)
                    VALUES
                        ('$bulkOpId', 'BTS-1', 'garbage')
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            }
        }.also { ex ->
            assertThat(ex.message).containsIgnoringCase("check")
        }
    }

    @Test
    fun `V008 bulk_operation_items issue_key 가 정규식 패턴을 위반하면 CHECK 위반 예외가 발생한다`() {
        val bulkOpId = insertValidBulkOperation()
        org.junit.jupiter.api.assertThrows<java.sql.SQLException> {
            conn().use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO bulk_operation_items
                        (bulk_operation_id, issue_key, status)
                    VALUES
                        ('$bulkOpId', 'invalid-key', 'PENDING')
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            }
        }.also { ex ->
            assertThat(ex.message).containsIgnoringCase("check")
        }
    }

    @Test
    fun `V008 bulk_operation_items status 가 FAILED 이고 failure_reason 이 NULL 이면 CHECK 위반 예외가 발생한다`() {
        val bulkOpId = insertValidBulkOperation()
        org.junit.jupiter.api.assertThrows<java.sql.SQLException> {
            conn().use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO bulk_operation_items
                        (bulk_operation_id, issue_key, status, failure_reason)
                    VALUES
                        ('$bulkOpId', 'BTS-2', 'FAILED', NULL)
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            }
        }.also { ex ->
            assertThat(ex.message).containsIgnoringCase("check")
        }
    }
}
