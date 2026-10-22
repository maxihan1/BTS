// V212(전환 화면 필드) 마이그레이션 검증 — DONE 도착 전환에만 「해결 결과 필수」가 심기는지 발행본 · 초안 양쪽에서 잰다

package com.bts.workflow.db

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Flyway V212(`transition_screen_fields`) 검증.
 *
 * 값은 백필 조건에 있다 — 「DONE 범주 도착 · INITIAL 아님」. 그래서 V211 까지 올린 DB 에
 * INITIAL→DONE · NORMAL→DONE · GLOBAL→DONE · DONE→TODO 를 한 벌씩 심고(발행본 + 같은 모양의 초안 JSON)
 * V212 를 올린 뒤 어느 전환이 화면을 받았는지를 잰다. 조건을 지우거나 뒤집으면 여기서 빨개진다.
 *
 * 적용 순서는 [V209MigrationTest] 와 같다(① target 200 ② issue_types 수동 생성 ③ target 211 → 픽스처 → latest).
 *
 * 참조. FR-WF-01 D10 · `docs/specs/2026-10-20-workflow-jira-parity-gap.md` G11
 */
@Testcontainers
class V212MigrationTest {
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
            flyway().target("200").load().migrate()
            exec(ISSUE_TYPES_DDL)
            flyway().target("211").load().migrate()
            seedFixture()
            flyway().load().migrate()
        }

        @JvmStatic
        private fun flyway() =
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )

        /** V201 의 cross-BC FK 대상 — issue-tracking 마이그레이션이 만들지 않아 테스트가 만든다([V209MigrationTest] 와 같다). */
        private const val ISSUE_TYPES_DDL =
            "CREATE TABLE IF NOT EXISTS issue_types (" +
                "id BIGSERIAL PRIMARY KEY, key VARCHAR(30) NOT NULL UNIQUE, " +
                "name VARCHAR(255) NOT NULL, is_standard BOOLEAN NOT NULL DEFAULT FALSE, " +
                "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), " +
                "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)"

        private val DRAFT_JSON =
            """
            {"key":"v212","name":"V212","states":[
              {"key":"v212_todo","name":"할 일","category":"TODO","displayOrder":1},
              {"key":"v212_done","name":"완료","category":"DONE","displayOrder":2}],
             "transitions":[
              {"to":"v212_done","name":"생성","kind":"INITIAL"},
              {"from":"v212_todo","to":"v212_done","name":"Complete","kind":"NORMAL"},
              {"to":"v212_done","name":"Close","kind":"GLOBAL"},
              {"from":"v212_done","to":"v212_todo","name":"Reopen","kind":"NORMAL"},
              {"from":"v212_todo","to":"v212_done","name":"NoKind"}]}
            """.trimIndent()

        /** 전환이 빈 초안 — `jsonb_agg` 가 NULL 을 내는 자리. 막지 않으면 NOT NULL 위반으로 Flyway 가 멈춘다. */
        private const val EMPTY_DRAFT_JSON = """{"key":"v212_empty","name":"빈","states":[],"transitions":[]}"""

        @JvmStatic
        private fun seedFixture() {
            exec(
                "INSERT INTO statuses (key, name, category) VALUES " +
                    "('v212_todo', '할 일', 'TODO'), ('v212_done', '완료', 'DONE')",
                "INSERT INTO workflows (key, name) VALUES ('v212', 'V212')",
                "INSERT INTO workflow_statuses (workflow_id, status_id, display_order) " +
                    "SELECT w.id, s.id, 0 FROM workflows w, statuses s " +
                    "WHERE w.key = 'v212' AND s.key IN ('v212_todo', 'v212_done')",
                transitionSql("생성", "INITIAL", null, "v212_done"),
                transitionSql("Complete", "NORMAL", "v212_todo", "v212_done"),
                transitionSql("Close", "GLOBAL", null, "v212_done"),
                transitionSql("Reopen", "NORMAL", "v212_done", "v212_todo"),
                "INSERT INTO workflow_drafts (workflow_id, definition, base_version) " +
                    "SELECT id, '$DRAFT_JSON'::jsonb, 0 FROM workflows WHERE key = 'v212'",
                "INSERT INTO workflows (key, name) VALUES ('v212_empty', '빈')",
                "INSERT INTO workflow_drafts (workflow_id, definition, base_version) " +
                    "SELECT id, '$EMPTY_DRAFT_JSON'::jsonb, 0 FROM workflows WHERE key = 'v212_empty'",
            )
        }

        private fun transitionSql(
            name: String,
            kind: String,
            from: String?,
            to: String,
        ): String {
            val composition = { key: String ->
                "(SELECT ws.id FROM workflow_statuses ws JOIN statuses s ON s.id = ws.status_id " +
                    "JOIN workflows w ON w.id = ws.workflow_id WHERE w.key = 'v212' AND s.key = '$key')"
            }
            return "INSERT INTO workflow_transitions (workflow_id, kind, name, from_status_id, to_status_id) " +
                "SELECT id, '$kind', '$name', ${from?.let(composition) ?: "NULL"}, ${composition(to)} " +
                "FROM workflows WHERE key = 'v212'"
        }

        private fun exec(vararg sqls: String) {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt -> sqls.forEach { stmt.execute(it) } }
            }
        }

        private fun strings(sql: String): List<String> =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
            }
    }

    @Test
    fun `발행본 — DONE 으로 들어가는 NORMAL · GLOBAL 전환만 해결 결과 필수를 받는다`() {
        val screened =
            strings(
                "SELECT t.name || ':' || f.field || ':' || f.required " +
                    "FROM workflow_transition_screen_fields f JOIN workflow_transitions t ON t.id = f.transition_id " +
                    "JOIN workflows w ON w.id = t.workflow_id WHERE w.key = 'v212' ORDER BY t.name",
            )

        assertThat(screened).containsExactly("Close:resolution:true", "Complete:resolution:true")
    }

    @Test
    fun `초안 — 같은 규칙으로 JSON 의 전환에 screen 이 붙고 순서는 그대로다`() {
        val transitions =
            strings(
                "SELECT t ->> 'name' || '=' || COALESCE(t -> 'screen', 'null'::jsonb)::text " +
                    "FROM workflow_drafts d JOIN workflows w ON w.id = d.workflow_id, " +
                    "jsonb_array_elements(d.definition -> 'transitions') WITH ORDINALITY x(t, ord) " +
                    "WHERE w.key = 'v212' ORDER BY ord",
            )

        assertThat(transitions).containsExactly(
            "생성=null",
            """Complete=[{"field": "resolution", "required": true}]""",
            """Close=[{"field": "resolution", "required": true}]""",
            "Reopen=null",
            // kind 가 없는 옛 초안 전환은 NORMAL 로 읽힌다(DraftTransitionDto 기본값) — 같은 규칙을 받는다.
            """NoKind=[{"field": "resolution", "required": true}]""",
        )
    }

    @Test
    fun `전환이 빈 초안은 그대로 남는다 — 정의가 NULL 이 되지 않는다`() {
        assertThat(
            strings(
                "SELECT d.definition -> 'transitions' FROM workflow_drafts d " +
                    "JOIN workflows w ON w.id = d.workflow_id WHERE w.key = 'v212_empty'",
            ),
        ).containsExactly("[]")
    }

    @Test
    fun `모르는 필드는 DB 가 거절한다`() {
        assertThatThrownBy {
            exec(
                "INSERT INTO workflow_transition_screen_fields (transition_id, field) " +
                    "SELECT id, 'priority' FROM workflow_transitions WHERE name = 'Reopen'",
            )
        }.isInstanceOf(SQLException::class.java)
            .hasMessageContaining("check")
    }
}
