// V029 마이그레이션 검증 — issues.rank(VARCHAR(50), NOT NULL) 컬럼 + 프로젝트별 created_at 순 백필 + idx_issues_project_rank 인덱스

package com.bts.issue.migration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * Flyway V001~V029 마이그레이션 체인 적용 후 V029 변경사항을 검증한다 (FR-BL-01).
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL 에서 직접 실행한다 (V028MigrationTest 패턴 미러).
 *
 * 검증 범위.
 * (a) issues 테이블에 rank 컬럼 존재 + 타입 character varying(50) + NOT NULL
 * (b) idx_issues_project_rank 인덱스 존재 (project_id, rank)
 * (c) 백필 정합 — V028 까지 적용 + 데이터 시드 후 V029 만 추가 적용했을 때,
 *     프로젝트별 created_at 순서와 rank 사전순서가 일치 + 모든 rank 가 끝문자 != 'a' (spec FR1)
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

        // (c) 백필 검증용 별도 컨테이너 — V028 까지 적용 + 시드 + V029 만 추가 적용.
        @Container
        @JvmStatic
        val backfillPostgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_backfill")
                .withUsername("bts")
                .withPassword("bts_test")

        // 백필 검증용 두 프로젝트 — 각 프로젝트별로 rank 가 created_at 순으로 부여되는지 확인.
        private val projectAlphaId: UUID = UUID.randomUUID()
        private val projectBetaId: UUID = UUID.randomUUID()

        @BeforeAll
        @JvmStatic
        fun setup() {
            // (a)(b) 전체 마이그레이션 체인(V001~V029) 적용 — target 미지정.
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .load()
                .migrate()

            // (c) V028 까지만 적용 → 데이터 시드 → V029 만 추가 적용.
            Flyway.configure()
                .dataSource(backfillPostgres.jdbcUrl, backfillPostgres.username, backfillPostgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .target("28")
                .load()
                .migrate()

            seedBackfillData()

            Flyway.configure()
                .dataSource(backfillPostgres.jdbcUrl, backfillPostgres.username, backfillPostgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .target("29")
                .load()
                .migrate()
        }

        /**
         * 두 프로젝트에 created_at 이 섞인 순서로 이슈를 삽입한다.
         * INSERT 순서와 created_at 순서를 일부러 어긋나게 두어, 백필이 created_at(+id) 기준으로 정렬되는지 확인한다.
         */
        @Suppress("LongMethod")
        private fun seedBackfillData() {
            DriverManager.getConnection(
                backfillPostgres.jdbcUrl,
                backfillPostgres.username,
                backfillPostgres.password,
            ).use { c ->
                c.prepareStatement(
                    "INSERT INTO projects (id, key, name, key_sequence) VALUES (?, ?, ?, ?)",
                ).use { stmt ->
                    stmt.setObject(1, projectAlphaId)
                    stmt.setString(2, "ALPHA")
                    stmt.setString(3, "Alpha")
                    stmt.setLong(4, 0)
                    stmt.executeUpdate()
                }
                c.prepareStatement(
                    "INSERT INTO projects (id, key, name, key_sequence) VALUES (?, ?, ?, ?)",
                ).use { stmt ->
                    stmt.setObject(1, projectBetaId)
                    stmt.setString(2, "BETA")
                    stmt.setString(3, "Beta")
                    stmt.setLong(4, 0)
                    stmt.executeUpdate()
                }

                // ALPHA 프로젝트: 5개 이슈. created_at 을 의도적으로 INSERT 순서와 다르게 부여.
                // (key, created_at offset minutes) — created_at 오름차순 기대 순서 = A-3, A-1, A-5, A-2, A-4
                insertIssue(c, projectAlphaId, "ALPHA-1", 20)
                insertIssue(c, projectAlphaId, "ALPHA-2", 40)
                insertIssue(c, projectAlphaId, "ALPHA-3", 10)
                insertIssue(c, projectAlphaId, "ALPHA-4", 50)
                insertIssue(c, projectAlphaId, "ALPHA-5", 30)

                // BETA 프로젝트: 3개 이슈.
                insertIssue(c, projectBetaId, "BETA-1", 15)
                insertIssue(c, projectBetaId, "BETA-2", 5)
                insertIssue(c, projectBetaId, "BETA-3", 25)
            }
        }

        private fun insertIssue(
            c: Connection,
            projectId: UUID,
            key: String,
            createdMinutesOffset: Int,
        ) {
            c.prepareStatement(
                "INSERT INTO issues (id, key, project_id, summary, reporter_id, current_state_key, created_at, updated_at)" +
                    " VALUES (?, ?, ?, ?, ?, ?, NOW() + (? || ' minutes')::interval, NOW())",
            ).use { stmt ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setString(2, key)
                stmt.setObject(3, projectId)
                stmt.setString(4, "summary $key")
                stmt.setObject(5, UUID.randomUUID())
                stmt.setString(6, "open")
                stmt.setInt(7, createdMinutesOffset)
                stmt.executeUpdate()
            }
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun conn() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun backfillConn() =
        DriverManager.getConnection(backfillPostgres.jdbcUrl, backfillPostgres.username, backfillPostgres.password)

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

    /**
     * 백필 검증용 — 한 프로젝트의 이슈를 created_at, id 순으로 읽어 (key, rank) 리스트를 반환한다.
     */
    @Suppress("NestedBlockDepth") // JDBC use {} 3중 중첩 — Connection/PreparedStatement/ResultSet 생명주기 관리 패턴
    private fun rankedByCreatedAt(projectId: UUID): List<Pair<String, String>> =
        backfillConn().use { c ->
            c.prepareStatement(
                "SELECT key, rank FROM issues WHERE project_id = ? ORDER BY created_at, id",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.executeQuery().use { rs ->
                    val rows = mutableListOf<Pair<String, String>>()
                    while (rs.next()) {
                        rows += rs.getString("key") to rs.getString("rank")
                    }
                    rows
                }
            }
        }

    // ── (a) rank 컬럼 존재/타입/NOT NULL 검증 ─────────────────────────────────

    @Test
    fun `V029 rank 컬럼 타입은 character varying`() {
        assertThat(columnDataType("issues", "rank")).isEqualTo("character varying")
    }

    @Test
    fun `V029 rank 컬럼 최대 길이는 50`() {
        assertThat(columnMaxLength("issues", "rank")).isEqualTo(50)
    }

    @Test
    fun `V029 rank 컬럼은 NOT NULL`() {
        assertThat(columnIsNullable("issues", "rank")).isEqualTo("NO")
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

    // ── (c) 백필 정합 검증 ────────────────────────────────────────────────────

    @Test
    fun `V029 백필 — ALPHA 프로젝트 rank 순서가 created_at 순서와 일치`() {
        val rows = rankedByCreatedAt(projectAlphaId)
        // created_at 오름차순 기대 순서 = ALPHA-3, ALPHA-1, ALPHA-5, ALPHA-2, ALPHA-4
        assertThat(rows.map { it.first })
            .containsExactly("ALPHA-3", "ALPHA-1", "ALPHA-5", "ALPHA-2", "ALPHA-4")
        // created_at 순으로 읽은 rank 가 사전순 오름차순(strictly increasing)이어야 함.
        val ranks = rows.map { it.second }
        assertThat(ranks).isEqualTo(ranks.sorted())
        assertThat(ranks.toSet()).hasSameSizeAs(ranks) // rank 중복 없음
    }

    @Test
    fun `V029 백필 — BETA 프로젝트 rank 순서가 created_at 순서와 일치`() {
        val rows = rankedByCreatedAt(projectBetaId)
        // created_at 오름차순 기대 순서 = BETA-2, BETA-1, BETA-3
        assertThat(rows.map { it.first }).containsExactly("BETA-2", "BETA-1", "BETA-3")
        val ranks = rows.map { it.second }
        assertThat(ranks).isEqualTo(ranks.sorted())
    }

    @Test
    fun `V029 백필 — 모든 rank 는 끝문자가 a 가 아님 (spec FR1)`() {
        val allRanks =
            rankedByCreatedAt(projectAlphaId).map { it.second } +
                rankedByCreatedAt(projectBetaId).map { it.second }
        assertThat(allRanks).isNotEmpty()
        assertThat(allRanks).allSatisfy { rank ->
            assertThat(rank.last()).isNotEqualTo('a')
        }
    }

    @Test
    fun `V029 백필 — 모든 rank 는 소문자 a-z 1~50자`() {
        val allRanks =
            rankedByCreatedAt(projectAlphaId).map { it.second } +
                rankedByCreatedAt(projectBetaId).map { it.second }
        assertThat(allRanks).allSatisfy { rank ->
            assertThat(rank).matches("^[a-z]{1,50}$")
        }
    }
}
