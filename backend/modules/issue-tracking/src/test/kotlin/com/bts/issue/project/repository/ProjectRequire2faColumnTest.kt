// issue-tracking V019 마이그레이션 검증 — projects.require_2fa BOOLEAN NOT NULL DEFAULT false 컬럼 존재 + 기본값 검증 (FR-MF-04)

package com.bts.issue.project.repository

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Flyway V001~V019 마이그레이션 적용 후 projects.require_2fa 컬럼을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 검증 범위 (FR-MF-04 Task 1).
 * - projects.require_2fa 컬럼 존재 (information_schema.columns)
 * - 타입 boolean / NOT NULL / DEFAULT false (스키마 메타데이터)
 * - require_2fa 를 명시하지 않고 INSERT 한 프로젝트 행의 require_2fa 가 기본값 false
 *
 * '민감 프로젝트'(MFA 강제 대상) 표시 컬럼. SensitiveProjectResolver(Task 2) 가 이 컬럼을 읽는다.
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * IssueComponentsSchemaMigrationTest 와 동일 결정.
 *
 * 참조. FR-MF-04 plan Task 1 / DATA.md §4-2 NOT NULL DEFAULT.
 */
@Testcontainers
class ProjectRequire2faColumnTest {
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

    // projects.require_2fa 컬럼의 메타데이터(타입/nullable/기본값)를 information_schema 에서 조회.
    private fun columnMetadata(columnName: String): ColumnMeta? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT data_type, is_nullable, column_default" +
                    " FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = 'projects' AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, columnName)
                stmt.executeQuery().use { rs -> rs.toColumnMeta() }
            }
        }

    // ResultSet 의 첫 행을 ColumnMeta 로 매핑 — 행이 없으면 null.
    private fun ResultSet.toColumnMeta(): ColumnMeta? =
        if (!next()) {
            null
        } else {
            ColumnMeta(
                dataType = getString("data_type"),
                isNullable = getString("is_nullable"),
                columnDefault = getString("column_default"),
            )
        }

    private data class ColumnMeta(
        val dataType: String,
        val isNullable: String,
        val columnDefault: String?,
    )

    // ── 컬럼 존재/타입/기본값 검증 ─────────────────────────────────────────────

    @Test
    fun `V019 projects require_2fa 컬럼 존재`() {
        assertThat(columnMetadata("require_2fa")).isNotNull()
    }

    @Test
    fun `V019 projects require_2fa 는 boolean NOT NULL DEFAULT false`() {
        val meta = columnMetadata("require_2fa")
        assertThat(meta).isNotNull()
        requireNotNull(meta)
        assertThat(meta.dataType).isEqualTo("boolean")
        assertThat(meta.isNullable).isEqualTo("NO")
        // PostgreSQL boolean DEFAULT false 는 column_default 에 'false' 로 저장된다.
        assertThat(meta.columnDefault).isEqualTo("false")
    }

    @Test
    fun `require_2fa 미지정 INSERT 시 기본값 false`() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            // require_2fa 컬럼을 명시하지 않고 프로젝트 1건 삽입 → DEFAULT 적용 여부 확인.
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES ('MFADEF', 'MFA Default Project')" +
                    " ON CONFLICT (key) DO NOTHING",
            ).use { it.executeUpdate() }

            conn.prepareStatement("SELECT require_2fa FROM projects WHERE key = 'MFADEF'").use { stmt ->
                stmt.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getBoolean("require_2fa")).isFalse()
                }
            }
        }
    }
}
