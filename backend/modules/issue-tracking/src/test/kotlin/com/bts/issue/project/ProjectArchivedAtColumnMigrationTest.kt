// projects.archived_at 컬럼 추가 마이그레이션(V037) 적용 결과 검증

package com.bts.issue.project

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
 * Flyway V001~V037 마이그레이션 적용 후 projects.archived_at 컬럼을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 검증 범위 (FR-PJ-04 PR-4 Task 1).
 * - projects.archived_at 컬럼 존재
 * - 타입이 timestamp with time zone (TIMESTAMPTZ 강제, DATA.md §4)
 * - nullable (IS_NULLABLE = 'YES') — NOT NULL 아님, backfill 불필요
 * - archived_at ⊥ deleted_at — deleted_at 컬럼은 별도로 여전히 존재(직교 컬럼, 읽기 술어 무변경)
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 *
 * 참조. FR-PJ-04 PR-4 plan Task 1 / DATA.md §4 TIMESTAMPTZ / §3 소프트 삭제.
 */
@Testcontainers
class ProjectArchivedAtColumnMigrationTest {
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

    // Connection → prepareStatement → executeQuery 3중 use 블록 중첩. SQL 헬퍼의 관용적 패턴이므로 Suppress.
    @Suppress("NestedBlockDepth")
    private fun columnInfo(
        tableName: String,
        columnName: String,
    ): Pair<String?, String?>? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT data_type, is_nullable FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) Pair(rs.getString("data_type"), rs.getString("is_nullable")) else null
                }
            }
        }

    // ── 컬럼 존재·타입·nullable·직교성 검증 ─────────────────────────────────────

    @Test
    fun `V037 projects archived_at 컬럼이 존재한다`() {
        val info = columnInfo("projects", "archived_at")
        assertThat(info).isNotNull()
    }

    @Test
    fun `V037 projects archived_at 타입은 timestamptz 다`() {
        val (dataType, _) = columnInfo("projects", "archived_at")!!
        assertThat(dataType).isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V037 projects archived_at 는 nullable 이다`() {
        val (_, isNullable) = columnInfo("projects", "archived_at")!!
        assertThat(isNullable).isEqualTo("YES")
    }

    @Test
    fun `V037 projects deleted_at 컬럼은 여전히 존재한다 (archived_at 과 직교)`() {
        val info = columnInfo("projects", "deleted_at")
        assertThat(info).isNotNull()
    }
}
