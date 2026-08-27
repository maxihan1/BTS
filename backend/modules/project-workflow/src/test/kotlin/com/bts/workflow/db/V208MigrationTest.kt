// V208(초안·발행 이력) 마이그레이션 검증 — 초안 1:1 · 발행 이력 append-only · CASCADE 를 컨테이너 1개로 확인한다

package com.bts.workflow.db

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

/**
 * Flyway V208(`workflow_drafts_and_publications`) 검증.
 *
 * ### 적용 순서 (load-bearing)
 * ```
 * ① target 200   issue-tracking V001~ + project-workflow V200
 * ② issue_types 수동 생성          V201 의 cross-BC FK 대상 (V207MigrationTest 와 동일 패턴)
 * ③ target latest V201 ~ V208
 * ```
 * V207 과 달리 **픽스처 선주입이 없다.** V208 은 백필이 0행인 순수 add 마이그레이션이라
 * 원본 데이터가 검증에 기여하지 않는다 — 각 테스트가 필요한 행을 롤백 트랜잭션 안에서 직접 심는다.
 *
 * ### 왜 append-only 를 DB 트리거로 강제하는가
 * 로드맵은 「UPDATE·DELETE 경로를 만들지 않는다」를 애플리케이션 약속으로 뒀다. 그 약속은
 * **아무도 검사하지 않는다** — 나중에 리포지토리에 UPDATE 를 하나 더하면 조용히 깨지고,
 * 깨진 사실이 어디에도 드러나지 않는다(`[[two-lists-never-check-each-other]]` 와 같은 양식).
 * 발행 이력은 감사 기록이고 ADR 2026-08-25 가 append-only 를 계약으로 선언했으므로
 * 계약을 기계가 지키게 한다. 트리거가 그 자리다.
 *
 * ### 성공 INSERT 는 반드시 롤백한다
 * 메인 체인 DB 를 오염시키면 뒤따르는 개수 검증이 실행 순서에 좌우된다. JUnit 실행 순서는
 * 보장되지 않으므로 성공 경로도 [inRolledBackTransaction] 으로 격리한다 (V207MigrationTest 관례).
 *
 * 이미지는 `quay.io/tembo/pg16-pgmq:latest` — issue-tracking V004 가 pgmq 확장을 요구한다
 * (ADR 2026-05-22-pgmq-postgres-image).
 *
 * 참조. FR-WF-07 D3 · ADR 2026-08-18-workflow-db-as-source-of-truth §D4 ·
 * ADR 2026-08-25-workflow-transition-rule-hard-delete (발행 이력 append-only 선언)
 */
@Testcontainers
class V208MigrationTest {
    companion object {
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
            migrateTo(postgres.jdbcUrl, "200")
            createIssueTypes(postgres.jdbcUrl)
            migrateToLatest(postgres.jdbcUrl)
        }

        @JvmStatic
        fun migrateTo(
            jdbcUrl: String,
            target: String,
        ) {
            flyway(jdbcUrl).target(target).load().migrate()
        }

        /** 남은 마이그레이션을 전부 적용한다. target 을 숫자로 고정하면 그 마이그레이션 이전 시점에 클래스가 통째로 죽는다. */
        @JvmStatic
        fun migrateToLatest(jdbcUrl: String) {
            flyway(jdbcUrl).load().migrate()
        }

        @JvmStatic
        private fun flyway(jdbcUrl: String) =
            Flyway.configure()
                .dataSource(jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )

        /** V201 의 cross-BC FK 대상. issue-tracking 마이그레이션이 만들지 않는 경로라 테스트가 직접 만든다. */
        @JvmStatic
        fun createIssueTypes(jdbcUrl: String) {
            DriverManager.getConnection(jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        "CREATE TABLE IF NOT EXISTS issue_types (" +
                            "id BIGSERIAL PRIMARY KEY, key VARCHAR(30) NOT NULL UNIQUE, " +
                            "name VARCHAR(255) NOT NULL, is_standard BOOLEAN NOT NULL DEFAULT FALSE, " +
                            "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), " +
                            "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)",
                    )
                }
            }
        }
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun <T> query(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    rs.next()
                    read(rs)
                }
            }
        }

    private fun tableExists(tableName: String): Boolean =
        query(
            "SELECT COUNT(*) FROM information_schema.tables" +
                " WHERE table_schema = 'public' AND table_name = '$tableName'",
        ) { it.getInt(1) > 0 }

    /** 컬럼의 선언 타입. TIMESTAMPTZ 강제(DATA.md §4 규칙 4)를 이 값으로 본다. */
    private fun columnType(
        tableName: String,
        columnName: String,
    ): String =
        query(
            "SELECT data_type FROM information_schema.columns" +
                " WHERE table_schema = 'public' AND table_name = '$tableName'" +
                " AND column_name = '$columnName'",
        ) { it.getString(1) ?: "" }

    private fun isNullable(
        tableName: String,
        columnName: String,
    ): Boolean =
        query(
            "SELECT is_nullable FROM information_schema.columns" +
                " WHERE table_schema = 'public' AND table_name = '$tableName'" +
                " AND column_name = '$columnName'",
        ) { it.getString(1) == "YES" }

    /**
     * SQL 을 트랜잭션 안에서 실행한 뒤 **반드시 롤백**한다.
     *
     * 성공 경로 검증이 메인 체인 DB 를 오염시키면 뒤따르는 개수 검증이 실행 순서에 따라 무너진다.
     */
    private fun <T> inRolledBackTransaction(work: (Connection) -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                work(conn)
            } finally {
                conn.rollback()
            }
        }

    /** 문장이 실패하기를 기대하고 그 메시지를 돌려준다. 성공하면 테스트를 실패시킨다. */
    private fun statementFailsWith(sql: String): String {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute(sql) }
            } catch (ex: java.sql.SQLException) {
                return ex.message ?: ""
            } finally {
                conn.rollback()
            }
        }
        error("문장이 실패해야 하는데 성공했다: $sql")
    }

    /**
     * 이미 심은 워크플로우 위에서 [work] 를 실행한다. 워크플로우 행이 없으면 FK 때문에
     * 초안·발행 이력을 심을 수 없어 모든 검증이 성립하지 않는다.
     *
     * @return [work] 의 반환값
     */
    private fun <T> withWorkflow(work: (Connection, String) -> T): T =
        inRolledBackTransaction { conn ->
            val workflowId =
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "INSERT INTO workflows (key, name) VALUES ('wf-v208', 'V208 픽스처') RETURNING id",
                    ).use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            work(conn, workflowId)
        }

    private fun countOf(
        conn: Connection,
        sql: String,
    ): Int =
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    // ── 테이블이 생겼는가 ─────────────────────────────────────────────────────

    @Test
    fun `workflow_drafts 와 workflow_publications 가 생성된다`() {
        assertThat(tableExists("workflow_drafts")).isTrue()
        assertThat(tableExists("workflow_publications")).isTrue()
    }

    @Test
    fun `시각 컬럼은 전부 TIMESTAMPTZ 다`() {
        // DATA.md §4 규칙 4 — TIMESTAMP without time zone 금지.
        assertThat(columnType("workflow_drafts", "updated_at")).isEqualTo("timestamp with time zone")
        assertThat(columnType("workflow_publications", "published_at")).isEqualTo("timestamp with time zone")
    }

    @Test
    fun `definition 은 JSONB 이고 NULL 을 허용하지 않는다`() {
        // 초안·발행본 둘 다 정의를 통째로 담는다. NULL 이면 「무엇을 발행했는가」가 사라진다.
        assertThat(columnType("workflow_drafts", "definition")).isEqualTo("jsonb")
        assertThat(columnType("workflow_publications", "definition")).isEqualTo("jsonb")
        assertThat(isNullable("workflow_drafts", "definition")).isFalse()
        assertThat(isNullable("workflow_publications", "definition")).isFalse()
    }

    // ── 초안은 워크플로우당 1개 ───────────────────────────────────────────────

    @Test
    fun `초안은 워크플로우당 1개 — 2건째 INSERT 가 PK 위반`() {
        // workflow_id 가 PK 다. 초안이 둘이면 「발행 대상이 어느 쪽인가」를 정할 수 없다.
        val message =
            withWorkflow { conn, workflowId ->
                conn.createStatement().use {
                    it.execute(
                        "INSERT INTO workflow_drafts (workflow_id, definition, base_version)" +
                            " VALUES ('$workflowId', '{}'::jsonb, 0)",
                    )
                }
                try {
                    conn.createStatement().use {
                        it.execute(
                            "INSERT INTO workflow_drafts (workflow_id, definition, base_version)" +
                                " VALUES ('$workflowId', '{\"x\":1}'::jsonb, 1)",
                        )
                    }
                    error("2건째 초안 INSERT 가 실패해야 하는데 성공했다")
                } catch (ex: java.sql.SQLException) {
                    ex.message ?: ""
                }
            }

        assertThat(message).containsIgnoringCase("workflow_drafts_pkey")
    }

    @Test
    fun `워크플로우를 지우면 초안도 함께 지워진다 (CASCADE)`() {
        // 초안은 워크플로우에 종속이다. 남으면 주인 없는 초안이 되고 발행 대상을 찾을 수 없다.
        val remaining =
            withWorkflow { conn, workflowId ->
                conn.createStatement().use {
                    it.execute(
                        "INSERT INTO workflow_drafts (workflow_id, definition, base_version)" +
                            " VALUES ('$workflowId', '{}'::jsonb, 0)",
                    )
                }
                conn.createStatement().use { it.execute("DELETE FROM workflows WHERE id = '$workflowId'") }
                countOf(conn, "SELECT COUNT(*) FROM workflow_drafts WHERE workflow_id = '$workflowId'")
            }

        assertThat(remaining).isZero()
    }

    // ── 발행 이력 ─────────────────────────────────────────────────────────────

    @Test
    fun `같은 워크플로우에 같은 version_no 를 두 번 적재할 수 없다`() {
        val message =
            withWorkflow { conn, workflowId ->
                conn.createStatement().use {
                    it.execute(
                        "INSERT INTO workflow_publications (workflow_id, version_no, definition)" +
                            " VALUES ('$workflowId', 1, '{}'::jsonb)",
                    )
                }
                try {
                    conn.createStatement().use {
                        it.execute(
                            "INSERT INTO workflow_publications (workflow_id, version_no, definition)" +
                                " VALUES ('$workflowId', 1, '{\"x\":1}'::jsonb)",
                        )
                    }
                    error("같은 version_no 2건째가 실패해야 하는데 성공했다")
                } catch (ex: java.sql.SQLException) {
                    ex.message ?: ""
                }
            }

        assertThat(message).containsIgnoringCase("uq_workflow_publications_version")
    }

    @Test
    fun `발행 이력은 UPDATE 할 수 없다 (append-only)`() {
        // 「경로를 안 만든다」는 약속은 아무도 검사하지 않는다. 트리거가 계약을 진다.
        val message =
            withWorkflow { conn, workflowId ->
                conn.createStatement().use {
                    it.execute(
                        "INSERT INTO workflow_publications (workflow_id, version_no, definition)" +
                            " VALUES ('$workflowId', 1, '{}'::jsonb)",
                    )
                }
                try {
                    conn.createStatement().use {
                        it.execute(
                            "UPDATE workflow_publications SET definition = '{\"tampered\":true}'::jsonb" +
                                " WHERE workflow_id = '$workflowId'",
                        )
                    }
                    error("발행 이력 UPDATE 가 거부돼야 하는데 성공했다")
                } catch (ex: java.sql.SQLException) {
                    ex.message ?: ""
                }
            }

        assertThat(message).contains("append-only")
    }

    @Test
    fun `발행 이력은 DELETE 할 수 없다 (append-only)`() {
        val message =
            withWorkflow { conn, workflowId ->
                conn.createStatement().use {
                    it.execute(
                        "INSERT INTO workflow_publications (workflow_id, version_no, definition)" +
                            " VALUES ('$workflowId', 1, '{}'::jsonb)",
                    )
                }
                try {
                    conn.createStatement().use {
                        it.execute("DELETE FROM workflow_publications WHERE workflow_id = '$workflowId'")
                    }
                    error("발행 이력 DELETE 가 거부돼야 하는데 성공했다")
                } catch (ex: java.sql.SQLException) {
                    ex.message ?: ""
                }
            }

        assertThat(message).contains("append-only")
    }

    @Test
    fun `워크플로우를 지우면 발행 이력도 함께 지워진다 (CASCADE 는 트리거보다 먼저다)`() {
        // append-only 트리거가 CASCADE 까지 막으면 워크플로우를 영영 못 지운다.
        // 이력 보존과 삭제 가능성이 충돌하는 지점이라 어느 쪽인지 명시적으로 못박는다.
        val remaining =
            withWorkflow { conn, workflowId ->
                conn.createStatement().use {
                    it.execute(
                        "INSERT INTO workflow_publications (workflow_id, version_no, definition)" +
                            " VALUES ('$workflowId', 1, '{}'::jsonb)",
                    )
                }
                conn.createStatement().use { it.execute("DELETE FROM workflows WHERE id = '$workflowId'") }
                countOf(conn, "SELECT COUNT(*) FROM workflow_publications WHERE workflow_id = '$workflowId'")
            }

        assertThat(remaining).isZero()
    }

    // ── 마이그레이션이 기존 체인을 깨지 않았는가 ──────────────────────────────

    @Test
    fun `V207 이 남긴 구 컬럼과 workflow_states 는 여전히 살아 있다`() {
        // 3단 분할의 3단(DROP)은 이 PR 범위 밖이다. 여기서 사라졌다면 롤백 자리를 잃은 것이다.
        assertThat(tableExists("workflow_states")).isTrue()
        assertThat(columnType("workflow_transitions", "from_state_id")).isEqualTo("uuid")
    }

    @Test
    fun `초안이 없는 워크플로우도 정상이다`() {
        // 초안은 선택적이다. 편집을 시작하지 않은 워크플로우에는 행이 없다.
        val drafts =
            withWorkflow { conn, workflowId ->
                countOf(conn, "SELECT COUNT(*) FROM workflow_drafts WHERE workflow_id = '$workflowId'")
            }

        assertThat(drafts).isZero()
    }

    @Test
    fun `존재하지 않는 워크플로우의 초안은 심을 수 없다 (FK)`() {
        val message =
            statementFailsWith(
                "INSERT INTO workflow_drafts (workflow_id, definition, base_version)" +
                    " VALUES ('00000000-0000-0000-0000-000000000000', '{}'::jsonb, 0)",
            )

        // ★ 제약 **이름**으로 단언한다. 테이블 이름으로 보면 테이블이 아예 없을 때의
        // `relation "workflow_drafts" does not exist` 도 통과해 판정이 공허해진다
        // (`[[invariant-satisfied-by-helptext-not-logic]]` — red 단계에서 실제로 그렇게 통과했다).
        assertThat(message).contains("fk_workflow_drafts_workflow")
    }
}
