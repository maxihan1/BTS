// JdbcSlackChannelBroadcastDedupRepository 통합 테스트 — 채널 브로드캐스트 dedup 저장소 round-trip (FR-SL-06 PR-B Task 3)

package com.bts.slack.persistence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * [JdbcSlackChannelBroadcastDedupRepository] 통합 테스트 (FR-SL-06 PR-B Task 3).
 *
 * [JdbcSlackChannelMappingRepositoryIntegrationTest] 와 동형 — Spring 컨텍스트 없이 Testcontainers
 * PostgreSQL(pgmq 이미지)에 slack-integration 전체 마이그레이션(V700~V705)을 직접 적용하고
 * [JdbcTemplate] 을 손수 구성해 리포지토리를 직접 생성한다.
 *
 * ## 검증 시나리오
 * - `existsPosted(key)=false → recordPosted(key) → existsPosted(key)=true` — 미기록→기록→기록됨 왕복.
 * - `recordPosted` 중복 호출 멱등 — 같은 key 를 두 번 기록해도 예외 없이 통과한다
 *   (pgmq at-least-once 재전달 시 같은 (이벤트,채널)이 두 번 게시되지 않도록 하는 단일 방어선).
 */
class JdbcSlackChannelBroadcastDedupRepositoryTest {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container(pgmq 바이너리 포함 — V705 가 `pgmq.create` 를 실행).
         * (memory: concurrent-testcontainers-suite-flaky — singleton 패턴 표준.)
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_slack_broadcast_dedup_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        @JvmStatic
        private var migrated = false

        /** Flyway V700~V705 마이그레이션을 JVM 당 1회만 실행한다. */
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

    private lateinit var repository: JdbcSlackChannelBroadcastDedupRepository

    @BeforeEach
    fun setUp() {
        migrateOnce()
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        val jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.update("DELETE FROM slack_channel_broadcast_log")
        repository = JdbcSlackChannelBroadcastDedupRepository(jdbcTemplate)
    }

    @Test
    fun `existsPosted 는 기록 전 false, recordPosted 후 true 를 반환한다`() {
        val dedupKey = "evt-abc123:C0999"

        assertThat(repository.existsPosted(dedupKey)).isFalse()

        repository.recordPosted(dedupKey)

        assertThat(repository.existsPosted(dedupKey)).isTrue()
    }

    @Test
    fun `recordPosted 는 같은 key 를 두 번 기록해도 예외 없이 멱등하다`() {
        val dedupKey = "evt-abc123:C0999"

        repository.recordPosted(dedupKey)

        assertThatCode { repository.recordPosted(dedupKey) }.doesNotThrowAnyException()
        assertThat(repository.existsPosted(dedupKey)).isTrue()
    }

    @Test
    fun `existsPosted 는 서로 다른 key 를 독립적으로 판정한다`() {
        repository.recordPosted("evt-abc123:C0999")

        assertThat(repository.existsPosted("evt-abc123:C0999")).isTrue()
        assertThat(repository.existsPosted("evt-abc123:C1111")).isFalse()
    }
}
