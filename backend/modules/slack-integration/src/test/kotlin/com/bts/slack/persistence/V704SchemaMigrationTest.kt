// V704 마이그레이션 검증 — slack_channel_project_map(채널↔프로젝트 매핑) 스키마/제약/인덱스 (FR-SL-06 PR-A)

package com.bts.slack.persistence

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
 * Flyway V704 마이그레이션 적용 후 FR-SL-06 신설 테이블 `slack_channel_project_map` 을 검증한다.
 *
 * 채널↔프로젝트 매핑은 한 프로젝트의 이벤트를 특정 Slack 채널로 브로드캐스트하기 위한 설정 행이다.
 * 다대다(한 프로젝트→여러 채널, 한 채널→여러 프로젝트)이며, `event_types text[]` 로 라우팅할 이벤트 종류를 필터한다.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) 의 PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이
 * 실행한다. 마이그레이션 체인에 V701 의 `CREATE EXTENSION pgmq` + `pgmq.create` 가 포함되므로 pgmq 바이너리가 담긴
 * `quay.io/tembo/pg16-pgmq` 이미지를 사용한다(SlackMigrationSchemaTest 동일 사유).
 *
 * **JVM 단위 singleton container 패턴** — companion object `.apply { start() }` 로 JVM 라이프사이클에 바인딩,
 * Ryuk 의 JVM 종료 시 자동 정리에 위임(동시 suite flaky 회피, SlackMigrationSchemaTest 동형).
 *
 * 검증 범위 (FR-SL-06 plan Task 1 / spec §데이터 모델 변경 V704 / DATA.md §4 TIMESTAMPTZ 강제).
 * - slack_channel_project_map 테이블 + 8개 컬럼(id, team_id, project_key, channel_id,
 *   channel_name, event_types, created_at, updated_at)
 * - id = uuid PK NOT NULL (애플리케이션 생성 UUID — slack_interaction_log(V703) 동형)
 * - team_id / project_key / channel_id = text NOT NULL (cross-BC 참조, BC 격리로 FK 없음)
 * - channel_name = text NULLABLE (표시용, 옵션)
 * - event_types = text[] NOT NULL (information_schema data_type = 'ARRAY')
 * - created_at / updated_at = timestamptz NOT NULL DEFAULT now() (TIMESTAMP without tz 금지)
 * - UNIQUE(team_id, project_key, channel_id) — 같은 워크스페이스에서 (프로젝트,채널) 매핑 중복 방지
 * - (project_key) 인덱스 — 채널 워커 라우팅 조회(PR-B 소비). PostgreSQL 은 자동 인덱스 없음(DATA.md §4.1#6).
 *
 * 정보 스키마(information_schema / pg_constraint / pg_indexes) 조회로 단언한다. SQL 문자열 결합 없이 prepared statement 사용.
 */
class V704SchemaMigrationTest {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container.
         * `.apply { start() }` 로 JVM 시작 시점에 한 번만 기동되며, Ryuk 이 JVM 종료 시 자동 정리한다.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_slack_channel_map_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        // slack_channel_project_map 이 보유해야 하는 8개 컬럼 (V704 DDL 정합).
        private val CHANNEL_MAP_COLUMNS =
            listOf(
                "id",
                "team_id",
                "project_key",
                "channel_id",
                "channel_name",
                "event_types",
                "created_at",
                "updated_at",
            )

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/slack-integration")
                .load()
                .migrate()
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun tableExists(tableName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
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
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
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

    // Connection → prepareStatement → executeQuery 3중 use 블록 중첩. SQL 헬퍼의 관용적 패턴이므로 Suppress.
    @Suppress("NestedBlockDepth")
    private fun columnDataType(
        tableName: String,
        columnName: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
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
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
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
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT column_default FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    // 지정 테이블의 PRIMARY KEY(contype = 'p') 구성 컬럼명 목록 조회 — id PK 검증용.
    @Suppress("NestedBlockDepth")
    private fun primaryKeyColumns(tableName: String): List<String> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT a.attname FROM pg_constraint c" +
                    " JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)" +
                    " WHERE c.contype = 'p' AND c.conrelid = ?::regclass",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    val cols = mutableListOf<String>()
                    while (rs.next()) cols.add(rs.getString(1))
                    cols
                }
            }
        }

    // slack_channel_project_map 위의 지정 인덱스 정의(pg_indexes.indexdef) 조회 — UNIQUE 여부/구성 컬럼 단언용. 없으면 null.
    private fun indexDefinition(indexName: String): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT indexdef FROM pg_indexes" +
                    " WHERE schemaname = 'public' AND tablename = 'slack_channel_project_map' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getString(1) else null
            }
        }

    // slack_channel_project_map 한 행 INSERT — UNIQUE(team_id, project_key, channel_id) 위반 유도용.
    // id 는 애플리케이션 생성 UUID, event_types 는 text[] round-trip(createArrayOf)로 바인딩한다.
    private fun insertMapping(
        teamId: String,
        projectKey: String,
        channelId: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val eventTypes = conn.createArrayOf("text", arrayOf("issue.created"))
            conn.prepareStatement(
                "INSERT INTO slack_channel_project_map" +
                    " (id, team_id, project_key, channel_id, event_types) VALUES (?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setString(2, teamId)
                stmt.setString(3, projectKey)
                stmt.setString(4, channelId)
                stmt.setArray(5, eventTypes)
                stmt.executeUpdate()
            }
        }
    }

    // ── 테이블 / 컬럼 검증 ─────────────────────────────────────────────────────

    @Test
    fun `V704 slack_channel_project_map 테이블 존재`() {
        assertThat(tableExists("slack_channel_project_map")).isTrue()
    }

    @Test
    fun `V704 slack_channel_project_map 8개 컬럼 존재`() {
        assertThat(columnsOf("slack_channel_project_map"))
            .containsExactlyInAnyOrderElementsOf(CHANNEL_MAP_COLUMNS)
    }

    @Test
    fun `V704 id 는 uuid PK NOT NULL`() {
        assertThat(columnDataType("slack_channel_project_map", "id")).isEqualTo("uuid")
        assertThat(columnIsNullable("slack_channel_project_map", "id")).isEqualTo("NO")
        assertThat(primaryKeyColumns("slack_channel_project_map")).containsExactly("id")
    }

    @Test
    fun `V704 team_id 는 text NOT NULL`() {
        assertThat(columnDataType("slack_channel_project_map", "team_id")).isEqualTo("text")
        assertThat(columnIsNullable("slack_channel_project_map", "team_id")).isEqualTo("NO")
    }

    @Test
    fun `V704 project_key 는 text NOT NULL`() {
        assertThat(columnDataType("slack_channel_project_map", "project_key")).isEqualTo("text")
        assertThat(columnIsNullable("slack_channel_project_map", "project_key")).isEqualTo("NO")
    }

    @Test
    fun `V704 channel_id 는 text NOT NULL`() {
        assertThat(columnDataType("slack_channel_project_map", "channel_id")).isEqualTo("text")
        assertThat(columnIsNullable("slack_channel_project_map", "channel_id")).isEqualTo("NO")
    }

    @Test
    fun `V704 channel_name 은 text NULLABLE`() {
        assertThat(columnDataType("slack_channel_project_map", "channel_name")).isEqualTo("text")
        assertThat(columnIsNullable("slack_channel_project_map", "channel_name")).isEqualTo("YES")
    }

    @Test
    fun `V704 event_types 는 text 배열 NOT NULL`() {
        // information_schema 는 배열 컬럼의 data_type 을 'ARRAY' 로 보고한다(요소 타입은 udt_name '_text').
        assertThat(columnDataType("slack_channel_project_map", "event_types")).isEqualTo("ARRAY")
        assertThat(columnIsNullable("slack_channel_project_map", "event_types")).isEqualTo("NO")
    }

    @Test
    fun `V704 created_at 은 timestamptz NOT NULL DEFAULT now`() {
        assertThat(columnDataType("slack_channel_project_map", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("slack_channel_project_map", "created_at")).isEqualTo("NO")
        assertThat(columnDefault("slack_channel_project_map", "created_at")).isEqualTo("now()")
    }

    @Test
    fun `V704 updated_at 은 timestamptz NOT NULL DEFAULT now`() {
        assertThat(columnDataType("slack_channel_project_map", "updated_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("slack_channel_project_map", "updated_at")).isEqualTo("NO")
        assertThat(columnDefault("slack_channel_project_map", "updated_at")).isEqualTo("now()")
    }

    // ── 제약 / 인덱스 검증 ─────────────────────────────────────────────────────

    @Test
    fun `V704 (team_id, project_key, channel_id) UNIQUE 제약 존재`() {
        // 명명 UNIQUE 제약은 동명의 UNIQUE 인덱스를 생성한다 — 정의에 UNIQUE 와 구성 컬럼 3종을 단언한다.
        val def = indexDefinition("uq_slack_channel_project_map_team_project_channel")
        assertThat(def).isNotNull()
        assertThat(def).contains("UNIQUE")
        assertThat(def).contains("(team_id, project_key, channel_id)")
    }

    @Test
    fun `V704 (project_key) 라우팅 조회 인덱스 존재`() {
        // 채널 워커가 projectKey 로 매핑을 조회(PR-B). PostgreSQL 은 자동 인덱스가 없어 명시 인덱스 필수(DATA.md §4.1#6).
        val def = indexDefinition("idx_slack_channel_project_map_project_key")
        assertThat(def).isNotNull()
        assertThat(def).contains("(project_key)")
    }

    @Test
    fun `V704 같은 (team_id, project_key, channel_id) 중복 INSERT 는 UNIQUE 위반`() {
        insertMapping("T_WORKSPACE_A", "PROJ", "C123")
        // 같은 (team_id, project_key, channel_id) 재삽입 시 UNIQUE 위반 — 매핑 중복 방지(멱등 CRUD 근거).
        assertThatThrownBy { insertMapping("T_WORKSPACE_A", "PROJ", "C123") }
            .hasMessageContaining("uq_slack_channel_project_map_team_project_channel")
    }
}
