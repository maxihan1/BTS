// issue-tracking V038 마이그레이션 검증 — bulk_operations.operation_type CHECK 가 STATUS_MIGRATION 을 허용하는지 확인

package com.bts.issue.bulk.db

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Flyway V001~V038 전체 체인 적용 후 V038 변경사항을 검증한다.
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL 에서 직접 실행한다.
 *
 * 검증 범위.
 * (a) operation_type='STATUS_MIGRATION' INSERT 성공 — V038 이 CHECK 를 넓혔다
 * (b) operation_type='NONSENSE' INSERT 거부 — CHECK 가 살아 있다는 비-공허 짝.
 *     위반 메시지의 제약 이름이 V008 의 chk_bulk_operations_operation_type 그대로인지도 함께 본다.
 *     이름을 바꾸면 다음 사람이 「어느 쪽이 진짜인가」를 못 푼다
 * (c) 기존 BULK_EDIT · BULK_TRANSITION INSERT 가 여전히 성공 — 회귀 방어
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * V008MigrationIntegrationTest 와 동일 결정.
 *
 * 공용 dev DB 의 선재 행이 가짜 그린을 만들지 않도록 픽스처가 자기 행을 직접 INSERT 하고
 * 돌려받은 id 로만 판정한다 (DATA.md §4 마이그레이션 테스트 규칙).
 */
@Testcontainers
class V038MigrationIntegrationTest {
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

    // operation_type 만 바꿔 최소 유효 row 를 넣고 생성된 id 를 돌려준다. CHECK 위반이면 SQLException.
    // JDBC use{} 표준 중첩(conn→stmt→rs) — 테스트 헬퍼라 분해 이득 없음
    @Suppress("NestedBlockDepth")
    private fun insertBulkOperation(operationType: String): String =
        conn().use { c ->
            c.prepareStatement(
                """
                INSERT INTO bulk_operations
                    (operation_type, status, actor_id, payload, total_count, created_at)
                VALUES
                    (?, 'PENDING', gen_random_uuid(), '{}', 0, now())
                RETURNING id::text
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, operationType)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    // 저장된 행을 id 로 되읽는다. 행이 없으면 null.
    @Suppress("NestedBlockDepth")
    private fun operationTypeOf(id: String): String? =
        conn().use { c ->
            c.prepareStatement(
                "SELECT operation_type FROM bulk_operations WHERE id = ?::uuid",
            ).use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }

    // ── (a) STATUS_MIGRATION 이 허용된다 ─────────────────────────────────────

    @Test
    fun `V038 bulk_operations 에 STATUS_MIGRATION 을 삽입하면 성공한다`() {
        val id = insertBulkOperation("STATUS_MIGRATION")

        assertThat(operationTypeOf(id))
            .describedAs("V038 이 CHECK 를 넓혔으므로 STATUS_MIGRATION 행이 저장돼 있어야 한다")
            .isEqualTo("STATUS_MIGRATION")
    }

    // ── (b) 정의되지 않은 값은 여전히 거부된다 (비-공허 짝) ────────────────────

    @Test
    fun `V038 이후에도 정의되지 않은 operation_type 은 CHECK 위반으로 거부된다`() {
        assertThrows<SQLException> {
            insertBulkOperation("NONSENSE")
        }.also { ex ->
            assertThat(ex.message)
                .describedAs(
                    "CHECK 가 살아 있어야 하고, 제약 이름은 V008 의 chk_bulk_operations_operation_type 그대로여야 한다",
                )
                .containsIgnoringCase("chk_bulk_operations_operation_type")
        }
    }

    // ── (c) 기존 2값 회귀 ────────────────────────────────────────────────────

    @Test
    fun `V038 이후에도 기존 BULK_EDIT 와 BULK_TRANSITION 삽입이 성공한다`() {
        val editId = insertBulkOperation("BULK_EDIT")
        val transitionId = insertBulkOperation("BULK_TRANSITION")

        assertThat(operationTypeOf(editId))
            .describedAs("V038 이 기존 BULK_EDIT 를 깨뜨리지 않아야 한다")
            .isEqualTo("BULK_EDIT")
        assertThat(operationTypeOf(transitionId))
            .describedAs("V038 이 기존 BULK_TRANSITION 을 깨뜨리지 않아야 한다")
            .isEqualTo("BULK_TRANSITION")
    }
}
