// issue-tracking V007 마이그레이션 검증 — issues.assignee_id UUID NULL 컬럼

package com.bts.issue.db

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
 * Flyway V001~V007 마이그레이션 적용 후 V007 변경사항을 검증한다.
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL 에서 직접 실행한다.
 *
 * 검증 범위.
 * (a) issues.assignee_id 컬럼 존재 — data_type=uuid
 * (b) issues.assignee_id 컬럼은 NULL 허용 (is_nullable=YES) — null=미할당
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * IssueTypeHierarchyAndFkMigrationIntegrationTest 와 동일 결정.
 */
@Testcontainers
class V007MigrationIntegrationTest {
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

    // ── (a) assignee_id 컬럼 존재 + 타입 검증 ────────────────────────────────

    @Test
    fun `V007 issues 에 assignee_id 컬럼이 존재한다`() {
        val (dataType, _) = columnInfo("issues", "assignee_id")
        assertThat(dataType)
            .describedAs("issues.assignee_id 컬럼이 존재해야 하며 data_type=uuid 이어야 한다")
            .isEqualTo("uuid")
    }

    // ── (b) assignee_id 는 NULL 허용 (미할당 기본) ───────────────────────────

    @Test
    fun `V007 issues assignee_id 컬럼은 NULL 허용이다`() {
        val (_, isNullable) = columnInfo("issues", "assignee_id")
        assertThat(isNullable)
            .describedAs("assignee_id 는 NULL 허용(미할당)이어야 한다 — is_nullable=YES")
            .isEqualTo("YES")
    }
}
