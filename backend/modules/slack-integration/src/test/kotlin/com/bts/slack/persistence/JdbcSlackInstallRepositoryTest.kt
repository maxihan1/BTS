// JdbcSlackInstallRepository 통합 테스트 — upsert 멱등 갱신 + findByTeamId 조회 검증 (FR-SL-01 Task 7)

package com.bts.slack.persistence

import com.bts.slack.domain.SlackInstall
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * [JdbcSlackInstallRepository] 통합 테스트 (FR-SL-01 Task 7).
 *
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL 에 V700 마이그레이션을 직접 적용하고,
 * [NamedParameterJdbcTemplate] 을 손수 구성해 리포지토리를 직접 생성한다
 * ([SlackInstallSchemaMigrationTest] 와 동일한 JVM 단위 singleton container 패턴).
 *
 * ## 검증 시나리오
 * - upsert 신규 삽입 후 findByTeamId 로 복원
 * - findByTeamId 부재 시 null
 * - 같은 team_id 재upsert 시 행 수 1 유지 + 필드 갱신
 * - 재upsert 시 updated_at 증가
 * - 저장된 bot_token_encrypted 가 전달한 암호문과 일치(평문 변환 없이 그대로 저장됨을 확인)
 */
class JdbcSlackInstallRepositoryTest {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container.
         * (memory: concurrent-testcontainers-suite-flaky — singleton 패턴 표준.)
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_slack_repo_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        @JvmStatic
        private var migrated = false

        /** Flyway V700 마이그레이션을 JVM 당 1회만 실행한다. */
        @JvmStatic
        @Synchronized
        fun migrateOnce() {
            if (migrated) return
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/slack-integration")
                .load()
                .migrate()
            migrated = true
        }
    }

    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: JdbcSlackInstallRepository

    @BeforeEach
    fun setUp() {
        migrateOnce()
        val dataSource: DataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        jdbc = NamedParameterJdbcTemplate(dataSource)
        jdbc.update("DELETE FROM slack_installs", emptyMap<String, Any>())
        repository = JdbcSlackInstallRepository(jdbc)
    }

    private fun sampleInstall(
        teamId: String = "T_WORKSPACE_A",
        teamName: String = "Acme Workspace",
        botTokenEncrypted: String = "deadbeefcafe",
    ): SlackInstall =
        SlackInstall(
            teamId = teamId,
            teamName = teamName,
            botUserId = "U0BOT",
            appId = "A0APP",
            botTokenEncrypted = botTokenEncrypted,
            scopes = "chat:write,commands",
            isEnterpriseInstall = false,
            installedBy = UUID.randomUUID(),
        )

    // ── upsert 신규 삽입 + findByTeamId 복원 ────────────────────────────────────

    @Test
    fun `upsert 신규 삽입 후 findByTeamId 로 복원된다`() {
        val install = sampleInstall()

        repository.upsert(install)
        val found = repository.findByTeamId(install.teamId)

        assertThat(found).isEqualTo(install)
    }

    @Test
    fun `findByTeamId — 존재하지 않는 team_id 는 null 을 반환한다`() {
        assertThat(repository.findByTeamId("T_UNKNOWN")).isNull()
    }

    // ── upsert 멱등 갱신 (ON CONFLICT (team_id) DO UPDATE) ──────────────────────

    @Test
    fun `upsert — 같은 team_id 재upsert 시 행 수는 1개로 유지되고 필드가 갱신된다`() {
        repository.upsert(sampleInstall(teamName = "Acme Workspace", botTokenEncrypted = "deadbeefcafe"))
        val updated = sampleInstall(teamName = "Acme Workspace Renamed", botTokenEncrypted = "newciphertext")

        repository.upsert(updated)

        val count =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM slack_installs WHERE team_id = :teamId",
                mapOf("teamId" to updated.teamId),
                Int::class.java,
            )
        assertThat(count).isEqualTo(1)
        assertThat(repository.findByTeamId(updated.teamId)).isEqualTo(updated)
    }

    @Test
    fun `upsert — 재upsert 시 updated_at 이 이전 값 이후로 갱신된다`() {
        repository.upsert(sampleInstall())
        val updatedAtBefore = updatedAtOf("T_WORKSPACE_A")

        // updated_at 해상도 차이를 보장하기 위한 최소 대기 (memory: 기존 리포지토리 테스트 선례 동형)
        Thread.sleep(10)
        repository.upsert(sampleInstall(teamName = "Renamed"))
        val updatedAtAfter = updatedAtOf("T_WORKSPACE_A")

        assertThat(updatedAtAfter).isAfter(updatedAtBefore)
    }

    private fun updatedAtOf(teamId: String): OffsetDateTime =
        jdbc.queryForObject(
            "SELECT updated_at FROM slack_installs WHERE team_id = :teamId",
            mapOf("teamId" to teamId),
            OffsetDateTime::class.java,
        )!!

    // ── bot_token_encrypted 그대로 저장 검증 (평문 변환 없음) ────────────────────

    @Test
    fun `upsert — 저장된 bot_token_encrypted 는 전달한 암호문과 일치한다`() {
        val install = sampleInstall(botTokenEncrypted = "cafebabef00d")

        repository.upsert(install)

        val storedEncrypted =
            jdbc.queryForObject(
                "SELECT bot_token_encrypted FROM slack_installs WHERE team_id = :teamId",
                mapOf("teamId" to install.teamId),
                String::class.java,
            )
        assertThat(storedEncrypted).isEqualTo("cafebabef00d")
    }
}
