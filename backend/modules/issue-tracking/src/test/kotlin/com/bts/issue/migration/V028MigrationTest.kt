// V028 마이그레이션 검증 — issues.epic_id(자기참조 FK, nullable) 컬럼 + idx_issues_epic_id 인덱스 추가

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
 * Flyway V001~V028 전체 마이그레이션 체인 적용 후 V028 변경사항을 검증한다.
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL 에서 직접 실행한다 (V006MigrationTest 패턴 미러).
 *
 * 검증 범위 (FR-EP-01).
 * (a) issues 테이블에 epic_id 컬럼 존재 + 타입 uuid + nullable
 * (b) epic_id 가 issues(id) 를 참조하는 자기참조 FK 보유
 * (c) idx_issues_epic_id 인덱스 존재
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 */
@Testcontainers
class V028MigrationTest {
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
        fun setup() {
            // 전체 마이그레이션 체인(V001~V028) 적용 — target 미지정.
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

    private fun columnExists(
        tableName: String,
        columnName: String,
    ): Boolean =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

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
     * issues.epic_id 가 issues(id) 를 참조하는 자기참조 FK 가 존재하는지 확인한다.
     * information_schema 의 FK 메타뷰를 조인해 (참조 컬럼=epic_id, 참조 대상 테이블=issues) 조합을 센다.
     */
    @Suppress("NestedBlockDepth") // JDBC use {} 3중 중첩 — Connection/PreparedStatement/ResultSet 생명주기 관리 패턴
    private fun selfFkExists(): Boolean =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*)" +
                    " FROM information_schema.table_constraints tc" +
                    " JOIN information_schema.key_column_usage kcu" +
                    "   ON tc.constraint_name = kcu.constraint_name" +
                    "  AND tc.table_schema = kcu.table_schema" +
                    " JOIN information_schema.constraint_column_usage ccu" +
                    "   ON tc.constraint_name = ccu.constraint_name" +
                    "  AND tc.table_schema = ccu.table_schema" +
                    " WHERE tc.constraint_type = 'FOREIGN KEY'" +
                    "   AND tc.table_schema = 'public'" +
                    "   AND tc.table_name = 'issues'" +
                    "   AND kcu.column_name = 'epic_id'" +
                    "   AND ccu.table_name = 'issues'" +
                    "   AND ccu.column_name = 'id'",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // ── (a) epic_id 컬럼 존재/타입/nullable 검증 ──────────────────────────────

    @Test
    fun `V028 issues 에 epic_id 컬럼 존재`() {
        assertThat(columnExists("issues", "epic_id")).isTrue()
    }

    @Test
    fun `V028 epic_id 컬럼 타입은 uuid`() {
        assertThat(columnDataType("issues", "epic_id")).isEqualTo("uuid")
    }

    @Test
    fun `V028 epic_id 컬럼은 nullable`() {
        assertThat(columnIsNullable("issues", "epic_id")).isEqualTo("YES")
    }

    // ── (b) 자기참조 FK 검증 ──────────────────────────────────────────────────

    @Test
    fun `V028 epic_id 는 issues(id) 자기참조 FK 보유`() {
        assertThat(selfFkExists()).isTrue()
    }

    // ── (c) FK 인덱스 존재 검증 ───────────────────────────────────────────────

    @Test
    fun `V028 idx_issues_epic_id 인덱스 존재`() {
        assertThat(indexExists("idx_issues_epic_id")).isTrue()
    }
}
