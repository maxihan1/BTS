// issue-tracking V011 마이그레이션 검증 — resolutions 테이블 + 표준 5종 seed + issues.resolution_id 컬럼 존재 확인

package com.bts.issue.resolution.repository

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
 * Flyway V001~V011 마이그레이션 적용 후 resolutions 테이블 + issues.resolution_id 컬럼을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 검증 범위 (FR-IS-07 Task B1).
 * - resolutions 테이블 존재
 * - 표준 5종 seed row 존재 (fixed/wontfix/duplicate/cannotreproduce/done) + is_standard=true + display_order 1~5
 * - 표준 seed UUID 가 Zod v4 형식 고정값 (프론트 픽스처/E2E 재사용)
 * - issues.resolution_id 컬럼 존재 + UUID 타입 + NULL 허용 (BC 격리로 FK 미적용)
 * - created_at / updated_at / deleted_at 이 TIMESTAMPTZ (DATA.md §4)
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정 (VersionsMigrationTest 선례).
 *
 * 참조. FR-IS-07 plan Task B1 / DATA.md §3 소프트 삭제 / §4 TIMESTAMPTZ.
 */
@Testcontainers
class ResolutionMigrationTest {
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

        // 표준 5종 resolution key → 고정 UUID (Zod v4 형식: 3그룹 4로 시작, 4그룹 8~b로 시작).
        // 프론트 픽스처/E2E 가 재사용하는 결정적 식별자.
        private val STANDARD_RESOLUTIONS: List<Triple<String, String, Int>> =
            listOf(
                Triple("fixed", "Fixed", 1),
                Triple("wontfix", "Won't Fix", 2),
                Triple("duplicate", "Duplicate", 3),
                Triple("cannotreproduce", "Cannot Reproduce", 4),
                Triple("done", "Done", 5),
            )
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

    private fun columnExists(
        tableName: String,
        columnName: String,
    ): Boolean = columnDataType(tableName, columnName) != null

    // Connection → prepareStatement → executeQuery 3중 use 블록 중첩. SQL 헬퍼의 관용적 패턴이므로 Suppress.
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
    ): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> rs.next() && rs.getString(1) == "YES" }
            }
        }

    // resolutions 단건 조회 — (id, name, display_order, is_standard) 반환. 없으면 null.
    @Suppress("NestedBlockDepth")
    private fun selectResolutionByKey(key: String): ResolutionRow? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT id, name, display_order, is_standard FROM resolutions WHERE key = ?",
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        ResolutionRow(
                            id = rs.getString("id"),
                            name = rs.getString("name"),
                            displayOrder = rs.getInt("display_order"),
                            isStandard = rs.getBoolean("is_standard"),
                        )
                    } else {
                        null
                    }
                }
            }
        }

    private data class ResolutionRow(
        val id: String,
        val name: String,
        val displayOrder: Int,
        val isStandard: Boolean,
    )

    // ── 테이블/컬럼 존재 검증 ──────────────────────────────────────────────────

    @Test
    fun `V011 resolutions 테이블 존재`() {
        assertThat(tableExists("resolutions")).isTrue()
    }

    @Test
    fun `V011 issues_resolution_id 컬럼 존재 + UUID + NULL 허용`() {
        assertThat(columnExists("issues", "resolution_id")).isTrue()
        assertThat(columnDataType("issues", "resolution_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("issues", "resolution_id")).isTrue()
    }

    // ── TIMESTAMPTZ 타입 검증 (DATA.md §4) ────────────────────────────────────

    @Test
    fun `V011 resolutions created_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("resolutions", "created_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V011 resolutions updated_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("resolutions", "updated_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V011 resolutions deleted_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("resolutions", "deleted_at"))
            .isEqualTo("timestamp with time zone")
    }

    // ── 표준 5종 seed 검증 ────────────────────────────────────────────────────

    @Test
    fun `V011 표준 5종 resolution seed 존재 + is_standard + display_order`() {
        STANDARD_RESOLUTIONS.forEach { (key, expectedName, expectedOrder) ->
            val row = selectResolutionByKey(key)
            assertThat(row).withFailMessage("표준 resolution '%s' seed 누락", key).isNotNull
            requireNotNull(row)
            assertThat(row.name).isEqualTo(expectedName)
            assertThat(row.displayOrder).isEqualTo(expectedOrder)
            assertThat(row.isStandard).isTrue()
        }
    }

    @Test
    fun `V011 표준 seed UUID 는 Zod v4 형식 고정값`() {
        // Zod v4 z.string().uuid(): 3번째 그룹 4로 시작, 4번째 그룹 8~b 로 시작 (메모리 zod-v4-uuid-fixture-strictness).
        val zodV4Uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        STANDARD_RESOLUTIONS.forEach { (key, _, _) ->
            val row = selectResolutionByKey(key)
            requireNotNull(row) { "표준 resolution '$key' seed 누락" }
            assertThat(row.id)
                .withFailMessage("resolution '%s' UUID '%s' 가 Zod v4 형식 아님", key, row.id)
                .matches { zodV4Uuid.matches(it) }
        }
    }
}
