// slack-integration 통합 테스트용 Testcontainers DataSource + Flyway V700 + JdbcTemplate + 권한 stub (FR-SL-01 Task 9)

package com.bts.slack

import org.flywaydb.core.Flyway
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/**
 * slack-integration `@SpringBootTest` 전용 Testcontainers + DataSource 설정 (FR-SL-01 Task 9).
 *
 * [SlackIntegrationTestBootApplication] 은 `DataSourceAutoConfiguration`/`FlywayAutoConfiguration` 을
 * 제외하므로(Task 1 인계) 실 DB 배선이 없다. Task 7 이 추가한 `@Repository JdbcSlackInstallRepository`
 * 가 [NamedParameterJdbcTemplate] 을 요구하고, Task 8 의 `SlackInstallService(@Service)` 가 cross-BC
 * `SystemPermissionResolver` 를 요구하면서 test-boot 컨텍스트 로드가 깨졌다([SlackContextLoadTest] 회귀).
 * 이 설정이 그 공백을 메운다.
 *
 * 제공 빈.
 * - Testcontainers PostgreSQL(16-alpine) + Flyway V700 을 적용한 [DataSource].
 * - [NamedParameterJdbcTemplate] / [JdbcTemplate] — repository 주입 + 테스트의 직접 검증용.
 * - [PlatformTransactionManager] — test-boot 앱의 `@EnableTransactionManagement` + repository/service
 *   `@Transactional` 프록시가 실제 트랜잭션을 여닫도록.
 * - [StubSystemPermissionResolver] — fail-closed 전역 관리자 판정 stub(테스트가 admin 을 명시 등록).
 *
 * ## JVM 단위 singleton container (교훈 concurrent-testcontainers-suite-flaky)
 * companion 의 `.apply { start() }` 로 JVM 시작 시 한 번만 기동하고 Ryuk 의 종료 시 자동 정리에 위임한다.
 * Flyway V700 은 [migrateOnce] 로 JVM 당 1회만 실행한다([JdbcSlackInstallRepositoryTest] /
 * notification `NotificationTestcontainersConfig` 동형).
 *
 * ## `@TestConfiguration` — 스캔 비대상
 * 이 클래스는 [SlackIntegrationTestBootApplication] 컴포넌트 스캔에 잡히지 않으며(`@TestConfiguration`
 * 은 명시 `@Import` 로만 등록), 이를 import 하지 않는 다른 테스트를 오염하지 않는다.
 */
@TestConfiguration
class SlackTestcontainersConfig {
    /**
     * Testcontainers PostgreSQL 에 Flyway V700 을 적용하고 연결하는 [DataSource] 빈.
     *
     * @return slack-integration 스키마(V700)가 적용된 [DriverManagerDataSource].
     */
    @Bean
    fun dataSource(): DataSource {
        migrateOnce()
        return DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    /**
     * [JdbcSlackInstallRepository] 가 주입받는 [NamedParameterJdbcTemplate] 빈.
     *
     * @param dataSource Testcontainers DataSource.
     */
    @Bean
    fun namedParameterJdbcTemplate(dataSource: DataSource): NamedParameterJdbcTemplate {
        return NamedParameterJdbcTemplate(dataSource)
    }

    /**
     * 통합 테스트가 `slack_installs` 를 직접 조회/정리할 때 쓰는 [JdbcTemplate] 빈.
     *
     * @param dataSource Testcontainers DataSource.
     */
    @Bean
    fun jdbcTemplate(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)

    /**
     * `@Transactional` AOP 프록시가 실제로 트랜잭션을 여닫도록 하는 [PlatformTransactionManager] 빈.
     *
     * @param dataSource Testcontainers DataSource.
     */
    @Bean
    fun transactionManager(dataSource: DataSource): PlatformTransactionManager {
        return DataSourceTransactionManager(dataSource)
    }

    /**
     * cross-BC 전역 관리자 판정 포트 — fail-closed [StubSystemPermissionResolver].
     *
     * 기본 admins 집합이 비어 있어 모두 거부(403)한다. 테스트가 admin actor UUID 를 명시 등록해야 통과한다.
     */
    @Bean
    fun systemPermissionResolver(): StubSystemPermissionResolver = StubSystemPermissionResolver()

    /**
     * cross-BC 사용자 표시명 해석 포트 — settable [StubUserLookupPort].
     *
     * [com.bts.slack.application.SlackInstallService] 생성자가 non-null `UserLookupPort` 를 요구하므로
     * test-boot 컨텍스트 로드를 위해 등록한다(빈 부재 시 [SlackContextLoadTest] 회귀). 기본 displayNames 가
     * 비어 있어 아무 이름도 해석하지 않으며, 테스트가 설치자 UUID→표시명을 명시 등록한다.
     */
    @Bean
    fun userLookupPort(): StubUserLookupPort = StubUserLookupPort()

    companion object {
        /**
         * JVM 단위 singleton PostgreSQL 16-alpine container.
         * `.apply { start() }` 로 JVM 시작 시 한 번만 기동. Ryuk 이 종료 시 자동 정리한다.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            // V701(FR-SL-02)이 q_slack_deliveries pgmq 큐를 생성하므로 pgmq 바이너리 포함 이미지 사용.
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_slack_web_it")
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
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/slack-integration")
                .load()
                .migrate()
            migrated = true
        }
    }
}
