// slack-integration 통합 테스트용 Testcontainers DataSource + Flyway V700 + JdbcTemplate + 권한 stub (FR-SL-01 Task 9)

package com.bts.slack

import com.bts.slack.message.SlackUserLookupClient
import com.slack.api.methods.MethodsClient
import io.mockk.mockk
import org.flywaydb.core.Flyway
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
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
 * - `@Primary` mock [SlackUserLookupClient] — 실 Slack `users.lookupByEmail` 호출을 회피(FR-SL-02 D6 Task 5,
 *   [slackUserLookupClient] 참고).
 * - [StubIssueUnfurlPort] — cross-BC 결합 fail-closed 이슈 카드 조회 stub(FR-SL-03 Task 12, issueKey 별
 *   시드 가능, [issueUnfurlPort] 참고).
 * - `@Primary` mock [MethodsClient] — `chat.unfurl`/`chat.postMessage` 등 모든 Slack SDK 호출을 실
 *   네트워크 없이 검증 가능하게 한다(FR-SL-03 Task 12, [slackMethodsClient] 참고).
 * - [StubIssueCompletionOptionsPort] — cross-BC 결합 fail-closed 완료 옵션 조회 stub(FR-SL-05 PR1
 *   Task 9, issueKey 별 시드 가능, [issueCompletionOptionsPort] 참고).
 * - [StubIssueTransitionPort] — cross-BC 완료 전이 실행 stub(FR-SL-05 PR1 Task 9, 성공 결과/실패 예외
 *   시드 가능, [issueTransitionPort] 참고).
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

    /**
     * Slack `users.lookupByEmail` 클라이언트 — 실 Slack 호출을 회피하는 mockk 대체 (FR-SL-02 D6 Task 5).
     *
     * 컴포넌트 스캔된 실 `SlackUserLookupClient`(외부 Slack API 호출)와 함께 이 빈도 등록되어 타입이
     * 충돌하므로 `@Primary` 로 이 mock 이 주입 우선순위를 가진다([SlackInstallIntegrationTest.FakeOAuthConfig]
     * 동형). 테스트가 `every { ... } returns ...` 로 [com.bts.slack.message.SlackUserLookupResult] 를
     * 시나리오별로 주입/변경한다(연결 happy/스코프부족/미발견/일시오류).
     */
    @Bean
    @Primary
    fun slackUserLookupClient(): SlackUserLookupClient = mockk()

    /**
     * cross-BC 결합 fail-closed 이슈 카드 조회 포트 — settable [StubIssueUnfurlPort] (FR-SL-03 Task 12).
     *
     * [com.bts.slack.unfurl.SlackUnfurlService] 생성자가 non-null [com.bts.shared.issue.IssueUnfurlPort]
     * 를 요구하므로, test-boot 컨텍스트 로드를 위해 등록한다(빈 부재 시 [SlackContextLoadTest] 회귀). 기본
     * `visibleIssues` 가 비어 있어 아무 issueKey 도 가시로 판정하지 않으며, 테스트가 issueKey → 카드를
     * 명시 등록한다.
     */
    @Bean
    fun issueUnfurlPort(): StubIssueUnfurlPort = StubIssueUnfurlPort()

    /**
     * Slack 공식 SDK `MethodsClient` — mockk 대체 (FR-SL-03 Task 12).
     *
     * 컴포넌트 스캔된 [com.bts.slack.message.SlackUnfurlClient]/[com.bts.slack.message.SlackMessageClient]
     * 등은 생성자 기본값 `Slack.getInstance().methods()`(자격증명 없는 실 SDK 인스턴스)로 부팅은 안전하지만,
     * 실제로 호출하면 네트워크 오류가 난다. `@Primary` 로 이 mock 을 주입 우선순위로 등록해 컴포넌트
     * 스캔된 클라이언트들이 모두 이 mock `MethodsClient` 를 받게 하고, 통합 테스트가 `chatUnfurl` 등
     * 호출/미호출과 인자를 검증한다([SlackUnfurlEndToEndTest] 참고). `relaxed = true` 라 스텁하지 않은
     * 호출도 예외 없이 기본값을 반환한다 — 이 mock 을 쓰지 않는 다른 통합 테스트(연결/설치)에서 우연히
     * 호출돼도 컨텍스트가 깨지지 않는다.
     */
    @Bean
    @Primary
    fun slackMethodsClient(): MethodsClient = mockk(relaxed = true)

    /**
     * cross-BC 결합 fail-closed 완료 옵션 조회 포트 — settable [StubIssueCompletionOptionsPort]
     * (FR-SL-05 PR1 Task 9).
     *
     * [com.bts.slack.interaction.SlackInteractionService] 생성자가 non-null
     * [com.bts.shared.issue.IssueCompletionOptionsPort] 를 요구하므로, test-boot 컨텍스트 로드를 위해
     * 등록한다(빈 부재 시 [SlackContextLoadTest] 회귀). 기본 `completionOptionsByIssueKey` 가 비어 있어
     * 아무 issueKey 도 완료 가능으로 판정하지 않으며, 테스트가 issueKey → 완료 옵션을 명시 등록한다.
     */
    @Bean
    fun issueCompletionOptionsPort(): StubIssueCompletionOptionsPort = StubIssueCompletionOptionsPort()

    /**
     * cross-BC 완료 전이 실행 포트 — settable [StubIssueTransitionPort] (FR-SL-05 PR1 Task 9).
     *
     * [com.bts.slack.interaction.SlackInteractionService] 생성자가 non-null
     * [com.bts.shared.board.IssueTransitionPort] 를 요구하므로, test-boot 컨텍스트 로드를 위해 등록한다
     * (빈 부재 시 [SlackContextLoadTest] 회귀). 시드되지 않은 상태로 호출하면 명시 오류로 실패하므로,
     * 테스트가 `succeedWith`/`failWith` 로 시나리오를 먼저 시드해야 한다.
     */
    @Bean
    fun issueTransitionPort(): StubIssueTransitionPort = StubIssueTransitionPort()

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
