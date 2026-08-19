// V203~V205 (전역 상태 카탈로그) 마이그레이션 검증 — 컨테이너 1개로 스키마·백필·컬럼을 한 번에 확인한다

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
 * Flyway V203(전역 상태 카탈로그 신설) · V204(백필 + 유일성 가드) · V205(workflows 컬럼 4종) 검증.
 *
 * ### 왜 한 클래스인가
 * `V200MigrationTest` 는 `@Container @JvmStatic` 이라 **클래스당 컨테이너 1개**를 띄운다. V203·V204·V205 를
 * 각각 클래스로 나누면 컨테이너가 3~4번 뜨는데, 착수 시점 러너 실측이 load 3.90배였다(게이트 1 리뷰 R4).
 * 세 마이그레이션은 어차피 한 체인으로만 의미가 있으므로 컨테이너 1개에서 순서대로 적용한다.
 *
 * ### 적용 순서 (load-bearing)
 * ```
 * ① target 200  issue-tracking V001~ + project-workflow V200
 * ② issue_types 수동 생성        V201 의 cross-BC FK 대상 (V200MigrationTest 와 동일 패턴)
 * ③ target 203  V201 · V202 · V203
 * ④ 픽스처 주입  시드 4종 모양의 workflow_states 17행     ← V204 가 백필할 원본
 * ⑤ target latest  V204(백필) · V205(컬럼)
 * ```
 * ④ 가 ③ 과 ⑤ 사이에 있어야 한다. 빈 DB 에 V204 를 적용하면 백필이 0행이라 아무것도 검증하지 못한다.
 *
 * ### 가드 테스트는 별도 데이터베이스에서 돈다
 * V204 의 `RAISE EXCEPTION` 3종은 **실패하는 마이그레이션**이라 위 체인을 오염시킨다. 같은 컨테이너 안에
 * `CREATE DATABASE` 로 격리된 DB 를 만들어 그 안에서만 위반 데이터를 심는다. 컨테이너 추가 기동 0.
 *
 * 이미지는 `quay.io/tembo/pg16-pgmq:latest` — V004 가 pgmq 확장을 요구한다
 * (ADR 2026-05-22-pgmq-postgres-image · `V200MigrationTest` 와 동일).
 *
 * 참조. FR-WF-04 · ADR 2026-08-18-workflow-global-status-catalog ·
 * `docs/plans/2026-08-19-migration-project-workflow-global-status-catalog.md`
 */
@Testcontainers
class V203ToV205MigrationTest {
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

        /** 시드 4종 YAML 의 상태 목록 실측값 — (워크플로우 키, 상태 키, 이름, 카테고리, 표시순서). */
        @JvmStatic
        val seedStates: List<Quintuple> =
            listOf(
                Quintuple("software-default", "open", "Open", "TODO", 1),
                Quintuple("software-default", "in_progress", "In Progress", "IN_PROGRESS", 2),
                Quintuple("software-default", "in_review", "In Review", "IN_PROGRESS", 3),
                Quintuple("software-default", "done", "Done", "DONE", 4),
                Quintuple("software-default", "closed", "Closed", "DONE", 5),
                Quintuple("bug-tracking", "reported", "Reported", "TODO", 1),
                Quintuple("bug-tracking", "triaged", "Triaged", "TODO", 2),
                Quintuple("bug-tracking", "in_progress", "In Progress", "IN_PROGRESS", 3),
                Quintuple("bug-tracking", "resolved", "Resolved", "DONE", 4),
                Quintuple("bug-tracking", "closed", "Closed", "DONE", 5),
                Quintuple("simple", "todo", "To Do", "TODO", 1),
                Quintuple("simple", "doing", "Doing", "IN_PROGRESS", 2),
                Quintuple("simple", "done", "Done", "DONE", 3),
                Quintuple("kanban-basic", "backlog", "Backlog", "TODO", 1),
                Quintuple("kanban-basic", "ready", "Ready", "TODO", 2),
                Quintuple("kanban-basic", "in_progress", "In Progress", "IN_PROGRESS", 3),
                Quintuple("kanban-basic", "done", "Done", "DONE", 4),
            )

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            migrateTo(postgres.jdbcUrl, "200")
            createIssueTypes(postgres.jdbcUrl)
            migrateTo(postgres.jdbcUrl, "203")
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                seedWorkflowStates(conn, seedStates)
            }
            migrateToLatest(postgres.jdbcUrl)
        }

        /** Flyway 를 지정 버전까지 적용한다. project-workflow V201 이 issue-tracking 테이블을 참조해 두 경로를 함께 건다. */
        @JvmStatic
        fun migrateTo(
            jdbcUrl: String,
            target: String,
        ) {
            Flyway.configure()
                .dataSource(jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .target(target)
                .load()
                .migrate()
        }

        /** 남은 마이그레이션을 전부 적용한다. `target` 을 숫자로 고정하면 그 버전이 생기기 전 단계에서 클래스가 통째로 죽는다. */
        @JvmStatic
        fun migrateToLatest(jdbcUrl: String) {
            Flyway.configure()
                .dataSource(jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .load()
                .migrate()
        }

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

        /**
         * 격리 DB 를 만들어 V203 까지 적용 → 픽스처 주입 → 남은 마이그레이션 적용까지 **성공**시키고 URL 을 준다.
         *
         * 메인 체인의 픽스처(시드 4종 17행)는 백필 기대값 12/17 을 고정하고 있어 여기에 행을 더할 수 없다.
         * 「표준이 아닌 워크플로우는 origin 이 CUSTOM 으로 남는가」처럼 다른 픽스처가 필요한 검증은 DB 를 가른다.
         */
        @JvmStatic
        fun migrateWithFixture(
            dbName: String,
            fixture: (Connection) -> Unit,
        ): String {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { it.execute("CREATE DATABASE $dbName") }
            }
            val url = postgres.jdbcUrl.replace("/${postgres.databaseName}", "/$dbName")
            migrateTo(url, "200")
            createIssueTypes(url)
            migrateTo(url, "203")
            DriverManager.getConnection(url, postgres.username, postgres.password).use { conn ->
                fixture(conn)
            }
            migrateToLatest(url)
            return url
        }

        /**
         * 같은 컨테이너 안에 격리 DB 를 만들고 V203 까지 적용한 뒤 위반 픽스처를 심고 남은 마이그레이션을 돌린다.
         *
         * V204 의 `RAISE EXCEPTION` 은 **마이그레이션을 실패시키는** 검증이라 메인 체인을 오염시킨다.
         * 컨테이너를 더 띄우지 않고 DB 만 갈라 격리한다.
         *
         * @return 마이그레이션이 실패하며 남긴 메시지 전문. 성공하면 테스트를 실패시킨다.
         */
        @JvmStatic
        fun migrationFailureMessage(
            dbName: String,
            violatingFixture: (Connection) -> Unit,
        ): String {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { it.execute("CREATE DATABASE $dbName") }
            }
            val url = postgres.jdbcUrl.replace("/${postgres.databaseName}", "/$dbName")
            migrateTo(url, "200")
            createIssueTypes(url)
            migrateTo(url, "203")
            DriverManager.getConnection(url, postgres.username, postgres.password).use { conn ->
                violatingFixture(conn)
            }
            try {
                migrateToLatest(url)
            } catch (ex: org.flywaydb.core.api.FlywayException) {
                return generateSequence<Throwable>(ex) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString(" | ")
            }
            error("V204 가 위반 데이터를 막아야 하는데 마이그레이션이 성공했다: $dbName")
        }

        /** workflow_states 1행을 임의 값으로 심는다. 가드 위반 픽스처 전용. */
        @JvmStatic
        fun insertState(
            conn: Connection,
            workflowKey: String,
            stateKey: String,
            name: String,
            category: String,
        ) {
            conn.prepareStatement(
                "INSERT INTO workflows (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, workflowKey)
                stmt.setString(2, workflowKey)
                stmt.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) " +
                    "SELECT id, ?, ?, ?, 1 FROM workflows WHERE key = ?",
            ).use { stmt ->
                stmt.setString(1, stateKey)
                stmt.setString(2, name)
                stmt.setString(3, category)
                stmt.setString(4, workflowKey)
                stmt.executeUpdate()
            }
        }

        /** workflows + workflow_states 픽스처를 심는다. V204 백필의 원본이다. */
        @JvmStatic
        fun seedWorkflowStates(
            conn: Connection,
            states: List<Quintuple>,
        ) {
            for (workflowKey in states.map { it.workflowKey }.distinct()) {
                conn.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
                ).use { stmt ->
                    stmt.setString(1, workflowKey)
                    stmt.setString(2, workflowKey)
                    stmt.executeUpdate()
                }
            }
            for (state in states) {
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
        }
    }

    /** 시드 상태 픽스처 1행. Kotlin 표준 Triple 로는 부족해 5-튜플을 둔다. */
    data class Quintuple(
        val workflowKey: String,
        val stateKey: String,
        val name: String,
        val category: String,
        val displayOrder: Int,
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

    private fun tableExists(tableName: String): Boolean =
        query(
            "SELECT COUNT(*) FROM information_schema.tables" +
                " WHERE table_schema = 'public' AND table_name = '$tableName'",
        ) { it.getInt(1) > 0 }

    private fun indexExists(indexName: String): Boolean =
        query(
            "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = '$indexName'",
        ) { it.getInt(1) > 0 }

    private fun columnDataType(
        tableName: String,
        columnName: String,
    ): String? =
        query(
            "SELECT data_type FROM information_schema.columns" +
                " WHERE table_schema = 'public' AND table_name = '$tableName'" +
                " AND column_name = '$columnName'",
        ) { it.getString(1) }

    /** FK 의 삭제 규칙(CASCADE / RESTRICT / NO ACTION)을 pg_constraint 에서 읽는다. */
    private fun foreignKeyDeleteRule(
        tableName: String,
        columnName: String,
    ): String? =
        query(
            """
            SELECT rc.delete_rule
              FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
              JOIN information_schema.referential_constraints rc
                ON tc.constraint_name = rc.constraint_name
             WHERE tc.table_name = '$tableName'
               AND tc.constraint_type = 'FOREIGN KEY'
               AND kcu.column_name = '$columnName'
            """.trimIndent(),
        ) { it.getString(1) }

    // ── V203. 전역 상태 카탈로그 2 테이블 ─────────────────────────────────────

    @Test
    fun `V203 statuses 테이블 존재`() {
        assertThat(tableExists("statuses")).isTrue()
    }

    @Test
    fun `V203 workflow_statuses 테이블 존재`() {
        assertThat(tableExists("workflow_statuses")).isTrue()
    }

    @Test
    fun `V203 statuses key 는 전역 UNIQUE`() {
        val count =
            query(
                "SELECT COUNT(*) FROM information_schema.table_constraints tc" +
                    " JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name" +
                    " WHERE tc.table_name = 'statuses' AND tc.constraint_type = 'UNIQUE'" +
                    " AND kcu.column_name = 'key'",
            ) { it.getInt(1) }
        assertThat(count).isGreaterThan(0)
    }

    @Test
    fun `V203 statuses 는 lower(name) 부분 UNIQUE 인덱스를 갖는다`() {
        assertThat(indexExists("uq_statuses_lower_name")).isTrue()
        val definition =
            query(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_statuses_lower_name'",
            ) { it.getString(1) }
        assertThat(definition).contains("UNIQUE")
        // name 이 VARCHAR 라 PostgreSQL 이 lower((name)::text) 로 정규화해 저장한다.
        assertThat(definition).contains("lower((name)::text)")
        assertThat(definition).contains("deleted_at IS NULL")
    }

    @Test
    fun `V203 statuses category 는 3종 CHECK 로 제한된다`() {
        assertThat(insertFailsWith("INSERT INTO statuses (key, name, category) VALUES ('x_bad', 'X Bad', 'NOPE')"))
            .contains("statuses_category_check")
    }

    @Test
    fun `V203 workflow_statuses workflow_id 는 CASCADE 다`() {
        assertThat(foreignKeyDeleteRule("workflow_statuses", "workflow_id")).isEqualTo("CASCADE")
    }

    @Test
    fun `V203 workflow_statuses status_id 는 RESTRICT 다`() {
        assertThat(foreignKeyDeleteRule("workflow_statuses", "status_id")).isEqualTo("RESTRICT")
    }

    @Test
    fun `V203 workflow_statuses 는 (workflow_id, status_id) UNIQUE 다`() {
        val count =
            query(
                "SELECT COUNT(*) FROM information_schema.table_constraints tc" +
                    " JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name" +
                    " WHERE tc.table_name = 'workflow_statuses' AND tc.constraint_type = 'UNIQUE'",
            ) { it.getInt(1) }
        assertThat(count).isEqualTo(2)
    }

    @Test
    fun `V203 FK 인덱스 2개가 있다 (DATA_md 7)`() {
        assertThat(indexExists("idx_workflow_statuses_workflow")).isTrue()
        assertThat(indexExists("idx_workflow_statuses_status")).isTrue()
    }

    @Test
    fun `V203 타임스탬프는 전부 TIMESTAMPTZ 다 (DATA_md 4)`() {
        assertThat(columnDataType("statuses", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnDataType("statuses", "updated_at")).isEqualTo("timestamp with time zone")
        assertThat(columnDataType("statuses", "deleted_at")).isEqualTo("timestamp with time zone")
        assertThat(columnDataType("workflow_statuses", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnDataType("workflow_statuses", "updated_at")).isEqualTo("timestamp with time zone")
    }

    // ── V204. 백필 ────────────────────────────────────────────────────────────

    @Test
    fun `V204 백필이 statuses 12행을 만든다`() {
        val count = query("SELECT COUNT(*) FROM statuses") { it.getInt(1) }
        assertThat(count).isEqualTo(seedStates.map { it.stateKey }.distinct().size)
        assertThat(count).isEqualTo(12)
    }

    @Test
    fun `V204 백필이 workflow_statuses 17행을 만든다`() {
        val count = query("SELECT COUNT(*) FROM workflow_statuses") { it.getInt(1) }
        assertThat(count).isEqualTo(seedStates.size)
        assertThat(count).isEqualTo(17)
    }

    @Test
    fun `V204 백필이 키별 이름과 카테고리를 그대로 옮긴다`() {
        val expected = seedStates.associate { it.stateKey to (it.name to it.category) }
        val actual = mutableMapOf<String, Pair<String, String>>()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT key, name, category FROM statuses")
            while (rs.next()) {
                actual[rs.getString(1)] = rs.getString(2) to rs.getString(3)
            }
        }
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `V204 백필이 워크플로우별 display_order 를 보존한다`() {
        val expected = seedStates.associate { (it.workflowKey to it.stateKey) to it.displayOrder }
        val actual = mutableMapOf<Pair<String, String>, Int>()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val rs =
                conn.createStatement().executeQuery(
                    "SELECT w.key, s.key, ws.display_order FROM workflow_statuses ws" +
                        " JOIN workflows w ON w.id = ws.workflow_id" +
                        " JOIN statuses s ON s.id = ws.status_id",
                )
            while (rs.next()) {
                actual[rs.getString(1) to rs.getString(2)] = rs.getInt(3)
            }
        }
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `V204 백필은 원본 workflow_states 를 지우지 않는다`() {
        val count = query("SELECT COUNT(*) FROM workflow_states") { it.getInt(1) }
        assertThat(count).isEqualTo(seedStates.size)
    }

    // ── V204. 유일성 가드 3종 (격리 DB — ADR D4 는 위반 데이터로 red 1회를 요구한다) ──

    @Test
    fun `V204 는 같은 키에 다른 이름 카테고리가 있으면 마이그레이션을 실패시킨다`() {
        val message =
            migrationFailureMessage("guard_dup_key") { conn ->
                insertState(conn, "wf-a", "in_progress", "In Progress", "IN_PROGRESS")
                insertState(conn, "wf-b", "in_progress", "진행중", "TODO")
            }
        assertThat(message).contains("in_progress")
        assertThat(message).contains("상태 키")
    }

    @Test
    fun `V204 는 다른 키가 같은 이름을 쓰면 마이그레이션을 실패시킨다`() {
        val message =
            migrationFailureMessage("guard_dup_name") { conn ->
                insertState(conn, "wf-a", "done", "Done", "DONE")
                insertState(conn, "wf-b", "complete", "done", "DONE")
            }
        assertThat(message).contains("done")
        assertThat(message).contains("이름")
    }

    @Test
    fun `V204 는 키가 50자를 넘으면 마이그레이션을 실패시킨다`() {
        val longKey = "x".repeat(51)
        val message =
            migrationFailureMessage("guard_long_key") { conn ->
                insertState(conn, "wf-a", longKey, "Long Key", "TODO")
            }
        assertThat(message).contains("50")
    }

    // ── V205. workflows 컬럼 4종 + origin 표기 ────────────────────────────────

    @Test
    fun `V205 workflows 에 컬럼 4종이 생긴다`() {
        assertThat(columnDataType("workflows", "version")).isEqualTo("bigint")
        assertThat(columnDataType("workflows", "origin")).isEqualTo("text")
        assertThat(columnDataType("workflows", "deleted_at")).isEqualTo("timestamp with time zone")
        assertThat(columnDataType("workflows", "is_locked")).isEqualTo("boolean")
    }

    @Test
    fun `V205 새 워크플로우는 version 0 · origin CUSTOM · is_locked false 로 시작한다`() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use {
                it.execute("INSERT INTO workflows (key, name) VALUES ('default-probe', 'default probe')")
            }
        }
        val row =
            query(
                "SELECT version, origin, is_locked, deleted_at FROM workflows WHERE key = 'default-probe'",
            ) { listOf(it.getLong(1).toString(), it.getString(2), it.getBoolean(3).toString(), it.getString(4)) }
        assertThat(row).containsExactly("0", "CUSTOM", "false", null)
    }

    @Test
    fun `V205 origin 은 SEED 와 CUSTOM 만 허용한다`() {
        assertThat(insertFailsWith("INSERT INTO workflows (key, name, origin) VALUES ('bad-origin', 'x', 'OTHER')"))
            .contains("ck_workflows_origin")
    }

    @Test
    fun `V205 는 표준 4키의 origin 만 SEED 로 표기한다`() {
        val url =
            migrateWithFixture("origin_scope") { conn ->
                insertState(conn, "software-default", "open", "Open", "TODO")
                insertState(conn, "our-custom-workflow", "open2", "Open Two", "TODO")
            }
        val origins = mutableMapOf<String, String>()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT key, origin FROM workflows")
            while (rs.next()) origins[rs.getString(1)] = rs.getString(2)
        }
        assertThat(origins["software-default"]).isEqualTo("SEED")
        assertThat(origins["our-custom-workflow"]).isEqualTo("CUSTOM")
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
}
