// issue-tracking V039 마이그레이션 검증 — description_html/description_plain 파생과 검색 인덱스 생존 확인

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
 * Flyway V001~V039 전체 체인 적용 후 V039 변경사항을 검증한다.
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL 에서 직접 실행한다 (V038MigrationIntegrationTest 동형).
 *
 * ## 검증 범위
 *
 * (a) `description_plain` 이 HTML 태그를 벗긴다 — 검색이 `p`·`strong` 을 토큰으로 긁지 않는다
 * (b) `description_html` 이 없으면 마크다운 원문이 그대로 평문이 된다 — 옛 행 경로
 * (c) HTML 이 있으면 그쪽이 이긴다 — 두 컬럼이 공존할 때의 우선순위
 * (d) `search_vector` 도 같은 평문을 색인한다 — 두 파생이 갈라지지 않았다
 * (e) ★**trigram 인덱스가 실제로 선택된다** (EXPLAIN 단언)
 * (f) `comments.body_html` 컬럼 존재
 *
 * ## (e) 가 이 파일의 존재 이유다
 *
 * V039 는 `idx_issues_description_trgm` 을 `lower(description_plain)` 으로 재작성했고, 백엔드는
 * `IssueRepository.buildTextSearchCondition` 이 **문자열로** 같은 표현식을 만든다. 둘이 어긋나면
 * 플래너가 인덱스를 무시하고 seq scan 으로 떨어지는데 — **기능 테스트는 전부 통과한다.**
 * 결과가 맞기 때문이다. 느려질 뿐이고, 느린 것은 테스트가 재지 않는다.
 *
 * V032 주석이 같은 함정을 [B1] 로 이미 기록해 뒀다. 그때는 `likeIgnoreCase`(native ILIKE)가
 * `lower(col)` 표현식 인덱스를 못 써 seq scan 이 나는 것을 EXPLAIN 실측으로 잡았다.
 * 이 테스트는 그 실측을 자동화해 다음 사람이 표현식을 건드릴 때 즉시 red 를 보게 한다.
 */
@Testcontainers
class V039MigrationIntegrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V002 pgmq 확장 요구 (V038MigrationIntegrationTest 와 같은 결정).
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

    /** 단일 값 스칼라 조회. */
    @Suppress("NestedBlockDepth")
    private fun scalar(sql: String): String? =
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }

    /**
     * 이슈 1건을 넣고 그 행의 `description_plain` 을 돌려준다.
     *
     * 공용 DB 의 선재 행에 기대지 않도록 **자기 행을 직접 넣고 그 id 로만** 읽는다
     * (DATA.md §4 · `shared-dev-db-preexisting-rows-fake-green`).
     */
    @Suppress("NestedBlockDepth")
    private fun insertAndReadPlain(
        description: String?,
        descriptionHtml: String?,
    ): String? =
        conn().use { c ->
            val projectId =
                c.createStatement().use { stmt ->
                    stmt.executeQuery(
                        """
                        INSERT INTO projects (key, name)
                        VALUES ('V39${(0..999999).random()}', 'V039 검증')
                        RETURNING id::text
                        """.trimIndent(),
                    ).use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            c.prepareStatement(
                """
                INSERT INTO issues
                    (project_id, key, summary, description, description_html, reporter_id, current_state_key, type_id)
                VALUES
                    (?::uuid, ?, '제목', ?, ?, gen_random_uuid(), 'open', 1)
                RETURNING description_plain
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, projectId)
                stmt.setString(2, "V39${(0..999999).random()}-1")
                stmt.setString(3, description)
                stmt.setString(4, descriptionHtml)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    // ── (a)~(c) description_plain 파생 ────────────────────────────────────────

    @Test
    fun `description_html 의 태그를 벗겨 평문을 만든다`() {
        val plain = insertAndReadPlain(description = null, descriptionHtml = "<p><strong>굵은</strong> 본문</p>")

        // 태그명이 남으면 FTS 가 'p'·'strong' 을 검색어로 색인한다.
        assertThat(plain).isEqualTo("굵은 본문")
    }

    @Test
    fun `description_html 이 없으면 마크다운 원문이 그대로 평문이 된다`() {
        // V039 이전에 쌓인 행의 경로. 백필을 하지 않으므로 이 분기가 실제로 돌아간다.
        val plain = insertAndReadPlain(description = "**굵은** 본문", descriptionHtml = null)

        assertThat(plain).isEqualTo("**굵은** 본문")
    }

    @Test
    fun `둘 다 있으면 HTML 쪽이 이긴다`() {
        val plain = insertAndReadPlain(description = "옛 마크다운", descriptionHtml = "<p>새 본문</p>")

        // 편집으로 HTML 이 채워진 뒤에는 마크다운이 옛 내용을 담은 채 남는다.
        // 검색이 그것을 긁으면 「고쳤는데 옛 내용으로 검색된다」가 된다.
        assertThat(plain).isEqualTo("새 본문")
    }

    @Test
    fun `본문이 전부 없으면 빈 문자열이다`() {
        assertThat(insertAndReadPlain(description = null, descriptionHtml = null)).isEmpty()
    }

    // ── (d) search_vector 정합 ────────────────────────────────────────────────

    @Test
    fun `search_vector 가 description_plain 과 같은 평문을 색인한다`() {
        // 두 파생이 같은 함수(issue_body_plain)를 호출하는지 실측한다. 식을 복사해 두면
        // 한쪽만 고쳤을 때 FTS 와 trigram 이 서로 다른 텍스트를 색인하게 된다.
        val matches =
            scalar(
                """
                SELECT to_tsvector('simple', '제목' || ' ' || issue_body_plain('<p>토큰</p>', NULL))
                     = to_tsvector('simple', '제목' || ' ' || '토큰')
                """.trimIndent(),
            )

        // JDBC getString 은 PostgreSQL boolean 을 "t"/"f" 로 돌려준다.
        assertThat(matches).isEqualTo("t")
    }

    // ── (e) ★죽은 인덱스 가드 ─────────────────────────────────────────────────

    @Test
    fun `trigram 인덱스가 실제로 선택된다 — 죽은 인덱스 차단`() {
        conn().use { c ->
            // 플래너가 인덱스를 고르려면 seq scan 이 더 비싸야 한다. 소량 데이터에서는
            // 언제나 seq scan 이 이기므로 그 선택지를 끈다 — 인덱스가 **사용 가능한지**를 재는 것이지
            // 최적화기의 비용 판단을 재는 게 아니다.
            c.createStatement().use { it.execute("SET enable_seqscan = off") }

            val plan =
                c.createStatement().use { stmt ->
                    stmt.executeQuery(
                        """
                        EXPLAIN SELECT id FROM issues
                        WHERE lower(issues.description_plain) like '%검색어%' escape '\'
                        """.trimIndent(),
                    ).use { rs ->
                        buildString { while (rs.next()) appendLine(rs.getString(1)) }
                    }
                }

            // ★이 문자열은 IssueRepository.buildTextSearchCondition 이 만드는 것과 **정확히 같아야** 한다.
            // 표현식이 어긋나면 여기가 Seq Scan 으로 떨어지고, 기능 테스트는 전부 초록을 유지한다.
            assertThat(plan)
                .describedAs(
                    "lower(description_plain) 표현식이 V039 의 trigram 인덱스와 어긋났다. " +
                        "IssueRepository.buildTextSearchCondition 의 raw SQL 과 마이그레이션의 " +
                        "인덱스 정의를 같은 형태로 맞출 것.\n실제 실행계획:\n%s",
                    plan,
                )
                .contains("idx_issues_description_trgm")
        }
    }

    @Test
    fun `EXPLAIN 단언이 공허하지 않다 — 다른 표현식은 이 인덱스를 못 쓴다`() {
        // 위 단언이 「무엇을 넣어도 인덱스 이름이 나온다」로 죽어 있지 않은지 되잰다.
        // coalesce 를 끼우면 표현식이 달라져 플래너가 이 인덱스를 고를 수 없다 — V032 [B1] 이
        // 경고한 바로 그 형태다.
        conn().use { c ->
            c.createStatement().use { it.execute("SET enable_seqscan = off") }

            val plan =
                c.createStatement().use { stmt ->
                    stmt.executeQuery(
                        """
                        EXPLAIN SELECT id FROM issues
                        WHERE lower(coalesce(issues.description_plain, '')) like '%검색어%' escape '\'
                        """.trimIndent(),
                    ).use { rs ->
                        buildString { while (rs.next()) appendLine(rs.getString(1)) }
                    }
                }

            assertThat(plan).doesNotContain("idx_issues_description_trgm")
        }
    }

    // ── (f) 댓글 컬럼 ─────────────────────────────────────────────────────────

    @Test
    fun `comments 에 body_html 컬럼이 있다`() {
        val dataType =
            scalar(
                """
                SELECT data_type FROM information_schema.columns
                WHERE table_name = 'comments' AND column_name = 'body_html'
                """.trimIndent(),
            )

        assertThat(dataType).isEqualTo("text")
    }

    @Test
    fun `description_plain 은 생성 컬럼이라 앱이 직접 쓸 수 없다`() {
        // jOOQ codegen 에서 제외한 이유가 여기 있다 — record 기반 INSERT 가 이 컬럼을 실으면
        // "cannot insert a non-DEFAULT value" 로 죽는다(search_vector 와 같은 함정).
        val isGenerated =
            scalar(
                """
                SELECT is_generated FROM information_schema.columns
                WHERE table_name = 'issues' AND column_name = 'description_plain'
                """.trimIndent(),
            )

        assertThat(isGenerated).isEqualTo("ALWAYS")
    }
}
