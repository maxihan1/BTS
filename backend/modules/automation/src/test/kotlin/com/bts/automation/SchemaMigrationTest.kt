// V300~V306 마이그레이션 검증 — automation rules·actions·conditions·executions + q_automation_execution 큐

package com.bts.automation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * Flyway V300~V301 마이그레이션 적용 후 automation BC 스키마(automation_rules 테이블 + q_automation_execution
 * pgmq 큐)를 검증한다 (FR-AT-01 Task 2).
 *
 * ## 이미지 선택 — 왜 [AutomationTestcontainersBase] 를 상속/재사용하지 않는가
 * [AutomationTestcontainersBase] 의 컨테이너는 `postgres:16-alpine` 이며 pgmq 확장을 **탑재하지 않는다**.
 * 이 테스트는 V301 `SELECT pgmq.create('q_automation_execution')` 를 검증해야 하므로 pgmq 확장이
 * 사전 설치된 `quay.io/tembo/pg16-pgmq` 이미지가 필요하다. 따라서 issue-tracking
 * `WebhookEventsQueueMigrationTest`(q_webhook_events 큐 검증) 선례를 그대로 미러해 Spring 컨텍스트 없이
 * 전용 tembo 컨테이너로 Flyway 를 직접 구성해 `.migrate()` 한다.
 * (ADR 2026-05-22-pgmq-postgres-image 동일 결정.)
 *
 * ## JVM 단위 singleton container 패턴 ([[concurrent-testcontainers-suite-flaky]])
 * companion object `.apply { start() }` 로 JVM 라이프사이클에 바인딩한다. `@Container` 대신 Ryuk 의 JVM
 * 종료 시 자동 정리에 위임해 동시 suite flaky 를 회피한다(SlackInstallSchemaMigrationTest 동형).
 *
 * ## 검증 범위 (plan Task 2 / ADR D1·D4 / DATA.md §4 TIMESTAMPTZ 강제)
 * - automation_rules 테이블 존재 + 13개 컬럼(ADR D1 트리거 전용 스키마 정합)
 * - id = uuid PK NOT NULL / created_by = uuid NOT NULL(cross-BC user id, BC 격리로 FK 아님)
 * - project_key / name / trigger_type = character varying NOT NULL
 * - enabled = boolean NOT NULL DEFAULT true
 * - trigger_config = jsonb NOT NULL DEFAULT '{}'
 * - webhook_token_hash = character varying NULL(WEBHOOK 전용) / next_fire_at = timestamptz NULL(SCHEDULED 전용)
 * - created_at / updated_at = timestamptz NOT NULL(DATA.md §4.1#4 — TIMESTAMP without tz 금지)
 * - version = bigint NOT NULL DEFAULT 0(OCC 낙관적 잠금)
 * - deleted_at = timestamptz NULL(소프트 삭제)
 * - trigger_type CHECK 제약(5종 화이트리스트) — 잘못된 값 거부
 * - q_automation_execution 큐 존재(automation 소유, FR-AT-02 액션 executor 가 소비)
 * - **q_automation_events 는 automation 이 생성하지 않음**(plan-eng-review E1 — producer 인 issue-tracking 이
 *   Task 10 에서 소유·생성. automation 마이그레이션 경계 명시)
 *
 * ## FR-AT-02 Task 1 추가 검증 (V302 automation_actions + V303 actor_user_id / ADR D1)
 * - automation_actions 테이블 존재 + 6개 컬럼(id/rule_id/position/action_type/action_config/created_at)
 * - id = uuid PK NOT NULL / rule_id = uuid NOT NULL / position = integer NOT NULL
 * - action_type = character varying NOT NULL / action_config = jsonb NOT NULL
 * - created_at = timestamptz NOT NULL(DATA.md §4.1#4 — TIMESTAMP without tz 금지)
 * - action_type CHECK 5종(SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK/SET_FIX_VERSIONS) — 잘못된 값 거부
 *   (5번째 SET_FIX_VERSIONS 는 V306 확장 — FR-AT-07 PR-B)
 * - rule_id FK → automation_rules(id) ON DELETE CASCADE(부모 룰 삭제 시 액션 동반 삭제)
 * - UNIQUE(rule_id, position)(겸 조회 인덱스 uq_automation_actions_rule_position) — 중복 순서 거부
 * - automation_rules.actor_user_id = uuid NOT NULL(V303 backfill: created_by → SET NOT NULL, 룰 실행 주체)
 *
 * ## FR-AT-03 Task 4 추가 검증 (V304 automation_conditions / 조건 분기 스키마)
 * - automation_conditions 테이블 존재 + 4개 컬럼(rule_id/expression/created_at/updated_at)
 * - rule_id = uuid PK NOT NULL(**룰당 0..1 행** — automation_actions 의 N행 position 과 달리 단일 행)
 * - expression = jsonb NOT NULL(JSONLogic 부분집합 조건 트리)
 * - created_at / updated_at = timestamptz NOT NULL(DATA.md §4.1#4 — TIMESTAMP without tz 금지)
 * - 같은 rule_id 중복 INSERT → PK 위반(automation_conditions_pkey) — 룰당 0..1 강제
 * - rule_id FK → automation_rules(id) ON DELETE CASCADE(부모 룰 삭제 시 조건 동반 삭제)
 *
 * ## FR-AT-05 Task 1 추가 검증 (V305 rule_executions / 실행 이력·감사 스키마)
 * - rule_executions 테이블 존재 + 12개 컬럼(id/rule_id/project_key/trigger_type/trigger_event/
 *   issue_key/status/outcomes/replayed_from/started_at/finished_at/created_at)
 * - id = uuid PK NOT NULL DEFAULT gen_random_uuid() / rule_id = uuid NOT NULL(**하드 FK 없음** — 감사 독립성)
 * - project_key / trigger_type / status = text NOT NULL / issue_key = text NULL(SCHEDULED·WEBHOOK 은 NULL)
 * - trigger_event = jsonb NOT NULL(replay 재료) / outcomes = jsonb NOT NULL DEFAULT '[]'(단계별 결과 배열)
 * - replayed_from = uuid NULL(이 row 가 replay 면 원본 실행 id)
 * - started_at / finished_at / created_at = timestamptz NOT NULL(DATA.md §4.1#4 — TIMESTAMP without tz 금지)
 * - **하드 FK 없음(NFR-4 감사 독립성)** — 외래 키 제약 0개 + 존재하지 않는 rule_id INSERT 성공(룰 하드 삭제돼도 이력 보존)
 * - 인덱스 2종 — idx_rule_executions_rule(rule_id, started_at DESC) /
 *   idx_rule_executions_project_issue(project_key, issue_key, started_at DESC)
 *
 * 정보 스키마(information_schema / pg_constraint / pgmq.list_queues) 조회로 단언한다.
 * SQL 문자열 결합 없이 prepared statement 파라미터 바인딩만 사용한다.
 */
class SchemaMigrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V301 pgmq.create 가 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
        // asCompatibleSubstituteFor("postgres"): Testcontainers 이미지 호환성 검증 우회.
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_automation_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        // automation_rules 가 보유해야 하는 14개 컬럼 (V300 트리거 스키마 13 + V303 actor_user_id).
        private val AUTOMATION_RULES_COLUMNS =
            listOf(
                "id",
                "project_key",
                "name",
                "enabled",
                "trigger_type",
                "trigger_config",
                "webhook_token_hash",
                "next_fire_at",
                "created_by",
                "created_at",
                "updated_at",
                "version",
                "deleted_at",
                // V303 추가 — 룰 실행 주체(actor)
                "actor_user_id",
            )

        // automation_actions 가 보유해야 하는 6개 컬럼 (FR-AT-02 Task 1 / ADR D1 액션 스키마).
        private val AUTOMATION_ACTIONS_COLUMNS =
            listOf(
                "id",
                "rule_id",
                "position",
                "action_type",
                "action_config",
                "created_at",
            )

        // automation_conditions 가 보유해야 하는 4개 컬럼 (FR-AT-03 Task 4 / V304 조건 스키마).
        // rule_id 가 PK(룰당 0..1 행) — automation_actions(N행 position)와 달리 단일 행.
        private val AUTOMATION_CONDITIONS_COLUMNS =
            listOf(
                "rule_id",
                "expression",
                "created_at",
                "updated_at",
            )

        // rule_executions 가 보유해야 하는 12개 컬럼 (FR-AT-05 Task 1 / V305 실행 이력 스키마).
        // 감사 독립성(NFR-4)을 위해 rule_id 에 하드 FK 를 걸지 않는다 — 룰이 하드 삭제돼도 이력은 보존된다.
        private val RULE_EXECUTIONS_COLUMNS =
            listOf(
                "id",
                "rule_id",
                "project_key",
                "trigger_type",
                "trigger_event",
                "issue_key",
                "status",
                "outcomes",
                "replayed_from",
                "started_at",
                "finished_at",
                "created_at",
            )

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/automation")
                .load()
                .migrate()
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun conn() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun tableExists(tableName: String): Boolean =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables" +
                    " WHERE table_schema = 'public' AND table_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    @Suppress("NestedBlockDepth")
    private fun columnsOf(tableName: String): List<String> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT column_name FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    val cols = mutableListOf<String>()
                    while (rs.next()) cols.add(rs.getString(1))
                    cols
                }
            }
        }

    // Connection → prepareStatement → executeQuery 3중 use 중첩은 SQL 헬퍼의 관용적 패턴이므로 Suppress.
    @Suppress("NestedBlockDepth")
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

    @Suppress("NestedBlockDepth")
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

    @Suppress("NestedBlockDepth")
    private fun columnDefault(
        tableName: String,
        columnName: String,
    ): String? =
        conn().use { c ->
            c.prepareStatement(
                "SELECT column_default FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun indexExists(
        indexName: String,
        tableName: String = "automation_rules",
    ): Boolean =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM pg_indexes" +
                    " WHERE schemaname = 'public' AND tablename = ? AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, indexName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    @Suppress("NestedBlockDepth")
    private fun pgmqQueueExists(queueName: String): Boolean =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM pgmq.list_queues() WHERE queue_name = ?",
            ).use { stmt ->
                stmt.setString(1, queueName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // automation_rules 한 행 INSERT — NOT NULL 이면서 DEFAULT 가 없는 최소 컬럼만 채운다.
    // (project_key / name / trigger_type / created_by / actor_user_id. 나머지는 DEFAULT.)
    // actor_user_id 는 V303 에서 NOT NULL(DEFAULT 없음)이 되므로 반드시 채운다.
    private fun insertRule(triggerType: String) {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO automation_rules (project_key, name, trigger_type, created_by, actor_user_id)" +
                    " VALUES (?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, "ATLAS")
                stmt.setString(2, "자동 라벨 부여 룰")
                stmt.setString(3, triggerType)
                stmt.setObject(4, UUID.randomUUID())
                stmt.setObject(5, UUID.randomUUID())
                stmt.executeUpdate()
            }
        }
    }

    // WEBHOOK 룰 한 행 INSERT — webhook_token_hash 부분 UNIQUE 검증용.
    private fun insertWebhookRule(tokenHash: String) {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO automation_rules" +
                    " (project_key, name, trigger_type, webhook_token_hash, created_by, actor_user_id)" +
                    " VALUES (?, ?, 'WEBHOOK', ?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, "ATLAS")
                stmt.setString(2, "웹훅 인바운드 룰")
                stmt.setString(3, tokenHash)
                stmt.setObject(4, UUID.randomUUID())
                stmt.setObject(5, UUID.randomUUID())
                stmt.executeUpdate()
            }
        }
    }

    // ── FR-AT-02 액션 테이블 검증용 헬퍼 ────────────────────────────────────────

    // automation_rules 한 행 INSERT 후 생성된 id 반환 — automation_actions FK/CASCADE 검증용 부모 행.
    @Suppress("NestedBlockDepth")
    private fun insertRuleReturningId(): UUID =
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO automation_rules (project_key, name, trigger_type, created_by, actor_user_id)" +
                    " VALUES (?, ?, 'ISSUE_CREATED', ?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setString(1, "ATLAS")
                stmt.setString(2, "액션 보유 룰")
                stmt.setObject(3, UUID.randomUUID())
                stmt.setObject(4, UUID.randomUUID())
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1, UUID::class.java)
                }
            }
        }

    // automation_actions 한 행 INSERT — action_config 는 최소 '{}' jsonb.
    private fun insertAction(
        ruleId: UUID,
        position: Int,
        actionType: String,
    ) {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO automation_actions (rule_id, position, action_type, action_config)" +
                    " VALUES (?, ?, ?, ?::jsonb)",
            ).use { stmt ->
                stmt.setObject(1, ruleId)
                stmt.setInt(2, position)
                stmt.setString(3, actionType)
                stmt.setString(4, "{}")
                stmt.executeUpdate()
            }
        }
    }

    @Suppress("NestedBlockDepth")
    private fun countActionsForRule(ruleId: UUID): Int =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM automation_actions WHERE rule_id = ?",
            ).use { stmt ->
                stmt.setObject(1, ruleId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    // ON DELETE CASCADE 검증용 — 부모 룰 하드 삭제(id 단건, 대상 명시).
    private fun deleteRule(ruleId: UUID) {
        conn().use { c ->
            c.prepareStatement("DELETE FROM automation_rules WHERE id = ?").use { stmt ->
                stmt.setObject(1, ruleId)
                stmt.executeUpdate()
            }
        }
    }

    // ── FR-AT-03 조건 테이블 검증용 헬퍼 ──────────────────────────────────────────

    // automation_conditions 한 행 INSERT — expression 은 최소 '{}' jsonb(룰당 0..1, rule_id PK).
    private fun insertCondition(
        ruleId: UUID,
        expression: String,
    ) {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO automation_conditions (rule_id, expression) VALUES (?, ?::jsonb)",
            ).use { stmt ->
                stmt.setObject(1, ruleId)
                stmt.setString(2, expression)
                stmt.executeUpdate()
            }
        }
    }

    @Suppress("NestedBlockDepth")
    private fun countConditionsForRule(ruleId: UUID): Int =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM automation_conditions WHERE rule_id = ?",
            ).use { stmt ->
                stmt.setObject(1, ruleId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    // ── FR-AT-05 실행 이력 테이블 검증용 헬퍼 ──────────────────────────────────────

    // rule_executions 한 행 INSERT — 존재하지 않는 rule_id 로도 성공해야 한다(하드 FK 없음, NFR-4 감사 독립성).
    // NOT NULL 이면서 DEFAULT 없는 최소 컬럼만 채운다(project_key/trigger_type/trigger_event/status + 시각 2종).
    private fun insertExecution(ruleId: UUID) {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO rule_executions" +
                    " (rule_id, project_key, trigger_type, trigger_event, status, started_at, finished_at)" +
                    " VALUES (?, ?, ?, ?::jsonb, ?, now(), now())",
            ).use { stmt ->
                stmt.setObject(1, ruleId)
                stmt.setString(2, "ATLAS")
                stmt.setString(3, "ISSUE_CREATED")
                stmt.setString(4, "{}")
                stmt.setString(5, "SUCCESS")
                stmt.executeUpdate()
            }
        }
    }

    // 외래 키 제약 개수 — rule_executions 는 감사 독립성(NFR-4)으로 0이어야 한다.
    @Suppress("NestedBlockDepth")
    private fun foreignKeyCount(tableName: String): Int =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.table_constraints" +
                    " WHERE table_schema = 'public' AND table_name = ? AND constraint_type = 'FOREIGN KEY'",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    // ── 테이블 / 컬럼 존재 검증 ────────────────────────────────────────────────

    @Test
    fun `V300 automation_rules 테이블 존재`() {
        assertThat(tableExists("automation_rules")).isTrue()
    }

    @Test
    fun `automation_rules 14개 컬럼 존재 (V300 13 + V303 actor_user_id)`() {
        assertThat(columnsOf("automation_rules"))
            .containsExactlyInAnyOrderElementsOf(AUTOMATION_RULES_COLUMNS)
    }

    // ── 컬럼 타입 / NOT NULL 검증 ──────────────────────────────────────────────

    @Test
    fun `V300 id 는 uuid PK NOT NULL`() {
        assertThat(columnDataType("automation_rules", "id")).isEqualTo("uuid")
        assertThat(columnIsNullable("automation_rules", "id")).isEqualTo("NO")
    }

    @Test
    fun `V300 project_key 는 character varying NOT NULL`() {
        assertThat(columnDataType("automation_rules", "project_key")).isEqualTo("character varying")
        assertThat(columnIsNullable("automation_rules", "project_key")).isEqualTo("NO")
    }

    @Test
    fun `V300 name 은 character varying NOT NULL`() {
        assertThat(columnDataType("automation_rules", "name")).isEqualTo("character varying")
        assertThat(columnIsNullable("automation_rules", "name")).isEqualTo("NO")
    }

    @Test
    fun `V300 enabled 는 boolean NOT NULL DEFAULT true`() {
        assertThat(columnDataType("automation_rules", "enabled")).isEqualTo("boolean")
        assertThat(columnIsNullable("automation_rules", "enabled")).isEqualTo("NO")
        assertThat(columnDefault("automation_rules", "enabled")).isEqualTo("true")
    }

    @Test
    fun `V300 trigger_type 은 character varying NOT NULL`() {
        assertThat(columnDataType("automation_rules", "trigger_type")).isEqualTo("character varying")
        assertThat(columnIsNullable("automation_rules", "trigger_type")).isEqualTo("NO")
    }

    @Test
    fun `V300 trigger_config 는 jsonb NOT NULL DEFAULT 빈 객체`() {
        assertThat(columnDataType("automation_rules", "trigger_config")).isEqualTo("jsonb")
        assertThat(columnIsNullable("automation_rules", "trigger_config")).isEqualTo("NO")
        assertThat(columnDefault("automation_rules", "trigger_config")).contains("'{}'")
    }

    @Test
    fun `V300 webhook_token_hash 는 character varying NULL (WEBHOOK 전용)`() {
        assertThat(columnDataType("automation_rules", "webhook_token_hash")).isEqualTo("character varying")
        assertThat(columnIsNullable("automation_rules", "webhook_token_hash")).isEqualTo("YES")
    }

    @Test
    fun `V300 next_fire_at 은 timestamptz NULL (SCHEDULED 전용)`() {
        assertThat(columnDataType("automation_rules", "next_fire_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("automation_rules", "next_fire_at")).isEqualTo("YES")
    }

    @Test
    fun `V300 created_by 는 uuid NOT NULL`() {
        assertThat(columnDataType("automation_rules", "created_by")).isEqualTo("uuid")
        assertThat(columnIsNullable("automation_rules", "created_by")).isEqualTo("NO")
    }

    @Test
    fun `V300 version 은 bigint NOT NULL DEFAULT 0 (OCC)`() {
        assertThat(columnDataType("automation_rules", "version")).isEqualTo("bigint")
        assertThat(columnIsNullable("automation_rules", "version")).isEqualTo("NO")
        assertThat(columnDefault("automation_rules", "version")).isEqualTo("0")
    }

    // ── timestamptz 강제 검증 (DATA.md §4 — TIMESTAMP without tz 금지) ──────────

    @Test
    fun `V300 created_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("automation_rules", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("automation_rules", "created_at")).isEqualTo("NO")
    }

    @Test
    fun `V300 updated_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("automation_rules", "updated_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("automation_rules", "updated_at")).isEqualTo("NO")
    }

    @Test
    fun `V300 deleted_at 은 timestamptz NULL (소프트 삭제)`() {
        assertThat(columnDataType("automation_rules", "deleted_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("automation_rules", "deleted_at")).isEqualTo("YES")
    }

    // ── trigger_type CHECK 제약 검증 (5종 화이트리스트) ─────────────────────────

    @Test
    fun `V300 유효한 trigger_type 5종은 INSERT 허용`() {
        listOf("ISSUE_CREATED", "ISSUE_UPDATED", "ISSUE_COMMENTED", "SCHEDULED", "WEBHOOK")
            .forEach { insertRule(it) }
    }

    @Test
    fun `V300 정의되지 않은 trigger_type 은 CHECK 제약 위반`() {
        assertThatThrownBy { insertRule("PR_MERGED") }
            .hasMessageContaining("ck_automation_rules_trigger_type")
    }

    // ── 인덱스 검증 (조회 경로별 부분 인덱스) ──────────────────────────────────

    @Test
    fun `V300 이벤트 매칭 조회용 project_trigger_enabled 부분 인덱스 존재`() {
        assertThat(indexExists("idx_automation_rules_project_trigger_enabled")).isTrue()
    }

    @Test
    fun `V300 웹훅 토큰 조회 겸 유일성용 webhook_token_hash 부분 UNIQUE 인덱스 존재`() {
        assertThat(indexExists("uq_automation_rules_webhook_token_hash")).isTrue()
    }

    @Test
    fun `V300 스케줄 발화 조회용 next_fire_at 부분 인덱스 존재`() {
        assertThat(indexExists("idx_automation_rules_next_fire_at")).isTrue()
    }

    @Test
    fun `V300 같은 webhook_token_hash 중복 INSERT 는 부분 UNIQUE 위반`() {
        insertWebhookRule("a".repeat(64))
        assertThatThrownBy { insertWebhookRule("a".repeat(64)) }
            .hasMessageContaining("uq_automation_rules_webhook_token_hash")
    }

    // ── pgmq 큐 검증 (ADR D4 — automation 소유 실행 큐 / E1 — events 큐는 비소유) ─

    @Test
    fun `V301 q_automation_execution 큐가 pgmq list_queues 에 존재한다`() {
        assertThat(pgmqQueueExists("q_automation_execution")).isTrue()
    }

    @Test
    fun `automation 마이그레이션은 q_automation_events 큐를 생성하지 않는다 (E1 — issue-tracking 소유)`() {
        assertThat(pgmqQueueExists("q_automation_events"))
            .`as`("q_automation_events 는 producer 인 issue-tracking(Task 10)이 소유·생성한다 — automation 경계 밖")
            .isFalse()
    }

    // ── FR-AT-02 Task 1: automation_actions 테이블 검증 (V302 / ADR D1) ──────────

    @Test
    fun `V302 automation_actions 테이블 존재`() {
        assertThat(tableExists("automation_actions")).isTrue()
    }

    @Test
    fun `V302 automation_actions 6개 컬럼 존재`() {
        assertThat(columnsOf("automation_actions"))
            .containsExactlyInAnyOrderElementsOf(AUTOMATION_ACTIONS_COLUMNS)
    }

    @Test
    fun `V302 automation_actions id 는 uuid PK NOT NULL`() {
        assertThat(columnDataType("automation_actions", "id")).isEqualTo("uuid")
        assertThat(columnIsNullable("automation_actions", "id")).isEqualTo("NO")
    }

    @Test
    fun `V302 rule_id 는 uuid NOT NULL`() {
        assertThat(columnDataType("automation_actions", "rule_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("automation_actions", "rule_id")).isEqualTo("NO")
    }

    @Test
    fun `V302 position 은 integer NOT NULL`() {
        assertThat(columnDataType("automation_actions", "position")).isEqualTo("integer")
        assertThat(columnIsNullable("automation_actions", "position")).isEqualTo("NO")
    }

    @Test
    fun `V302 action_type 은 character varying NOT NULL`() {
        assertThat(columnDataType("automation_actions", "action_type")).isEqualTo("character varying")
        assertThat(columnIsNullable("automation_actions", "action_type")).isEqualTo("NO")
    }

    @Test
    fun `V302 action_config 는 jsonb NOT NULL`() {
        assertThat(columnDataType("automation_actions", "action_config")).isEqualTo("jsonb")
        assertThat(columnIsNullable("automation_actions", "action_config")).isEqualTo("NO")
    }

    @Test
    fun `V302 automation_actions created_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("automation_actions", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("automation_actions", "created_at")).isEqualTo("NO")
    }

    // ── action_type CHECK 제약 (V306 이후 5종 화이트리스트) ─────────────────────

    @Test
    fun `V306 유효한 action_type 5종은 INSERT 허용`() {
        val ruleId = insertRuleReturningId()
        listOf("SET_FIELD", "ASSIGN", "ADD_COMMENT", "CALL_WEBHOOK", "SET_FIX_VERSIONS")
            .forEachIndexed { position, actionType -> insertAction(ruleId, position, actionType) }
    }

    @Test
    fun `V302 정의되지 않은 action_type 은 CHECK 제약 위반`() {
        val ruleId = insertRuleReturningId()
        assertThatThrownBy { insertAction(ruleId, 0, "DELETE_ISSUE") }
            .hasMessageContaining("ck_automation_actions_action_type")
    }

    // ── FK ON DELETE CASCADE + UNIQUE(rule_id, position) ────────────────────────

    @Test
    fun `V302 rule 삭제 시 automation_actions 는 ON DELETE CASCADE 로 함께 삭제`() {
        val ruleId = insertRuleReturningId()
        insertAction(ruleId, 0, "SET_FIELD")
        assertThat(countActionsForRule(ruleId)).isEqualTo(1)
        deleteRule(ruleId)
        assertThat(countActionsForRule(ruleId)).isEqualTo(0)
    }

    @Test
    fun `V302 같은 rule_id + position 중복 INSERT 는 UNIQUE 위반`() {
        val ruleId = insertRuleReturningId()
        insertAction(ruleId, 0, "SET_FIELD")
        assertThatThrownBy { insertAction(ruleId, 0, "ASSIGN") }
            .hasMessageContaining("uq_automation_actions_rule_position")
    }

    @Test
    fun `V302 조회 겸 유일성용 rule_id position 인덱스 존재`() {
        assertThat(indexExists("uq_automation_actions_rule_position", "automation_actions")).isTrue()
    }

    // ── FR-AT-02 Task 1: automation_rules.actor_user_id 검증 (V303) ─────────────

    @Test
    fun `V303 actor_user_id 는 uuid NOT NULL (실행 주체)`() {
        assertThat(columnDataType("automation_rules", "actor_user_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("automation_rules", "actor_user_id")).isEqualTo("NO")
    }

    // ── FR-AT-03 Task 4: automation_conditions 테이블 검증 (V304) ─────────────────

    @Test
    fun `V304 automation_conditions 테이블 존재`() {
        assertThat(tableExists("automation_conditions")).isTrue()
    }

    @Test
    fun `V304 automation_conditions 4개 컬럼 존재`() {
        assertThat(columnsOf("automation_conditions"))
            .containsExactlyInAnyOrderElementsOf(AUTOMATION_CONDITIONS_COLUMNS)
    }

    @Test
    fun `V304 rule_id 는 uuid PK NOT NULL (룰당 단일 행)`() {
        assertThat(columnDataType("automation_conditions", "rule_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("automation_conditions", "rule_id")).isEqualTo("NO")
    }

    @Test
    fun `V304 expression 은 jsonb NOT NULL`() {
        assertThat(columnDataType("automation_conditions", "expression")).isEqualTo("jsonb")
        assertThat(columnIsNullable("automation_conditions", "expression")).isEqualTo("NO")
    }

    @Test
    fun `V304 automation_conditions created_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("automation_conditions", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("automation_conditions", "created_at")).isEqualTo("NO")
    }

    @Test
    fun `V304 automation_conditions updated_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("automation_conditions", "updated_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("automation_conditions", "updated_at")).isEqualTo("NO")
    }

    @Test
    fun `V304 같은 rule_id 중복 INSERT 는 PK 위반 (룰당 단일 행)`() {
        val ruleId = insertRuleReturningId()
        insertCondition(ruleId, "{}")
        assertThatThrownBy { insertCondition(ruleId, """{"and":[]}""") }
            .hasMessageContaining("automation_conditions_pkey")
    }

    @Test
    fun `V304 rule 삭제 시 automation_conditions 는 ON DELETE CASCADE 로 함께 삭제`() {
        val ruleId = insertRuleReturningId()
        insertCondition(ruleId, "{}")
        assertThat(countConditionsForRule(ruleId)).isEqualTo(1)
        deleteRule(ruleId)
        assertThat(countConditionsForRule(ruleId)).isEqualTo(0)
    }

    // ── FR-AT-05 Task 1: rule_executions 테이블 검증 (V305 실행 이력·감사) ──────────

    @Test
    fun `V305 rule_executions 테이블 존재`() {
        assertThat(tableExists("rule_executions")).isTrue()
    }

    @Test
    fun `V305 rule_executions 12개 컬럼 존재`() {
        assertThat(columnsOf("rule_executions"))
            .containsExactlyInAnyOrderElementsOf(RULE_EXECUTIONS_COLUMNS)
    }

    @Test
    fun `V305 id 는 uuid PK NOT NULL DEFAULT gen_random_uuid`() {
        assertThat(columnDataType("rule_executions", "id")).isEqualTo("uuid")
        assertThat(columnIsNullable("rule_executions", "id")).isEqualTo("NO")
        assertThat(columnDefault("rule_executions", "id")).contains("gen_random_uuid")
    }

    @Test
    fun `V305 rule_id 는 uuid NOT NULL (하드 FK 없음)`() {
        assertThat(columnDataType("rule_executions", "rule_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("rule_executions", "rule_id")).isEqualTo("NO")
    }

    @Test
    fun `V305 project_key 는 text NOT NULL`() {
        assertThat(columnDataType("rule_executions", "project_key")).isEqualTo("text")
        assertThat(columnIsNullable("rule_executions", "project_key")).isEqualTo("NO")
    }

    @Test
    fun `V305 trigger_type 은 text NOT NULL`() {
        assertThat(columnDataType("rule_executions", "trigger_type")).isEqualTo("text")
        assertThat(columnIsNullable("rule_executions", "trigger_type")).isEqualTo("NO")
    }

    @Test
    fun `V305 trigger_event 는 jsonb NOT NULL (replay 재료)`() {
        assertThat(columnDataType("rule_executions", "trigger_event")).isEqualTo("jsonb")
        assertThat(columnIsNullable("rule_executions", "trigger_event")).isEqualTo("NO")
    }

    @Test
    fun `V305 issue_key 는 text NULL (SCHEDULED WEBHOOK 은 NULL)`() {
        assertThat(columnDataType("rule_executions", "issue_key")).isEqualTo("text")
        assertThat(columnIsNullable("rule_executions", "issue_key")).isEqualTo("YES")
    }

    @Test
    fun `V305 status 는 text NOT NULL`() {
        assertThat(columnDataType("rule_executions", "status")).isEqualTo("text")
        assertThat(columnIsNullable("rule_executions", "status")).isEqualTo("NO")
    }

    @Test
    fun `V305 outcomes 는 jsonb NOT NULL DEFAULT 빈 배열`() {
        assertThat(columnDataType("rule_executions", "outcomes")).isEqualTo("jsonb")
        assertThat(columnIsNullable("rule_executions", "outcomes")).isEqualTo("NO")
        assertThat(columnDefault("rule_executions", "outcomes")).contains("'[]'")
    }

    @Test
    fun `V305 replayed_from 은 uuid NULL (replay 원본 실행 id)`() {
        assertThat(columnDataType("rule_executions", "replayed_from")).isEqualTo("uuid")
        assertThat(columnIsNullable("rule_executions", "replayed_from")).isEqualTo("YES")
    }

    // ── timestamptz 강제 (DATA.md §4 — TIMESTAMP without tz 금지) ─────────────────

    @Test
    fun `V305 started_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("rule_executions", "started_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("rule_executions", "started_at")).isEqualTo("NO")
    }

    @Test
    fun `V305 finished_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("rule_executions", "finished_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("rule_executions", "finished_at")).isEqualTo("NO")
    }

    @Test
    fun `V305 created_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("rule_executions", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("rule_executions", "created_at")).isEqualTo("NO")
    }

    // ── 감사 독립성 (NFR-4) — 하드 FK 없음 ────────────────────────────────────────

    @Test
    fun `V305 rule_executions 는 외래 키 제약이 없다 (감사 독립성 NFR-4)`() {
        assertThat(foreignKeyCount("rule_executions")).isEqualTo(0)
    }

    @Test
    fun `V305 존재하지 않는 rule_id 로도 INSERT 성공 (하드 FK 없음)`() {
        insertExecution(UUID.randomUUID())
    }

    // ── 인덱스 검증 (조회 경로별) ─────────────────────────────────────────────────

    @Test
    fun `V305 룰별 이력 조회용 rule started_at 인덱스 존재`() {
        assertThat(indexExists("idx_rule_executions_rule", "rule_executions")).isTrue()
    }

    @Test
    fun `V305 프로젝트 이슈별 이력 조회용 project_issue 인덱스 존재`() {
        assertThat(indexExists("idx_rule_executions_project_issue", "rule_executions")).isTrue()
    }
}
