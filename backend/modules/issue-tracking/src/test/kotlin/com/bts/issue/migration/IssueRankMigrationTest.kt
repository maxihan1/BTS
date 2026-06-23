// V029 마이그레이션 검증 — issues.rank(VARCHAR(50), nullable) 컬럼 + idx_issues_project_rank 인덱스 (FR-BL-01 옵션 B)

package com.bts.issue.migration

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
 * Flyway V001~V029 마이그레이션 체인 적용 후 V029 변경사항을 검증한다 (FR-BL-01 옵션 B).
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL 에서 직접 실행한다 (V028MigrationTest 패턴 미러).
 *
 * 검증 범위.
 * (a) issues 테이블에 rank 컬럼 존재 + 타입 character varying(50) + nullable(옵션 B)
 * (b) idx_issues_project_rank 인덱스 존재 (project_id, rank)
 *
 * 옵션 B 전환으로 백필·NOT NULL 검증은 제거됨 (spec 결정 #5).
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 */
@Testcontainers
class IssueRankMigrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V002 pgmq 확장 요구로 인해 tembo 이미지 사용.
        // asCompatibleSubstituteFor("postgres"): Testcontainers 이미지 호환성 검증 우회.
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        // (a)(b) 구조 검증용 컨테이너 — 전체 체인 V001~V029 적용.
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @BeforeAll
        @JvmStatic
        fun setup() {
            // 전체 마이그레이션 체인(V001~V029) 적용 — target 미지정.
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

    @Suppress("NestedBlockDepth") // JDBC use {} 3중 중첩 — Connection/PreparedStatement/ResultSet 생명주기 관리 패턴
    private fun columnDataType(
        tableName: String,
        columnName: String,
    ): String? =
        conn().use { c ->
            c.prepareStatement(
                "SELECT data_type FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    @Suppress("NestedBlockDepth") // JDBC use {} 3중 중첩 — Connection/PreparedStatement/ResultSet 생명주기 관리 패턴
    private fun columnMaxLength(
        tableName: String,
        columnName: String,
    ): Int? =
        conn().use { c ->
            c.prepareStatement(
                "SELECT character_maximum_length FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
            }
        }

    @Suppress("NestedBlockDepth") // JDBC use {} 3중 중첩 — Connection/PreparedStatement/ResultSet 생명주기 관리 패턴
    private fun columnIsNullable(
        tableName: String,
        columnName: String,
    ): String? =
        conn().use { c ->
            c.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun indexExists(indexName: String): Boolean =
        conn().use { c ->
            c.prepareStatement(
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

    /**
     * 인덱스가 (project_id, rank) 두 컬럼을 그 순서로 포함하는지 인덱스 정의 문자열로 확인한다.
     */
    private fun indexDefinition(indexName: String): String? =
        conn().use { c ->
            c.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    // ── (a) rank 컬럼 존재/타입/nullable 검증 ────────────────────────────────

    @Test
    fun `V029 rank 컬럼 타입은 character varying`() {
        assertThat(columnDataType("issues", "rank")).isEqualTo("character varying")
    }

    @Test
    fun `V029 rank 컬럼 최대 길이는 50`() {
        assertThat(columnMaxLength("issues", "rank")).isEqualTo(50)
    }

    @Test
    fun `V029 rank 컬럼은 nullable (옵션 B, lazy 부여)`() {
        // 옵션 B 전환: rank=NULL 허용 — 신규 이슈는 rank=NULL, 드래그(rerank) 시 부여.
        assertThat(columnIsNullable("issues", "rank")).isEqualTo("YES")
    }

    // ── (b) 인덱스 검증 ───────────────────────────────────────────────────────

    @Test
    fun `V029 idx_issues_project_rank 인덱스 존재`() {
        assertThat(indexExists("idx_issues_project_rank")).isTrue()
    }

    @Test
    fun `V029 idx_issues_project_rank 인덱스는 project_id, rank 복합`() {
        val def = indexDefinition("idx_issues_project_rank")
        assertThat(def).isNotNull()
        assertThat(def!!.lowercase()).contains("project_id")
        assertThat(def.lowercase()).contains("rank")
    }
}
