// V700 마이그레이션 검증 — slack_installs 테이블 + 11컬럼 + team_id UNIQUE(upsert 기준) + deleted_at 부재 확인 (FR-SL-01)

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
 * Flyway V700 마이그레이션 적용 후 slack_installs 테이블(FR-SL-01 Slack App 설치 + 봇 토큰 보관)을 검증한다.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) 의 PostgreSQL 을 직접 사용하며
 * Spring 컨텍스트 없이 실행한다. slack-integration BC 마이그레이션(V700)은 pgmq 확장을 요구하지 않으므로
 * postgres:16-alpine 이미지로 충분하다 (notification FavoritesSchemaMigrationTest 동일 패턴).
 *
 * **test-boot 앱 비의존** — [com.bts.slack.SlackIntegrationTestBootApplication] 은 DataSource/Flyway
 * AutoConfiguration 을 제외했으므로(Task 1 인계) 마이그레이션 검증은 부트 클래스에 의존하지 않고
 * `org.flywaydb.core.Flyway` 를 코드로 직접 구성해 `.migrate()` 한다
 * (search-export-import ExportJobsSchemaMigrationTest / notification FavoritesSchemaMigrationTest 선례).
 *
 * **JVM 단위 singleton container 패턴** — companion object `.apply { start() }` 로 JVM 라이프사이클에 바인딩.
 * `@Container` 라이프사이클 대신 Ryuk 의 JVM 종료 시 자동 정리에 위임해 동시 suite flaky 를 회피한다
 * (메모리 concurrent-testcontainers-suite-flaky / ExportJobsSchemaMigrationTest 동일 패턴).
 *
 * 검증 범위 (FR-SL-01 plan Task 2 / spec §데이터 모델 / DATA.md §4 TIMESTAMPTZ 강제).
 * - slack_installs 테이블 존재 + 11개 컬럼 (spec DDL 정합)
 * - id = uuid PK NOT NULL
 * - team_id = text NOT NULL + UNIQUE(uq_slack_installs_team_id) — upsert 기준(ON CONFLICT), 중복 INSERT 거부
 * - team_name / bot_user_id / app_id / bot_token_encrypted / scopes = text NOT NULL
 * - is_enterprise_install = boolean NOT NULL DEFAULT false
 * - installed_by = uuid NOT NULL (cross-BC user id, BC 격리로 FK 아님)
 * - installed_at / updated_at = timestamptz NOT NULL (DATA.md §4.1#4 — TIMESTAMP without tz 금지)
 * - deleted_at 컬럼 부재 (설치는 upsert 멱등 — 소프트 삭제 대상 아님, revoke=행 제거)
 *
 * 정보 스키마(information_schema / pg_constraint) 조회로 단언한다. SQL 문자열 결합 없이 prepared statement 사용.
 */
class SlackInstallSchemaMigrationTest {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container.
         * `.apply { start() }` 로 JVM 시작 시점에 한 번만 기동되며, Ryuk 이 JVM 종료 시 자동 정리한다.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_slack_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        // slack_installs 가 보유해야 하는 11개 컬럼 (spec §데이터 모델 DDL 정합).
        private val SLACK_INSTALLS_COLUMNS =
            listOf(
                "id",
                "team_id",
                "team_name",
                "bot_user_id",
                "app_id",
                "bot_token_encrypted",
                "scopes",
                "is_enterprise_install",
                "installed_by",
                "installed_at",
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

    private fun columnExists(
        tableName: String,
        columnName: String,
    ): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
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

    // 지정 이름의 UNIQUE 제약(contype = 'u') 존재 여부 조회 — uq_slack_installs_team_id 검증용.
    @Suppress("NestedBlockDepth")
    private fun uniqueConstraintExists(
        tableName: String,
        constraintName: String,
    ): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM pg_constraint" +
                    " WHERE contype = 'u' AND conrelid = ?::regclass AND conname = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, constraintName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // slack_installs 한 행 INSERT — team_id UNIQUE 위반 유도용. NOT NULL 컬럼을 모두 채운다.
    // id 는 DEFAULT(gen_random_uuid()) 가 있으나 명시 지정으로 재삽입 간 id 충돌을 배제하고 team_id 위반만 유도한다.
    private fun insertInstall(teamId: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO slack_installs" +
                    " (id, team_id, team_name, bot_user_id, app_id, bot_token_encrypted, scopes, installed_by)" +
                    " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setString(2, teamId)
                stmt.setString(3, "Acme Workspace")
                stmt.setString(4, "U0BOT")
                stmt.setString(5, "A0APP")
                stmt.setString(6, "deadbeefcafe")
                stmt.setString(7, "chat:write,commands")
                stmt.setObject(8, UUID.randomUUID())
                stmt.executeUpdate()
            }
        }
    }

    // ── 테이블 / 컬럼 존재 검증 ────────────────────────────────────────────────

    @Test
    fun `V700 slack_installs 테이블 존재`() {
        assertThat(tableExists("slack_installs")).isTrue()
    }

    @Test
    fun `V700 slack_installs 11개 컬럼 존재`() {
        assertThat(columnsOf("slack_installs"))
            .containsExactlyInAnyOrderElementsOf(SLACK_INSTALLS_COLUMNS)
    }

    // ── 컬럼 타입 / NOT NULL 검증 ──────────────────────────────────────────────

    @Test
    fun `V700 id 는 uuid PK NOT NULL`() {
        assertThat(columnDataType("slack_installs", "id")).isEqualTo("uuid")
        assertThat(columnIsNullable("slack_installs", "id")).isEqualTo("NO")
    }

    @Test
    fun `V700 team_id 는 text NOT NULL`() {
        assertThat(columnDataType("slack_installs", "team_id")).isEqualTo("text")
        assertThat(columnIsNullable("slack_installs", "team_id")).isEqualTo("NO")
    }

    @Test
    fun `V700 team_name 은 text NOT NULL`() {
        assertThat(columnDataType("slack_installs", "team_name")).isEqualTo("text")
        assertThat(columnIsNullable("slack_installs", "team_name")).isEqualTo("NO")
    }

    @Test
    fun `V700 bot_user_id 는 text NOT NULL`() {
        assertThat(columnDataType("slack_installs", "bot_user_id")).isEqualTo("text")
        assertThat(columnIsNullable("slack_installs", "bot_user_id")).isEqualTo("NO")
    }

    @Test
    fun `V700 app_id 는 text NOT NULL`() {
        assertThat(columnDataType("slack_installs", "app_id")).isEqualTo("text")
        assertThat(columnIsNullable("slack_installs", "app_id")).isEqualTo("NO")
    }

    @Test
    fun `V700 bot_token_encrypted 는 text NOT NULL`() {
        assertThat(columnDataType("slack_installs", "bot_token_encrypted")).isEqualTo("text")
        assertThat(columnIsNullable("slack_installs", "bot_token_encrypted")).isEqualTo("NO")
    }

    @Test
    fun `V700 scopes 는 text NOT NULL`() {
        assertThat(columnDataType("slack_installs", "scopes")).isEqualTo("text")
        assertThat(columnIsNullable("slack_installs", "scopes")).isEqualTo("NO")
    }

    @Test
    fun `V700 is_enterprise_install 은 boolean NOT NULL DEFAULT false`() {
        assertThat(columnDataType("slack_installs", "is_enterprise_install")).isEqualTo("boolean")
        assertThat(columnIsNullable("slack_installs", "is_enterprise_install")).isEqualTo("NO")
        assertThat(columnDefault("slack_installs", "is_enterprise_install")).isEqualTo("false")
    }

    @Test
    fun `V700 installed_by 는 uuid NOT NULL`() {
        assertThat(columnDataType("slack_installs", "installed_by")).isEqualTo("uuid")
        assertThat(columnIsNullable("slack_installs", "installed_by")).isEqualTo("NO")
    }

    // ── timestamptz 강제 검증 (DATA.md §4 — TIMESTAMP without tz 금지) ──────────

    @Test
    fun `V700 installed_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("slack_installs", "installed_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("slack_installs", "installed_at")).isEqualTo("NO")
    }

    @Test
    fun `V700 updated_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("slack_installs", "updated_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("slack_installs", "updated_at")).isEqualTo("NO")
    }

    // ── team_id UNIQUE 검증 (upsert 기준 — ON CONFLICT (team_id)) ──────────────

    @Test
    fun `V700 uq_slack_installs_team_id 유니크 제약 존재`() {
        assertThat(uniqueConstraintExists("slack_installs", "uq_slack_installs_team_id")).isTrue()
    }

    @Test
    fun `V700 같은 team_id 중복 INSERT 는 유니크 위반`() {
        insertInstall("T_WORKSPACE_A")
        // 같은 team_id 재삽입 시 제약명(uq_slack_installs_team_id) 위반 — upsert(ON CONFLICT) 멱등의 근거.
        assertThatThrownBy { insertInstall("T_WORKSPACE_A") }
            .hasMessageContaining("uq_slack_installs_team_id")
    }

    // ── deleted_at 부재 검증 (설치는 upsert 멱등 — 소프트 삭제 아님) ─────────────

    @Test
    fun `V700 slack_installs 는 deleted_at 컬럼이 없다 (upsert 멱등)`() {
        assertThat(columnExists("slack_installs", "deleted_at"))
            .`as`("slack_installs 는 upsert 멱등 테이블이므로 deleted_at 컬럼이 없어야 한다 (revoke=행 제거)")
            .isFalse()
    }
}
