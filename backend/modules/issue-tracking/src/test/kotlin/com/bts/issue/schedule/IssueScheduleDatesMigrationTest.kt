// issues.start_date/due_date/target_date 컬럼 추가 마이그레이션(V025) 적용 결과 검증

package com.bts.issue.schedule

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
 * Flyway V001~V025 마이그레이션 적용 후 issues 테이블의 일정 3컬럼을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다 (FR-PL-01).
 *
 * 검증 범위.
 * - issues.start_date / due_date / target_date 컬럼 존재
 * - 타입이 date (시각/타임존 없는 캘린더 날짜)
 * - nullable (IS_NULLABLE = 'YES')
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정 (ProjectLeadColumnMigrationTest 선례).
 *
 * 참조. FR-PL-01 plan Task 1 / SDD 05.1 일정 필드 / DATA.md §4.
 */
@Testcontainers
class IssueScheduleDatesMigrationTest {
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

    // ── start_date ────────────────────────────────────────────────────────────

    @Test
    fun `V025 issues start_date 컬럼이 존재한다`() {
        assertThat(columnInfo("issues", "start_date")).isNotNull()
    }

    @Test
    fun `V025 issues start_date 타입은 date 이고 nullable 이다`() {
        val (dataType, isNullable) = columnInfo("issues", "start_date")!!
        assertThat(dataType).isEqualTo("date")
        assertThat(isNullable).isEqualTo("YES")
    }

    // ── due_date ──────────────────────────────────────────────────────────────

    @Test
    fun `V025 issues due_date 컬럼이 존재한다`() {
        assertThat(columnInfo("issues", "due_date")).isNotNull()
    }

    @Test
    fun `V025 issues due_date 타입은 date 이고 nullable 이다`() {
        val (dataType, isNullable) = columnInfo("issues", "due_date")!!
        assertThat(dataType).isEqualTo("date")
        assertThat(isNullable).isEqualTo("YES")
    }

    // ── target_date ───────────────────────────────────────────────────────────

    @Test
    fun `V025 issues target_date 컬럼이 존재한다`() {
        assertThat(columnInfo("issues", "target_date")).isNotNull()
    }

    @Test
    fun `V025 issues target_date 타입은 date 이고 nullable 이다`() {
        val (dataType, isNullable) = columnInfo("issues", "target_date")!!
        assertThat(dataType).isEqualTo("date")
        assertThat(isNullable).isEqualTo("YES")
    }
}
