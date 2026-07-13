// JdbcSlackChannelMappingRepository 통합 테스트 — 채널↔프로젝트 매핑 CRUD round-trip (FR-SL-06 Task 4)

package com.bts.slack.persistence

import com.bts.slack.domain.ChannelProjectMapping
import com.bts.slack.domain.SlackChannelEventType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID

/**
 * [JdbcSlackChannelMappingRepository] 통합 테스트 (FR-SL-06 Task 4).
 *
 * [V704SchemaMigrationTest]/[JdbcSlackUserMappingRepositoryReverseTest] 와 동형 — Spring 컨텍스트 없이
 * Testcontainers PostgreSQL 에 slack-integration 전체 마이그레이션(V700~V704)을 직접 적용하고
 * [JdbcTemplate] 을 손수 구성해 리포지토리를 직접 생성한다.
 *
 * ## 검증 시나리오
 * - save → findById round-trip — `eventTypes`(`Set<String>`) 가 text[] 컬럼을 왕복해도 보존된다.
 * - findByProjectKey — 같은 프로젝트에 여러 채널 매핑이 있으면 전부 반환한다.
 * - update — channelId/channelName/eventTypes 가 갱신되고 updatedAt 이 이전 값과 달라진다.
 * - deleteById — 존재하는 id 는 true, 존재하지 않는 id 는 false.
 * - UNIQUE(team_id, project_key, channel_id) 위반 시 [DataIntegrityViolationException] 이 전파된다(삼키지 않음).
 */
class JdbcSlackChannelMappingRepositoryIntegrationTest {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container.
         * (memory: concurrent-testcontainers-suite-flaky — singleton 패턴 표준.)
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_slack_channel_map_repo_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        @JvmStatic
        private var migrated = false

        /** Flyway V700~V704 마이그레이션을 JVM 당 1회만 실행한다. */
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

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var repository: JdbcSlackChannelMappingRepository

    @BeforeEach
    fun setUp() {
        migrateOnce()
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.update("DELETE FROM slack_channel_project_map")
        repository = JdbcSlackChannelMappingRepository(jdbcTemplate)
    }

    private fun newMapping(
        teamId: String = "T_WORKSPACE_A",
        projectKey: String = "PROJ",
        channelId: String = "C123",
        channelName: String? = "general",
        eventTypes: Set<String> = setOf(SlackChannelEventType.ISSUE_CREATED, SlackChannelEventType.ISSUE_ASSIGNED),
        createdAt: Instant = Instant.parse("2026-07-13T09:00:00.123456Z"),
        updatedAt: Instant = createdAt,
    ): ChannelProjectMapping =
        ChannelProjectMapping(
            id = UUID.randomUUID(),
            teamId = teamId,
            projectKey = projectKey,
            channelId = channelId,
            channelName = channelName,
            eventTypes = eventTypes,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    // ── save → findById round-trip ──────────────────────────────────────────────

    @Test
    fun `save 후 findById 로 조회하면 eventTypes(Set) 를 포함한 모든 필드가 보존된다`() {
        val mapping = newMapping()

        repository.save(mapping)
        val found = repository.findById(mapping.id)

        assertThat(found).isEqualTo(mapping)
    }

    @Test
    fun `findById 는 존재하지 않는 id 면 null 을 반환한다`() {
        assertThat(repository.findById(UUID.randomUUID())).isNull()
    }

    // ── findByProjectKey(다건) ───────────────────────────────────────────────────

    @Test
    fun `findByProjectKey 는 같은 프로젝트의 채널 매핑을 전부 반환한다`() {
        val first = newMapping(channelId = "C001")
        val second = newMapping(channelId = "C002")
        val otherProject = newMapping(projectKey = "OTHER", channelId = "C003")
        repository.save(first)
        repository.save(second)
        repository.save(otherProject)

        val found = repository.findByProjectKey("PROJ")

        assertThat(found).containsExactlyInAnyOrder(first, second)
    }

    @Test
    fun `findByProjectKey 는 매핑이 없으면 빈 리스트를 반환한다`() {
        assertThat(repository.findByProjectKey("NO_SUCH_PROJECT")).isEmpty()
    }

    // ── update(eventTypes+channel 갱신, updated_at 변경) ────────────────────────

    @Test
    fun `update 는 channelId, channelName, eventTypes 를 갱신하고 updatedAt 을 변경한다`() {
        val original = newMapping()
        repository.save(original)

        val updated =
            original.copy(
                channelId = "C999",
                channelName = "renamed-channel",
                eventTypes = setOf(SlackChannelEventType.ISSUE_TRANSITIONED),
                updatedAt = original.updatedAt.plusSeconds(60),
            )
        repository.update(updated)
        val found = repository.findById(original.id)

        assertThat(found).isEqualTo(updated)
        assertThat(found?.updatedAt).isNotEqualTo(original.updatedAt)
        // id/teamId/projectKey/createdAt 은 갱신 대상이 아니다.
        assertThat(found?.id).isEqualTo(original.id)
        assertThat(found?.teamId).isEqualTo(original.teamId)
        assertThat(found?.projectKey).isEqualTo(original.projectKey)
        assertThat(found?.createdAt).isEqualTo(original.createdAt)
    }

    // ── deleteById(true/false) ───────────────────────────────────────────────────

    @Test
    fun `deleteById 는 존재하는 매핑을 삭제하고 true 를 반환한다`() {
        val mapping = newMapping()
        repository.save(mapping)

        val deleted = repository.deleteById(mapping.id)

        assertThat(deleted).isTrue()
        assertThat(repository.findById(mapping.id)).isNull()
    }

    @Test
    fun `deleteById 는 존재하지 않는 id 면 false 를 반환한다`() {
        assertThat(repository.deleteById(UUID.randomUUID())).isFalse()
    }

    // ── UNIQUE(team_id, project_key, channel_id) 위반 ────────────────────────────

    @Test
    fun `같은 (team_id, project_key, channel_id) 로 중복 저장하면 예외가 전파된다`() {
        val mapping = newMapping()
        repository.save(mapping)

        val duplicate = newMapping(teamId = mapping.teamId, projectKey = mapping.projectKey, channelId = mapping.channelId)

        assertThatThrownBy { repository.save(duplicate) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
