// V213 마이그레이션 검증 — RequiredField(resolution) 검증기가 화면 「해결 결과 필수」로 옮겨지는지 발행본 · 초안 양쪽에서 잰다

package com.bts.workflow.db

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
 * Flyway V213(`resolution_validator_to_screen`) 검증.
 *
 * 값은 옮기는 조건에 있다 — 「`RequiredField` · field=resolution · INITIAL 아님」. V212 까지 올린 DB 에
 * INITIAL(검증기 남음) · DONE 도착(V212 화면이 이미 있음) · 비-DONE(화면에 담당자만 있음 + summary 검증기)을
 * 한 벌씩 심고(발행본 + 같은 모양의 초안 JSON) V213 을 올린 뒤 검증기와 화면을 잰다.
 *
 * 적용 순서는 [V212MigrationTest] 와 같다(① target 200 ② issue_types 수동 생성 ③ target 212 → 픽스처 → latest).
 *
 * 참조. FR-WF-01 D11 · `docs/specs/2026-10-20-workflow-jira-parity-gap.md` G16
 */
@Testcontainers
class V213MigrationTest {
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
            flyway().target("212").load().migrate()
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

        private const val RF_RESOLUTION = """{"type":"RequiredField","config":{"field":"resolution"}}"""
        private const val RF_SUMMARY = """{"type":"RequiredField","config":{"field":"summary"}}"""

        private val DRAFT_JSON =
            """
            {"key":"v213","name":"V213","states":[
              {"key":"v213_todo","name":"할 일","category":"TODO","displayOrder":1},
              {"key":"v213_done","name":"완료","category":"DONE","displayOrder":2}],
             "transitions":[
              {"to":"v213_done","name":"생성","kind":"INITIAL","validators":[$RF_RESOLUTION]},
              {"from":"v213_todo","to":"v213_done","name":"Complete","kind":"NORMAL","validators":[$RF_RESOLUTION],
               "screen":[{"field":"resolution","required":true}]},
              {"from":"v213_done","to":"v213_todo","name":"Reopen","validators":[$RF_SUMMARY,$RF_RESOLUTION],
               "screen":[{"field":"assignee","required":false}]},
              {"from":"v213_done","to":"v213_todo","name":"Plain"}]}
            """.trimIndent()

        @JvmStatic
        private fun seedFixture() {
            exec(
                "INSERT INTO statuses (key, name, category) VALUES " +
                    "('v213_todo', '할 일', 'TODO'), ('v213_done', '완료', 'DONE')",
                "INSERT INTO workflows (key, name) VALUES ('v213', 'V213')",
                "INSERT INTO workflow_statuses (workflow_id, status_id, display_order) " +
                    "SELECT w.id, s.id, 0 FROM workflows w, statuses s " +
                    "WHERE w.key = 'v213' AND s.key IN ('v213_todo', 'v213_done')",
                transitionSql("생성", "INITIAL", null, "v213_done"),
                transitionSql("Complete", "NORMAL", "v213_todo", "v213_done"),
                transitionSql("Reopen", "NORMAL", "v213_done", "v213_todo"),
                transitionSql("Rework", "NORMAL", "v213_done", "v213_todo"),
                validatorSql("생성", "resolution", 0),
                validatorSql("Complete", "resolution", 0),
                validatorSql("Reopen", "summary", 0),
                validatorSql("Reopen", "resolution", 1),
                // 같은 전환에 해결 결과 검증기 둘 — DISTINCT 가 없으면 ON CONFLICT 가 같은 행을 두 번 고치려다 멈춘다.
                validatorSql("Rework", "resolution", 0),
                validatorSql("Rework", "resolution", 1),
                // 이미 있는 「해결 결과 · 선택」 칸 — 필수로 올라가야 한다(DO UPDATE).
                "INSERT INTO workflow_transition_screen_fields (transition_id, field, required) " +
                    "SELECT id, 'resolution', FALSE FROM workflow_transitions WHERE name = 'Rework'",
                // V212 는 DONE 도착 전환(Complete)에 화면을 이미 심었다. Reopen 에는 담당자 칸만 둔다.
                "INSERT INTO workflow_transition_screen_fields (transition_id, field, required) " +
                    "SELECT id, 'assignee', FALSE FROM workflow_transitions WHERE name = 'Reopen'",
                "INSERT INTO workflow_drafts (workflow_id, definition, base_version) " +
                    "SELECT id, '$DRAFT_JSON'::jsonb, 0 FROM workflows WHERE key = 'v213'",
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
                    "JOIN workflows w ON w.id = ws.workflow_id WHERE w.key = 'v213' AND s.key = '$key')"
            }
            return "INSERT INTO workflow_transitions (workflow_id, kind, name, from_status_id, to_status_id) " +
                "SELECT id, '$kind', '$name', ${from?.let(composition) ?: "NULL"}, ${composition(to)} " +
                "FROM workflows WHERE key = 'v213'"
        }

        private fun validatorSql(
            transition: String,
            field: String,
            order: Int,
        ): String =
            "INSERT INTO workflow_validators (transition_id, type, config, display_order) " +
                "SELECT t.id, 'RequiredField', '{\"field\":\"$field\"}'::jsonb, $order " +
                "FROM workflow_transitions t JOIN workflows w ON w.id = t.workflow_id " +
                "WHERE w.key = 'v213' AND t.name = '$transition'"

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
    fun `발행본 — 비-INITIAL 전환의 해결 결과 검증기는 화면 필수로 옮겨지고 지워진다`() {
        val screened =
            strings(
                "SELECT t.name || ':' || f.field || ':' || f.required " +
                    "FROM workflow_transition_screen_fields f JOIN workflow_transitions t ON t.id = f.transition_id " +
                    "JOIN workflows w ON w.id = t.workflow_id WHERE w.key = 'v213' ORDER BY t.name, f.field",
            )
        val validators =
            strings(
                "SELECT t.name || ':' || (v.config ->> 'field') " +
                    "FROM workflow_validators v JOIN workflow_transitions t ON t.id = v.transition_id " +
                    "JOIN workflows w ON w.id = t.workflow_id WHERE w.key = 'v213' ORDER BY t.name",
            )

        assertThat(screened).containsExactly(
            "Complete:resolution:true",
            "Reopen:assignee:false",
            "Reopen:resolution:true",
            "Rework:resolution:true",
        )
        // INITIAL 에는 화면이 없어 옮길 자리가 없다 — 남는다. 다른 필드 검증기도 남는다.
        assertThat(validators).containsExactlyInAnyOrder("Reopen:summary", "생성:resolution")
    }

    @Test
    fun `초안 — 같은 규칙으로 옮기고 화면은 해결 결과가 맨 앞이며 전환 순서는 그대로다`() {
        val transitions =
            strings(
                "SELECT t ->> 'name' || '=' || COALESCE(t -> 'validators', 'null'::jsonb)::text || '|' || " +
                    "COALESCE(t -> 'screen', 'null'::jsonb)::text " +
                    "FROM workflow_drafts d JOIN workflows w ON w.id = d.workflow_id, " +
                    "jsonb_array_elements(d.definition -> 'transitions') WITH ORDINALITY x(t, ord) " +
                    "WHERE w.key = 'v213' ORDER BY ord",
            )

        assertThat(transitions).containsExactly(
            """생성=[{"type": "RequiredField", "config": {"field": "resolution"}}]|null""",
            """Complete=[]|[{"field": "resolution", "required": true}]""",
            """Reopen=[{"type": "RequiredField", "config": {"field": "summary"}}]|""" +
                """[{"field": "resolution", "required": true}, {"field": "assignee", "required": false}]""",
            "Plain=null|null",
        )
    }
}
