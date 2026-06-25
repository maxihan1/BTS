// V031 마이그레이션 검증 — pg_trgm 확장 + issues.summary 표현식 trigram GIN 인덱스 (FR-SR-02 D3, AQL `~` 가속)

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
import java.util.UUID

/**
 * Flyway V001~V031 전체 마이그레이션 체인 적용 후 V031 변경사항을 검증한다 (FR-SR-02 D3).
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL 에서 직접 실행한다 (V028/V030MigrationTest 패턴 미러).
 *
 * 검증 범위.
 * (a) pg_extension 에 pg_trgm 확장 설치됨 (gin_trgm_ops opclass 의 전제)
 * (b) idx_issues_summary_trgm 의 pg_indexes.indexdef 가 'using gin' + 'gin_trgm_ops' + 'lower(summary)' 를 모두 포함
 *     — 이름만 보는 indexExists 는 vacuous 하므로 indexdef 단언 병행 (CustomFieldsMigrationTest:100 선례).
 * (c) EXPLAIN 인덱스 사용 검증 — 이슈 다수 시드 후 백엔드 `~` 와 동일한 `lower(summary) LIKE lower(?)`
 *     쿼리의 EXPLAIN 이 idx_issues_summary_trgm 을 통한 Bitmap Index Scan(또는 Index Scan)을 포함하는지 단언.
 *     (B1 핵심 — bare summary 인덱스는 lower() 표현식 불일치로 죽은 인덱스가 되므로, 인덱스가 실제로 선택
 *      가능한지를 검증한다. 소량 데이터에서 플래너가 seq scan 을 선호할 수 있어 SET enable_seqscan=off 로
 *      인덱스 선택을 강제하여 "인덱스 사용 가능성" 자체를 확인한다.)
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 */
@Testcontainers
class SummaryTrgmIndexMigrationTest {
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
            // 전체 마이그레이션 체인(V001~V031) 적용 — target 미지정.
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

    /** pg_extension 카탈로그에 확장명이 설치돼 있는지 확인한다. */
    private fun extensionInstalled(extName: String): Boolean =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = ?",
            ).use { stmt ->
                stmt.setString(1, extName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    /** 인덱스 정의 문자열(pg_indexes.indexdef)을 반환한다. */
    @Suppress("NestedBlockDepth") // JDBC use {} 중첩 — Connection/PreparedStatement/ResultSet 생명주기 관리 패턴
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
     * EXPLAIN 인덱스 사용 검증을 위한 시드 + 플랜 추출.
     *
     * 1. 프로젝트 1건 + 이슈 N건(summary 다양화) 시드 후 ANALYZE 로 플래너 통계 갱신.
     * 2. SET enable_seqscan=off 로 인덱스 선택을 강제(소량 데이터 seq scan 회피).
     * 3. 백엔드 `~`(buildSummaryCondition CONTAINS, IssueRepository.kt:2298)와 동일한
     *    `lower(summary) LIKE lower(?)` 쿼리의 EXPLAIN 플랜 텍스트를 한 문자열로 합쳐 반환.
     */
    @Suppress("NestedBlockDepth") // JDBC use {} 중첩 — 시드/ANALYZE/EXPLAIN 순차 실행 패턴
    private fun explainSummaryContains(term: String): String =
        conn().use { c ->
            val projectId = UUID.randomUUID()
            c.prepareStatement(
                "INSERT INTO projects (id, key, name) VALUES (?, 'TRGM', 'Trigram Seed Project')",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.executeUpdate()
            }
            // issues.type_id 는 V005 에서 추가된 NOT NULL FK → issue_types(id). V003 표준 타입 중 하나를 사용.
            val typeId =
                c.prepareStatement("SELECT id FROM issue_types LIMIT 1").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1)
                    }
                }
            // 충분한 행수 시드 — 플래너가 인덱스를 후보로 고려하도록 데이터량 확보.
            c.prepareStatement(
                "INSERT INTO issues (id, key, project_id, summary, reporter_id, current_state_key, type_id)" +
                    " VALUES (?, ?, ?, ?, ?, 'open', ?)",
            ).use { stmt ->
                for (i in 1..500) {
                    stmt.setObject(1, UUID.randomUUID())
                    stmt.setString(2, "TRGM-$i")
                    stmt.setObject(3, projectId)
                    stmt.setString(4, "issue summary number $i contains login and search terms")
                    stmt.setObject(5, UUID.randomUUID())
                    stmt.setObject(6, typeId)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            c.createStatement().use { st -> st.execute("ANALYZE issues") }
            // 인덱스 선택 강제 — 소량/균질 데이터에서도 인덱스 사용 가능성 자체를 검증.
            c.createStatement().use { st -> st.execute("SET enable_seqscan = off") }

            val plan = StringBuilder()
            c.prepareStatement(
                "EXPLAIN SELECT * FROM issues WHERE lower(summary) LIKE lower(?)",
            ).use { stmt ->
                stmt.setString(1, "%$term%")
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        plan.appendLine(rs.getString(1))
                    }
                }
            }
            plan.toString()
        }

    // ── (a) pg_trgm 확장 검증 ─────────────────────────────────────────────────

    @Test
    fun `V031 pg_trgm 확장이 설치된다`() {
        assertThat(extensionInstalled("pg_trgm")).isTrue()
    }

    // ── (b) 인덱스 정의 검증 (indexdef — vacuous 방지) ───────────────────────────

    @Test
    fun `V031 idx_issues_summary_trgm 은 lower(summary) gin_trgm_ops GIN 인덱스`() {
        val def = indexDefinition("idx_issues_summary_trgm")
        assertThat(def).isNotNull()
        val lower = def!!.lowercase()
        assertThat(lower).contains("using gin")
        assertThat(lower).contains("gin_trgm_ops")
        // 표현식 인덱스 — bare summary 가 아니라 lower(summary) 여야 likeIgnoreCase(lower() 매칭)에 사용됨.
        // PostgreSQL 은 summary(VARCHAR)를 text 로 캐스팅해 lower((summary)::text) 형태로 정규화 렌더링한다.
        assertThat(lower).contains("lower(")
        assertThat(lower).contains("summary")
        // bare 컬럼 인덱스(gin (summary gin_trgm_ops))가 아님을 보증 — lower() 표현식이 opclass 직전에 와야 함.
        assertThat(lower).doesNotContain("(summary gin_trgm_ops)")
    }

    // ── (c) EXPLAIN 인덱스 사용 검증 (B1 — 죽은 인덱스 방지) ──────────────────────

    @Test
    fun `V031 summary CONTAINS 쿼리는 idx_issues_summary_trgm 인덱스를 사용한다`() {
        val plan = explainSummaryContains("login")
        // 인덱스 이름이 플랜에 등장 + Bitmap/Index Scan 으로 접근하는지 단언.
        assertThat(plan).contains("idx_issues_summary_trgm")
        assertThat(plan.lowercase()).containsAnyOf("bitmap index scan", "index scan")
    }
}
