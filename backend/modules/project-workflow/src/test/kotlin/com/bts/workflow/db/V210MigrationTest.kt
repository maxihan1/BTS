// V210(workflow_states DROP) 마이그레이션 검증 — 구 컬럼만 찬 전환의 재백필 · 옮기지 못하면 배포 중단 · 이연 제약 2종

package com.bts.workflow.db

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Flyway V210(`drop_workflow_states`) 검증 — add → backfill → drop 의 3단.
 *
 * ### 적용 순서 (load-bearing)
 * ```
 * ① target 200    issue-tracking V001~ + project-workflow V200
 * ② issue_types 수동 생성   V201 의 cross-BC FK 대상
 * ③ target 209    V201 ~ V209
 * ④ 픽스처 주입    구 컬럼만 찬 전환 · 신 컬럼만 찬 전환 · INITIAL   ← V210 재백필의 원본
 * ⑤ target latest V210
 * ```
 * ④ 가 ③ 과 ⑤ 사이에 있어야 한다. 빈 DB 에 V210 을 적용하면 재백필이 0행이라 아무것도 검증하지 못한다.
 *
 * `V207MigrationTest` 가 「아직 안 건다」로 못박은 3개(to_status_id NULL 허용 · 구 컬럼만 채운 INSERT ·
 * kind/from CHECK 부재)의 뒤집힌 단언이 여기 있다.
 *
 * 이미지는 `quay.io/tembo/pg16-pgmq:latest` — issue-tracking V004 가 pgmq 확장을 요구한다.
 *
 * 참조. FR-WF-04 WF-04-4 · V207 ⑦·⑪
 */
@Testcontainers
class V210MigrationTest {
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
            migrateTo(postgres.jdbcUrl, "209")
            connect(postgres.jdbcUrl).use { conn -> seedFixture(conn) }
            flyway(postgres.jdbcUrl).load().migrate()
        }

        @JvmStatic
        fun migrateTo(
            jdbcUrl: String,
            target: String,
        ) {
            flyway(jdbcUrl).target(target).load().migrate()
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

        @JvmStatic
        fun connect(jdbcUrl: String): Connection =
            DriverManager.getConnection(jdbcUrl, postgres.username, postgres.password)

        /** V201 의 cross-BC FK 대상. issue-tracking 마이그레이션이 만들지 않는 경로라 테스트가 직접 만든다. */
        @JvmStatic
        fun createIssueTypes(jdbcUrl: String) {
            connect(jdbcUrl).use { conn ->
                conn.exec(
                    "CREATE TABLE IF NOT EXISTS issue_types (" +
                        "id BIGSERIAL PRIMARY KEY, key VARCHAR(30) NOT NULL UNIQUE, " +
                        "name VARCHAR(255) NOT NULL, is_standard BOOLEAN NOT NULL DEFAULT FALSE, " +
                        "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), " +
                        "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)",
                )
            }
        }

        /**
         * 워크플로우 `wf-x` 하나에 상태 2개(두 세대 모두) · 전환 3건.
         *
         * - 「구 컬럼만」 — V207 뒤 구 컬럼으로만 쓰던 경로가 남긴 모양. V210 ① 이 신 컬럼을 채워야 한다
         * - 「신 컬럼만」 — CRUD 가 만든 모양. 재백필이 건드리면 안 된다
         * - INITIAL — 출발지 없음
         */
        @JvmStatic
        fun seedFixture(conn: Connection) {
            conn.exec("INSERT INTO workflows (key, name) VALUES ('wf-x', 'wf-x')")
            seedState(conn, "wf-x", "x_open", "X Open", "TODO", 1)
            seedState(conn, "wf-x", "x_done", "X Done", "DONE", 2)
            conn.exec(
                "INSERT INTO workflow_transitions (workflow_id, name, from_state_id, to_state_id)" +
                    " SELECT w.id, '구 컬럼만', f.id, t.id FROM workflows w" +
                    " JOIN workflow_states f ON f.workflow_id = w.id AND f.key = 'x_open'" +
                    " JOIN workflow_states t ON t.workflow_id = w.id AND t.key = 'x_done'" +
                    " WHERE w.key = 'wf-x'",
            )
            conn.exec(
                "INSERT INTO workflow_transitions (workflow_id, name, from_status_id, to_status_id)" +
                    " SELECT w.id, '신 컬럼만', " + COMPOSITION_OF.format("x_done") + ", " +
                    COMPOSITION_OF.format("x_open") + " FROM workflows w WHERE w.key = 'wf-x'",
            )
            conn.exec(
                "INSERT INTO workflow_transitions (workflow_id, kind, name, to_status_id)" +
                    " SELECT w.id, 'INITIAL', '이슈 생성', " + COMPOSITION_OF.format("x_open") +
                    " FROM workflows w WHERE w.key = 'wf-x'",
            )
        }

        /** 상태 1개를 구형 `workflow_states` 와 전역 카탈로그 양쪽에 심는다. V209 시점의 정상 모양이다. */
        @Suppress("LongParameterList")
        @JvmStatic
        fun seedState(
            conn: Connection,
            workflowKey: String,
            key: String,
            name: String,
            category: String,
            order: Int,
        ) {
            conn.exec(
                "INSERT INTO workflow_states (workflow_id, key, name, category, display_order)" +
                    " SELECT id, '$key', '$name', '$category', $order FROM workflows WHERE key = '$workflowKey'",
            )
            conn.exec("INSERT INTO statuses (key, name, category) VALUES ('$key', '$name', '$category')")
            conn.exec(
                "INSERT INTO workflow_statuses (workflow_id, status_id, display_order)" +
                    " SELECT w.id, s.id, $order FROM workflows w, statuses s" +
                    " WHERE w.key = '$workflowKey' AND s.key = '$key'",
            )
        }

        /** 상태 key → 그 워크플로우(`w`)의 `workflow_statuses.id` 를 고르는 스칼라 서브쿼리. */
        private const val COMPOSITION_OF =
            "(SELECT ws.id FROM workflow_statuses ws JOIN statuses s ON s.id = ws.status_id" +
                " WHERE ws.workflow_id = w.id AND s.key = '%s')"

        private fun Connection.exec(sql: String) {
            createStatement().use { it.execute(sql) }
        }
    }

    // ── 3단 drop ─────────────────────────────────────────────────────────────

    @Test
    fun `workflow_states 와 전환의 구 컬럼이 사라진다`() {
        assertThat(count("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'workflow_states'"))
            .isZero()
        assertThat(
            count(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'workflow_transitions'" +
                    " AND column_name IN ('from_state_id', 'to_state_id')",
            ),
        ).isZero()
    }

    // ── ① 재백필 ─────────────────────────────────────────────────────────────

    @Test
    fun `구 컬럼만 찬 전환은 같은 상태의 workflow_statuses 로 다시 이어진다`() {
        assertThat(endpointKeys("구 컬럼만")).isEqualTo("x_open->x_done")
    }

    @Test
    fun `신 컬럼이 찬 전환은 재백필이 건드리지 않는다`() {
        assertThat(endpointKeys("신 컬럼만")).isEqualTo("x_done->x_open")
        assertThat(endpointKeys("이슈 생성")).isEqualTo("null->x_open")
    }

    // ── ⑤ 이연 제약 — V207MigrationTest 의 「아직 안 건다」 3개를 뒤집는다 ─────────

    @Test
    fun `to_status_id 는 NULL 을 허용하지 않는다`() {
        val sql =
            "INSERT INTO workflow_transitions (workflow_id, kind, name)" +
                " SELECT w.id, 'GLOBAL', '도착 없음'$WF_X_FROM"
        assertThat(insertFailsWith(sql)).contains("to_status_id")
    }

    @Test
    fun `NORMAL 인데 출발지가 없으면 거부된다`() {
        val sql =
            "INSERT INTO workflow_transitions (workflow_id, kind, name, to_status_id)" +
                " SELECT w.id, 'NORMAL', '출발 없음', " + COMPOSITION_OF.format("x_done") + WF_X_FROM
        assertThat(insertFailsWith(sql)).contains("ck_transition_kind_from")
    }

    @Test
    fun `GLOBAL 인데 출발지가 있으면 거부된다`() {
        val sql =
            "INSERT INTO workflow_transitions (workflow_id, kind, name, from_status_id, to_status_id)" +
                " SELECT w.id, 'GLOBAL', '출발 있음', " + COMPOSITION_OF.format("x_open") + ", " +
                COMPOSITION_OF.format("x_done") + WF_X_FROM
        assertThat(insertFailsWith(sql)).contains("ck_transition_kind_from")
    }

    // ── ② 가드 — 옮기지 못하면 배포를 멈춘다 (격리 DB) ───────────────────────────

    @Test
    fun `편성에서 빠진 상태를 가리키는 구 컬럼 전환이 있으면 마이그레이션이 이름을 들고 실패한다`() {
        connect(postgres.jdbcUrl).use { it.createStatement().use { s -> s.execute("CREATE DATABASE v210_guard") } }
        val url = postgres.jdbcUrl.replace("/${postgres.databaseName}", "/v210_guard")
        migrateTo(url, "200")
        createIssueTypes(url)
        migrateTo(url, "209")
        connect(url).use { conn ->
            seedFixture(conn)
            // 도착 상태를 편성에서 뺀다 — 구 컬럼은 여전히 가리키지만 신 컬럼으로 옮길 곳이 없다.
            conn.createStatement().use {
                it.execute(
                    "DELETE FROM workflow_transitions WHERE name <> '구 컬럼만';" +
                        " DELETE FROM workflow_statuses" +
                        " WHERE status_id = (SELECT id FROM statuses WHERE key = 'x_done')",
                )
            }
        }

        val message =
            try {
                flyway(url).load().migrate()
                error("V210 가드가 옮기지 못한 전환을 막아야 하는데 마이그레이션이 성공했다")
            } catch (ex: FlywayException) {
                generateSequence<Throwable>(ex) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            }

        assertThat(message).contains("V210").contains("구 컬럼만")
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun count(sql: String): Int =
        connect(postgres.jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** 전환 이름 → 「출발 key->도착 key」. 출발지가 없으면 `null` 이다. */
    private fun endpointKeys(transitionName: String): String =
        connect(postgres.jdbcUrl).use { conn ->
            conn.prepareStatement(
                "SELECT fs.key, ts.key FROM workflow_transitions t" +
                    " LEFT JOIN workflow_statuses fws ON fws.id = t.from_status_id" +
                    " LEFT JOIN statuses fs ON fs.id = fws.status_id" +
                    " JOIN workflow_statuses tws ON tws.id = t.to_status_id" +
                    " JOIN statuses ts ON ts.id = tws.status_id" +
                    " WHERE t.name = ?",
            ).use { stmt ->
                stmt.setString(1, transitionName)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "전환 '$transitionName' 이 없다" }
                    "${rs.getString(1)}->${rs.getString(2)}"
                }
            }
        }

    /** 실패해야 하는 INSERT 를 롤백 트랜잭션 안에서 실행하고 오류 메시지를 돌려준다. */
    private fun insertFailsWith(sql: String): String =
        connect(postgres.jdbcUrl).use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute(sql) }
                error("거부돼야 할 INSERT 가 성공했다: $sql")
            } catch (ex: SQLException) {
                ex.message.orEmpty()
            } finally {
                conn.rollback()
            }
        }
}

/** INSERT ... SELECT 의 FROM 절. `wf-x` 워크플로우를 `w` 로 잡아 [V210MigrationTest] 의 서브쿼리가 쓴다. */
private const val WF_X_FROM = " FROM workflows w WHERE w.key = 'wf-x'"
