// V207(다중·전역·최초 전환) 마이그레이션 검증 — UNIQUE 해제 · kind CHECK · 상태 카탈로그 재지정 백필을 컨테이너 1개로 확인한다

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
 * Flyway V207(`transitions_multi_and_global`) 검증.
 *
 * ### 적용 순서 (load-bearing)
 * ```
 * ① target 200   issue-tracking V001~ + project-workflow V200
 * ② issue_types 수동 생성          V201 의 cross-BC FK 대상 (V203ToV206MigrationTest 와 동일 패턴)
 * ③ target 203   V201 · V202 · V203
 * ④ 픽스처 주입   workflows 2 + workflow_states 6 + workflow_transitions 5   ← V204·V207 백필의 원본
 * ⑤ target latest V204(카탈로그 백필) · V205 · V206 · V207
 * ```
 * ④ 가 ③ 과 ⑤ 사이에 있어야 한다. 빈 DB 에 V207 을 적용하면 백필이 0행이라 아무것도 검증하지 못한다
 * (`[[shared-dev-db-preexisting-rows-fake-green]]` 의 반대 방향 — 원본이 없으면 조용히 초록이다).
 *
 * ### 픽스처가 일부러 어긋나 있다
 * `wf-beta` 의 `display_order` 는 삽입 순서와 다르다(5 · 2 · 9). INITIAL 백필이 「최소 display_order」가
 * 아니라 「첫 번째로 삽입된 상태」를 고르면 이 픽스처에서만 red 가 난다. 현행
 * `WorkflowKeyResolverImpl` 의 `minByOrNull { it.displayOrder }` 를 그대로 보존하는지 보는 자리다.
 *
 * ### 성공 INSERT 는 반드시 롤백한다
 * 「같은 상태쌍 2행이 들어간다」처럼 **성공**을 검증하는 INSERT 는 메인 체인 DB 를 오염시켜
 * display_order·INITIAL 개수 검증을 뒤에서 무너뜨린다. JUnit 실행 순서는 보장되지 않으므로
 * 성공 경로도 [inRolledBackTransaction] 으로 격리한다.
 *
 * 이미지는 `quay.io/tembo/pg16-pgmq:latest` — issue-tracking V004 가 pgmq 확장을 요구한다
 * (ADR 2026-05-22-pgmq-postgres-image · `V203ToV206MigrationTest` 와 동일).
 *
 * 참조. FR-WF-05 · ADR 2026-08-18-workflow-transition-id-identity ·
 * `docs/specs/2026-08-20-backend-workflow-transition-id-multi-global.md` §데이터 모델 변경
 */
@Testcontainers
class V207MigrationTest {
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

        /**
         * 상태 픽스처 — (워크플로우 키, 상태 키, 이름, 카테고리, 표시순서).
         *
         * V204 의 가드 3종을 통과해야 한다. ① key 당 (name, category) 1:1 ② 서로 다른 key 가
         * 같은 lower(name) 을 쓰지 않음 ③ key 50자 이하. 이름을 전부 다르게 둔 것이 ②의 이유다.
         */
        @JvmStatic
        val fixtureStates: List<StateRow> =
            listOf(
                StateRow("wf-alpha", "a_open", "Alpha Open", "TODO", 1),
                StateRow("wf-alpha", "a_doing", "Alpha Doing", "IN_PROGRESS", 2),
                StateRow("wf-alpha", "a_done", "Alpha Done", "DONE", 3),
                // 삽입 순서와 display_order 가 어긋난다 — INITIAL 백필의 판별력을 만드는 자리다.
                StateRow("wf-beta", "b_todo", "Beta Todo", "TODO", 5),
                StateRow("wf-beta", "b_wip", "Beta Wip", "IN_PROGRESS", 2),
                StateRow("wf-beta", "b_end", "Beta End", "DONE", 9),
            )

        /** 전환 픽스처 — (워크플로우 키, from 상태 키, to 상태 키, 이름). V207 백필의 원본이다. */
        @JvmStatic
        val fixtureTransitions: List<TransitionRow> =
            listOf(
                TransitionRow("wf-alpha", "a_open", "a_doing", "알파 시작"),
                TransitionRow("wf-alpha", "a_doing", "a_done", "알파 완료"),
                TransitionRow("wf-alpha", "a_open", "a_done", "알파 즉시 완료"),
                TransitionRow("wf-beta", "b_todo", "b_wip", "베타 착수"),
                TransitionRow("wf-beta", "b_wip", "b_end", "베타 종료"),
            )

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            migrateTo(postgres.jdbcUrl, "200")
            createIssueTypes(postgres.jdbcUrl)
            migrateTo(postgres.jdbcUrl, "203")
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                seedFixture(conn)
            }
            migrateToLatest(postgres.jdbcUrl)
        }

        /** Flyway 를 지정 버전까지 적용한다. project-workflow V201 이 issue-tracking 테이블을 참조해 두 경로를 함께 건다. */
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

        /** workflows + workflow_states + workflow_transitions 픽스처를 심는다. V204·V207 백필의 원본이다. */
        @JvmStatic
        fun seedFixture(conn: Connection) {
            for (workflowKey in fixtureStates.map { it.workflowKey }.distinct()) {
                conn.prepareStatement(
                    // V205 이전 시점이라 deleted_at 컬럼이 아직 없다 — 부분 유니크 술어를 붙이지 않는다.
                    "INSERT INTO workflows (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
                ).use { stmt ->
                    stmt.setString(1, workflowKey)
                    stmt.setString(2, workflowKey)
                    stmt.executeUpdate()
                }
            }
            for (state in fixtureStates) {
                conn.prepareStatement(
                    "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) " +
                        "SELECT id, ?, ?, ?, ? FROM workflows WHERE key = ?",
                ).use { stmt ->
                    stmt.setString(1, state.stateKey)
                    stmt.setString(2, state.name)
                    stmt.setString(3, state.category)
                    stmt.setInt(4, state.displayOrder)
                    stmt.setString(5, state.workflowKey)
                    stmt.executeUpdate()
                }
            }
            for (transition in fixtureTransitions) {
                conn.prepareStatement(
                    "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name) " +
                        "SELECT w.id, f.id, t.id, ? FROM workflows w " +
                        " JOIN workflow_states f ON f.workflow_id = w.id AND f.key = ? " +
                        " JOIN workflow_states t ON t.workflow_id = w.id AND t.key = ? " +
                        " WHERE w.key = ?",
                ).use { stmt ->
                    stmt.setString(1, transition.name)
                    stmt.setString(2, transition.fromStateKey)
                    stmt.setString(3, transition.toStateKey)
                    stmt.setString(4, transition.workflowKey)
                    stmt.executeUpdate()
                }
            }
        }
    }

    /** 상태 픽스처 1행. */
    data class StateRow(
        val workflowKey: String,
        val stateKey: String,
        val name: String,
        val category: String,
        val displayOrder: Int,
    )

    /** 전환 픽스처 1행. */
    data class TransitionRow(
        val workflowKey: String,
        val fromStateKey: String,
        val toStateKey: String,
        val name: String,
    )

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

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

    /** 결과 행마다 [read] 를 호출한다. JDBC 3단 try-with-resources 보일러플레이트를 한 곳에 모은다. */
    @Suppress("NestedBlockDepth")
    private fun <T> rows(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): List<T> {
        val result = mutableListOf<T>()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    while (rs.next()) result.add(read(rs))
                }
            }
        }
        return result
    }

    private fun tableExists(tableName: String): Boolean =
        query(
            "SELECT COUNT(*) FROM information_schema.tables" +
                " WHERE table_schema = 'public' AND table_name = '$tableName'",
        ) { it.getInt(1) > 0 }

    private fun columnExists(
        tableName: String,
        columnName: String,
    ): Boolean =
        query(
            "SELECT COUNT(*) FROM information_schema.columns" +
                " WHERE table_schema = 'public' AND table_name = '$tableName'" +
                " AND column_name = '$columnName'",
        ) { it.getInt(1) > 0 }

    /** 지정 테이블·컬럼에 걸린 **컬럼 레벨** UNIQUE 제약 수. 부분 유니크 인덱스는 여기 잡히지 않는다. */
    private fun uniqueConstraintCount(
        tableName: String,
        columnName: String,
    ): Int =
        query(
            "SELECT COUNT(*) FROM information_schema.table_constraints tc" +
                " JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name" +
                " WHERE tc.table_name = '$tableName' AND tc.constraint_type = 'UNIQUE'" +
                " AND kcu.column_name = '$columnName'",
        ) { it.getInt(1) }

    /**
     * SQL 을 트랜잭션 안에서 실행한 뒤 **반드시 롤백**한다.
     *
     * 성공 경로 검증이 메인 체인 DB 를 오염시키면 display_order·INITIAL 개수 검증이 실행 순서에
     * 따라 무너진다. 롤백으로 격리한다 — 컨테이너를 더 띄우지 않는다.
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

    /** INSERT 가 실패하기를 기대하고 그 메시지를 돌려준다. 성공하면 테스트를 실패시킨다. */
    private fun insertFailsWith(sql: String): String {
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
        error("INSERT 가 실패해야 하는데 성공했다: $sql")
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

    // ── V207. 다중 전환 (UNIQUE 해제) ─────────────────────────────────────────

    @Test
    fun `UNIQUE(workflow_id, from_state_id, to_state_id) 가 사라져 같은 상태쌍 2행이 들어간다`() {
        // 스키마 축 — 컬럼 레벨 UNIQUE 제약이 남아 있으면 아래 INSERT 가 성공해도 의미가 없다.
        assertThat(uniqueConstraintCount("workflow_transitions", "from_state_id")).isZero()
        assertThat(uniqueConstraintCount("workflow_transitions", "to_state_id")).isZero()

        // 동작 축 — 같은 (workflow, from, to) 에 이름만 다른 전환을 하나 더 넣는다.
        inRolledBackTransaction { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO workflow_transitions" +
                        " (workflow_id, from_state_id, to_state_id, from_status_id, to_status_id," +
                        "  name, kind, display_order)" +
                        " SELECT t.workflow_id, t.from_state_id, t.to_state_id," +
                        "        t.from_status_id, t.to_status_id, '알파 조건부 승인', 'NORMAL', 99" +
                        "   FROM workflow_transitions t JOIN workflows w ON w.id = t.workflow_id" +
                        "  WHERE w.key = 'wf-alpha' AND t.name = '알파 시작'",
                )
            }
            val sameEdge =
                countOf(
                    conn,
                    "SELECT COUNT(*) FROM workflow_transitions t" +
                        " JOIN workflows w ON w.id = t.workflow_id" +
                        " WHERE w.key = 'wf-alpha'" +
                        "   AND t.name IN ('알파 시작', '알파 조건부 승인')",
                )
            assertThat(sameEdge).isEqualTo(2)
        }
    }

    // ── V207. kind CHECK ──────────────────────────────────────────────────────

    /**
     * ### 왜 스키마 축이 먼저인가 — 동작 축만 두면 이 판별식이 공허해진다
     *
     * `ck_transition_kind_from` 은 「NORMAL 이면 from 있음 · GLOBAL/INITIAL 이면 from 없음」이라
     * **목록 밖의 kind 는 어느 분기도 만족시키지 못해 함께 거부한다.** 그래서 INSERT 실패만 보면
     * `ck_workflow_transitions_kind` 를 통째로 지워도 초록이다(실측 — 지운 상태에서 PostgreSQL 이
     * `ck_transition_kind_from` 위반으로 막았다). 허용 목록의 정본이 어느 제약인지는 정의를 직접 읽어야 한다.
     */
    @Test
    fun `kind CHECK 가 NORMAL GLOBAL INITIAL 만 허용한다`() {
        val definition =
            query(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint" +
                    " WHERE conrelid = 'workflow_transitions'::regclass" +
                    "   AND conname = 'ck_workflow_transitions_kind'",
            ) { it.getString(1) }
        assertThat(definition)
            .describedAs("허용 목록의 정본은 ck_workflow_transitions_kind 다")
            .contains("'NORMAL'")
            .contains("'GLOBAL'")
            .contains("'INITIAL'")

        // 동작 축 — 목록 밖의 값은 실제로 거부된다.
        val failure =
            insertFailsWith(
                "INSERT INTO workflow_transitions" +
                    " (workflow_id, from_status_id, to_status_id, name, kind, display_order)" +
                    " SELECT t.workflow_id, t.from_status_id, t.to_status_id, '알파 잘못된 종류', 'WEIRD', 99" +
                    "   FROM workflow_transitions t JOIN workflows w ON w.id = t.workflow_id" +
                    "  WHERE w.key = 'wf-alpha' AND t.name = '알파 시작'",
            )
        assertThat(failure).contains("violates check constraint")

        // 허용 3종이 전부 실제로 저장된다 — CHECK 가 다 막으면 위 단언이 공허해진다.
        // INITIAL 은 ⑨ 백필이 이미 심어 두었으므로 여기서는 NORMAL·GLOBAL 을 더해 3종을 채운다.
        inRolledBackTransaction { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO workflow_transitions" +
                        " (workflow_id, to_status_id, name, kind, display_order)" +
                        " SELECT t.workflow_id, t.to_status_id, '알파 어디서든 완료', 'GLOBAL', 98" +
                        "   FROM workflow_transitions t JOIN workflows w ON w.id = t.workflow_id" +
                        "  WHERE w.key = 'wf-alpha' AND t.name = '알파 시작'",
                )
                stmt.execute(
                    "INSERT INTO workflow_transitions" +
                        " (workflow_id, from_status_id, to_status_id, name, kind, display_order)" +
                        " SELECT t.workflow_id, t.from_status_id, t.to_status_id," +
                        "        '알파 또 다른 보통 전환', 'NORMAL', 99" +
                        "   FROM workflow_transitions t JOIN workflows w ON w.id = t.workflow_id" +
                        "  WHERE w.key = 'wf-alpha' AND t.name = '알파 시작'",
                )
            }
            val distinctKinds = countOf(conn, "SELECT COUNT(DISTINCT kind) FROM workflow_transitions")
            assertThat(distinctKinds)
                .describedAs("NORMAL·GLOBAL·INITIAL 3종이 모두 저장돼야 한다")
                .isEqualTo(3)
        }
    }

    @Test
    fun `INITIAL 은 워크플로우당 1개 — 2건째 INSERT 가 유니크 위반`() {
        val failure =
            insertFailsWith(
                "INSERT INTO workflow_transitions" +
                    " (workflow_id, to_status_id, name, kind, display_order)" +
                    " SELECT t.workflow_id, t.to_status_id, '알파 두 번째 최초 전환', 'INITIAL', 0" +
                    "   FROM workflow_transitions t JOIN workflows w ON w.id = t.workflow_id" +
                    "  WHERE w.key = 'wf-alpha' AND t.kind = 'INITIAL'",
            )
        assertThat(failure).contains("uq_workflow_transitions_initial")
    }

    @Test
    fun `kind=NORMAL 인데 from_status_id 가 NULL 이면 CHECK 위반`() {
        val failure =
            insertFailsWith(
                "INSERT INTO workflow_transitions" +
                    " (workflow_id, to_status_id, name, kind, display_order)" +
                    " SELECT t.workflow_id, t.to_status_id, '알파 출발지 없는 보통 전환', 'NORMAL', 99" +
                    "   FROM workflow_transitions t JOIN workflows w ON w.id = t.workflow_id" +
                    "  WHERE w.key = 'wf-alpha' AND t.name = '알파 시작'",
            )
        assertThat(failure).contains("ck_transition_kind_from")

        // 반대 방향 — GLOBAL 인데 출발지가 있으면 같은 CHECK 가 막는다.
        val reverse =
            insertFailsWith(
                "INSERT INTO workflow_transitions" +
                    " (workflow_id, from_status_id, to_status_id, name, kind, display_order)" +
                    " SELECT t.workflow_id, t.from_status_id, t.to_status_id, '알파 출발지 있는 전역 전환'," +
                    "        'GLOBAL', 99" +
                    "   FROM workflow_transitions t JOIN workflows w ON w.id = t.workflow_id" +
                    "  WHERE w.key = 'wf-alpha' AND t.name = '알파 시작'",
            )
        assertThat(reverse).contains("ck_transition_kind_from")
    }

    // ── V207. INITIAL 백필 ────────────────────────────────────────────────────

    /**
     * 백필 도착지가 `WorkflowKeyResolverImpl` 의 `minByOrNull { it.displayOrder }` 와 같은가.
     *
     * `wf-beta` 는 삽입 순서(b_todo · b_wip · b_end)와 display_order(5 · 2 · 9)가 어긋나 있다.
     * 백필이 순서를 안 보고 첫 행을 고르면 `b_todo` 가 나와 red 가 난다.
     */
    @Test
    fun `INITIAL 백필 도착지가 백필 전 display_order 최소 상태와 같다`() {
        val destinations =
            rows(
                "SELECT w.key, s.key FROM workflow_transitions t" +
                    " JOIN workflows w ON w.id = t.workflow_id" +
                    " JOIN workflow_statuses ws ON ws.id = t.to_status_id" +
                    " JOIN statuses s ON s.id = ws.status_id" +
                    " WHERE t.kind = 'INITIAL'",
            ) { it.getString(1) to it.getString(2) }

        assertThat(destinations).containsExactlyInAnyOrder(
            "wf-alpha" to "a_open",
            "wf-beta" to "b_wip",
        )
    }

    // ── V207. 구 컬럼 보존 (N4 — add → backfill → drop 3단 분할의 1·2단만 한다) ──

    @Test
    fun `from_state_id·to_state_id 와 workflow_states 는 살아 있다`() {
        assertThat(tableExists("workflow_states")).isTrue()
        assertThat(columnExists("workflow_transitions", "from_state_id")).isTrue()
        assertThat(columnExists("workflow_transitions", "to_state_id")).isTrue()

        // 두 세대가 나란히 산다 — 기존 전환은 구 FK 를 그대로 들고, 새 카탈로그 FK 가 같은 상태를 가리킨다.
        val parity =
            rows(
                "SELECT t.name, old_from.key, new_from.key, old_to.key, new_to.key" +
                    "  FROM workflow_transitions t" +
                    "  JOIN workflow_states old_from ON old_from.id = t.from_state_id" +
                    "  JOIN workflow_states old_to   ON old_to.id   = t.to_state_id" +
                    "  JOIN workflow_statuses wsf ON wsf.id = t.from_status_id" +
                    "  JOIN statuses new_from ON new_from.id = wsf.status_id" +
                    "  JOIN workflow_statuses wst ON wst.id = t.to_status_id" +
                    "  JOIN statuses new_to ON new_to.id = wst.status_id" +
                    " WHERE t.kind = 'NORMAL'",
            ) {
                listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5))
            }

        assertThat(parity).hasSize(fixtureTransitions.size)
        assertThat(parity).allSatisfy { row ->
            assertThat(row[2]).describedAs("전환 '%s' 의 from 재지정이 어긋났다", row[0]).isEqualTo(row[1])
            assertThat(row[4]).describedAs("전환 '%s' 의 to 재지정이 어긋났다", row[0]).isEqualTo(row[3])
        }
    }

    // ── V207. display_order 백필 (리뷰 T2 — GAP-1) ────────────────────────────

    /**
     * `NOT NULL DEFAULT 0` 만 두면 기존 전환이 전부 0 이 되어 편집기가 임의 순서로 그린다.
     * 화면이 없는 지금은 안 보이는 조용한 실패라 여기서 못을 박는다.
     */
    @Test
    fun `display_order 가 워크플로우 안에서 1..n 로 중복 없이 채워진다`() {
        val ordersByWorkflow =
            rows(
                "SELECT w.key, t.kind, t.display_order FROM workflow_transitions t" +
                    " JOIN workflows w ON w.id = t.workflow_id",
            ) { Triple(it.getString(1), it.getString(2), it.getInt(3)) }
                .groupBy { it.first }

        assertThat(ordersByWorkflow.keys).containsExactlyInAnyOrder("wf-alpha", "wf-beta")

        for ((workflowKey, entries) in ordersByWorkflow) {
            val normalOrders = entries.filter { it.second == "NORMAL" }.map { it.third }.sorted()
            assertThat(normalOrders)
                .describedAs("워크플로우 '%s' 의 기존 전환 display_order 가 1..n 이 아니다", workflowKey)
                .isEqualTo((1..normalOrders.size).toList())

            // INITIAL 은 생성 시점의 전환이라 항상 맨 앞(0)이다. 전체가 중복 없이 유일해야 한다.
            val initialOrders = entries.filter { it.second == "INITIAL" }.map { it.third }
            assertThat(initialOrders)
                .describedAs("워크플로우 '%s' 의 INITIAL 은 1건이고 display_order 0 이다", workflowKey)
                .isEqualTo(listOf(0))
            assertThat(entries.map { it.third })
                .describedAs("워크플로우 '%s' 안에서 display_order 가 중복됐다", workflowKey)
                .doesNotHaveDuplicates()
        }
    }
}
