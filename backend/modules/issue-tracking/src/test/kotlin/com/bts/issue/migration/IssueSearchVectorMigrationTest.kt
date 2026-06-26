// V032 마이그레이션 검증 — issues.search_vector(STORED generated tsvector) + GIN + description trigram GIN (FR-SR-04 한글 FTS)

package com.bts.issue.migration

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueRepository
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * Flyway V001~V032 전체 마이그레이션 체인 적용 후 V032 변경사항을 검증한다 (FR-SR-04).
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL 에서 직접 실행한다 (SummaryTrgmIndexMigrationTest 패턴 미러).
 *
 * 검증 범위.
 * (a) issues.search_vector 컬럼 존재 + 타입 tsvector
 *     + idx_issues_search_vector(GIN, search_vector) 존재
 *     + idx_issues_description_trgm(GIN, lower(description) gin_trgm_ops, coalesce 없음) 존재.
 *     B1 핵심 — description trigram 표현식은 V031 summary 와 동형(lower(description))이어야 백엔드
 *     likeIgnoreCase(`lower("description") like ?`)와 정확히 일치한다. coalesce 가 끼면 표현식 불일치로
 *     플래너가 무시 → 죽은 인덱스 → OR 전체 seq scan.
 * (b) 행 INSERT 시 search_vector 가 DDL 과 동일한 표현식
 *     `to_tsvector('simple', coalesce(summary,'') || ' ' || coalesce(description,''))` 으로 자동 산출.
 *     저장값(search_vector::text)을 같은 행의 컬럼으로 재계산한 기대값과 비교 — 표현식 불일치 시 실패.
 * (c) [B3] write-path 회귀 — generated STORED 컬럼이 이슈 생성 경로(IssueRepository.insert →
 *     toInsertRecord 의 record 기반 set(record))를 깨지 않음. insert → findByKey 라운드트립 성공 단언.
 *     jOOQ codegen 이 generated 컬럼을 readonly 로 안 빼면 `cannot insert non-DEFAULT into GENERATED column`
 *     으로 실패한다.
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 */
@Testcontainers
class IssueSearchVectorMigrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V002 pgmq 확장 요구로 인해 tembo 이미지 사용.
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

        // write-path(c) 용 jOOQ 진입점 + 리포지토리 + 시드 식별자.
        private lateinit var dsl: DSLContext
        private lateinit var repository: IssueRepository
        private lateinit var testProjectId: UUID
        private var taskTypeId: Long = 0

        @BeforeAll
        @JvmStatic
        fun setup() {
            // 전체 마이그레이션 체인(V001~V032) 적용 — target 미지정.
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .load()
                .migrate()

            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            repository = IssueRepository(dsl)

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "INSERT INTO projects (key, name) VALUES ('TPRJ', 'Test Project') ON CONFLICT (key) DO NOTHING",
                ).use { it.executeUpdate() }
                conn.prepareStatement("SELECT id FROM projects WHERE key = 'TPRJ'").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        testProjectId = rs.getObject(1) as UUID
                    }
                }
                conn.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        taskTypeId = rs.getLong(1)
                    }
                }
            }
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun conn() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

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

    private fun indexDefinition(indexName: String): String? =
        conn().use { c ->
            c.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    /** 원시 INSERT 한 행의 (저장된 search_vector::text, DDL 표현식으로 재계산한 기대값::text) 쌍을 반환한다. */
    @Suppress("NestedBlockDepth") // JDBC use {} 중첩 — Connection/PreparedStatement/ResultSet 생명주기 관리 패턴
    private fun insertedVectorVsExpected(
        key: String,
        summary: String,
        description: String?,
    ): Pair<String, String> =
        conn().use { c ->
            insertRawIssue(c, key, summary, description)
            c.prepareStatement(
                "SELECT search_vector::text," +
                    " to_tsvector('simple', coalesce(summary,'') || ' ' || coalesce(description,''))::text" +
                    " FROM issues WHERE key = ?",
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1) to rs.getString(2)
                }
            }
        }

    private fun insertRawIssue(
        c: Connection,
        key: String,
        summary: String,
        description: String?,
    ) {
        c.prepareStatement(
            "INSERT INTO issues (id, key, project_id, summary, description, reporter_id, current_state_key, type_id)" +
                " VALUES (?, ?, ?, ?, ?, ?, 'open', ?)",
        ).use { stmt ->
            stmt.setObject(1, UUID.randomUUID())
            stmt.setString(2, key)
            stmt.setObject(3, testProjectId)
            stmt.setString(4, summary)
            if (description == null) stmt.setNull(5, java.sql.Types.VARCHAR) else stmt.setString(5, description)
            stmt.setObject(6, UUID.randomUUID())
            stmt.setLong(7, taskTypeId)
            stmt.executeUpdate()
        }
    }

    // ── (a) 컬럼 + 인덱스 구조 검증 ──────────────────────────────────────────────

    @Test
    fun `V032 search_vector 컬럼 타입은 tsvector`() {
        assertThat(columnDataType("issues", "search_vector")).isEqualTo("tsvector")
    }

    @Test
    fun `V032 idx_issues_search_vector 는 search_vector GIN 인덱스`() {
        val def = indexDefinition("idx_issues_search_vector")
        assertThat(def).isNotNull()
        val lower = def!!.lowercase()
        assertThat(lower).contains("using gin")
        assertThat(lower).contains("search_vector")
    }

    @Test
    fun `V032 idx_issues_description_trgm 는 lower(description) gin_trgm_ops GIN 인덱스 (coalesce 없음)`() {
        val def = indexDefinition("idx_issues_description_trgm")
        assertThat(def).isNotNull()
        val lower = def!!.lowercase()
        assertThat(lower).contains("using gin")
        assertThat(lower).contains("gin_trgm_ops")
        // B1 — 백엔드 likeIgnoreCase(lower("description") like ?)와 표현식 일치 필수.
        assertThat(lower).contains("lower(")
        assertThat(lower).contains("description")
        // coalesce 가 끼면 표현식 불일치 → 죽은 인덱스. V031 summary 와 동형으로 bare lower(description) 여야 함.
        assertThat(lower).doesNotContain("coalesce")
        assertThat(lower).doesNotContain("(description gin_trgm_ops)")
    }

    // ── (b) generated column 자동 산출 표현식 검증 ──────────────────────────────

    @Test
    fun `V032 search_vector 는 to_tsvector('simple', summary와 description 결합)으로 자동 산출`() {
        val (stored, expected) =
            insertedVectorVsExpected(
                key = "TPRJ-901",
                summary = "로그인 토큰 만료",
                description = "사용자가 로그인 후 토큰 만료 시 search 에러 발생",
            )
        // 저장된 search_vector 가 같은 행의 DDL 표현식 재계산 결과와 정확히 일치 → generated 표현식 동일 보증.
        assertThat(stored).isEqualTo(expected)
        // 토큰이 실제로 색인됐는지(빈 벡터 아님) 추가 보증.
        assertThat(stored).isNotBlank()
    }

    @Test
    fun `V032 search_vector 는 description 이 NULL 이어도 summary 만으로 산출`() {
        val (stored, expected) =
            insertedVectorVsExpected(
                key = "TPRJ-902",
                summary = "제목만 있는 이슈",
                description = null,
            )
        assertThat(stored).isEqualTo(expected)
        assertThat(stored).isNotBlank()
    }

    // ── (c) [B3] write-path 회귀 — generated 컬럼이 record 기반 INSERT 를 깨지 않음 ──

    @Test
    fun `V032 이슈 생성 write-path(IssueRepository_insert set record)가 generated 컬럼에서 깨지지 않는다`() {
        val key = IssueKey.of("TPRJ", 1L)
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = IssueTypeId(taskTypeId),
                summary = "토큰 만료 검색 대상",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
                description = "본문에만 등장하는 한글 토큰 — 인앱 알림",
            )

        // toInsertRecord() → set(record) → returning() 경로가 generated 컬럼에서 예외 없이 통과해야 한다.
        repository.insert(issue)

        val found = requireNotNull(repository.findByKey(key)) { "write-path insert 후 이슈 조회 불가" }
        assertThat(found.summary).isEqualTo("토큰 만료 검색 대상")
        assertThat(found.description).isEqualTo("본문에만 등장하는 한글 토큰 — 인앱 알림")

        // generated 컬럼이 repository 경로 INSERT 에서도 자동 산출됐는지 확인(빈 벡터 아님).
        val storedVector =
            conn().use { c ->
                c.prepareStatement("SELECT search_vector::text FROM issues WHERE key = ?").use { stmt ->
                    stmt.setString(1, key.value)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            }
        assertThat(storedVector).isNotBlank()
    }
}
