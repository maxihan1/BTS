// EmailChannelSender 통합 테스트 전용 — MailHog 컨테이너 + @DynamicPropertySource + UserLookupPort fake 제공
@file:Suppress("DEPRECATION") // JooqExceptionTranslator: Spring Boot 3.3 deprecated, package-private 후계 미공개

package com.bts.notification.channel

import com.bts.shared.issue.IssueRecipientLookupPort
import com.bts.shared.issue.IssueRecipients
import com.bts.shared.user.UserLookupPort
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DataSourceConnectionProvider
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.DefaultDSLContext
import org.jooq.impl.DefaultExecuteListenerProvider
import org.springframework.boot.autoconfigure.jooq.JooqExceptionTranslator
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import javax.sql.DataSource

/**
 * [EmailChannelSenderIntegrationTest] 전용 테스트 인프라 설정.
 *
 * ## 컨테이너
 * - PostgreSQL 16-alpine: notification 스키마 V400~V401 Flyway 마이그레이션 적용.
 * - MailHog v1.0.1: 테스트용 가짜 SMTP 서버(받은 메일을 HTTP API로 조회 가능).
 *   포트 1025(SMTP) + 8025(HTTP API).
 *
 * ## @DynamicPropertySource
 * `spring.mail.host/port` 를 MailHog 컨테이너 좌표로 주입.
 * Spring Boot MailSenderAutoConfiguration 이 그 host 로 [org.springframework.mail.javamail.JavaMailSender] 를 생성한다.
 * `bts.notification.email.from` 도 테스트값으로 고정한다.
 *
 * ## UserLookupPort fake
 * - [EmailChannelSenderIntegrationTest.KNOWN_RECIPIENT_ID] → [EmailChannelSenderIntegrationTest.KNOWN_RECIPIENT_EMAIL]
 * - [EmailChannelSenderIntegrationTest.UNKNOWN_RECIPIENT_ID] → null (이메일 미존재 시나리오)
 *
 * ## 격리
 * 기존 인앱 E2E([com.bts.notification.NotificationDeliveryEndToEndIntegrationTest]) 와 완전히 분리된
 * 별도 컨테이너를 사용한다.
 * (memory: concurrent-testcontainers-suite-flaky — JVM 단위 singleton 패턴 표준.)
 */
@TestConfiguration
class EmailChannelSenderIntegrationTestConfig {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL 컨테이너.
         *
         * EmailChannelSender 통합 테스트 전용 DB.
         * `.apply { start() }` 로 JVM 초기화 시점에 1회 기동, Ryuk 이 JVM 종료 시 자동 정리.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_notification_email_test")
                .withUsername("bts")
                .withPassword("bts_email_test")
                .apply { start() }

        /**
         * JVM 단위 singleton MailHog 컨테이너.
         *
         * MailHog: 테스트용 가짜 SMTP 서버. 포트 1025(SMTP) + 8025(HTTP API).
         * `.apply { start() }` 로 JVM 초기화 시점에 1회 기동.
         */
        @JvmStatic
        val mailhog: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("mailhog/mailhog:v1.0.1"))
                .withExposedPorts(1025, 8025)
                .apply { start() }

        private var migrated = false

        /**
         * Flyway V400~V401 마이그레이션을 1회만 실행한다.
         *
         * - V400: notification_policies DDL
         * - V401: 기본 정책 시드
         */
        @Synchronized
        fun migrateOnce(
            jdbcUrl: String,
            username: String,
            password: String,
        ) {
            if (migrated) return
            Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/notification")
                .load()
                .migrate()
            migrated = true
        }

    }

    /**
     * Testcontainers PostgreSQL 에 연결하는 [DataSource] 빈.
     *
     * Flyway V400~V401 마이그레이션을 적용한 뒤 DataSource 를 반환한다.
     */
    @Bean
    fun dataSource(): DataSource {
        migrateOnce(postgres.jdbcUrl, postgres.username, postgres.password)
        return DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    /**
     * jOOQ [DSLContext] 빈.
     *
     * jOOQ: SQL 을 코드로 안전하게 작성하는 라이브러리.
     * [JooqExceptionTranslator] 로 UNIQUE 위반을 DuplicateKeyException 으로 변환한다.
     */
    @Bean
    fun dslContext(dataSource: DataSource): DSLContext {
        val configuration =
            DefaultConfiguration()
                .set(DataSourceConnectionProvider(TransactionAwareDataSourceProxy(dataSource)))
                .set(SQLDialect.POSTGRES)
                .set(DefaultExecuteListenerProvider(JooqExceptionTranslator()))
        return DefaultDSLContext(configuration)
    }

    /**
     * Spring 트랜잭션 매니저 빈.
     */
    @Bean
    fun transactionManager(dataSource: DataSource): PlatformTransactionManager {
        return DataSourceTransactionManager(dataSource)
    }

    /**
     * 테스트 전용 [UserLookupPort] fake 빈.
     *
     * [EmailChannelSender] 가 수신자 이메일을 cross-BC 조회할 때 사용하는 포트.
     * identity-access BC 가 없는 notification 단독 테스트 컨텍스트에서 fake 를 제공한다.
     * (memory: crossbc-resolver-nullable-fail-open — 빈 부재 시 fail-open 방지를 위해 명시적 fake 제공.)
     *
     * ## 매핑 규칙
     * - [EmailChannelSenderIntegrationTest.KNOWN_RECIPIENT_ID] → [EmailChannelSenderIntegrationTest.KNOWN_RECIPIENT_EMAIL]
     * - [EmailChannelSenderIntegrationTest.UNKNOWN_RECIPIENT_ID] → null (이메일 부재 시나리오)
     * - 그 외 UUID → null (fail-safe)
     */
    @Bean
    fun userLookupPort(): UserLookupPort =
        object : UserLookupPort {
            override fun exists(userId: UUID): Boolean =
                userId == EmailChannelSenderIntegrationTest.KNOWN_RECIPIENT_ID

            override fun findEmailById(userId: UUID): String? =
                when (userId) {
                    EmailChannelSenderIntegrationTest.KNOWN_RECIPIENT_ID ->
                        EmailChannelSenderIntegrationTest.KNOWN_RECIPIENT_EMAIL
                    else -> null
                }
        }

    /**
     * [com.bts.notification.recipient.EventRecipientResolver] / [com.bts.notification.worker.NotificationWorker]
     * 가 요구하는 cross-BC [IssueRecipientLookupPort] 빈.
     *
     * 이 테스트는 [EmailChannelSender] 직접 호출만 검증하므로 워커 fanout 경로를 사용하지 않는다.
     * 빈 수신자를 반환하는 fail-safe stub 을 둔다.
     * (memory: crossbc-resolver-nullable-fail-open — 빈 부재 시 fail-open 위험 차단.)
     */
    @Bean
    fun issueRecipientLookupPort(): IssueRecipientLookupPort =
        object : IssueRecipientLookupPort {
            override fun findRecipients(issueKey: String): IssueRecipients = IssueRecipients.empty()
        }

    /**
     * [com.bts.notification.config.WebSocketConfig] 가 요구하는 [JwtDecoder] 빈.
     *
     * 이 테스트는 WebSocket 트래픽을 사용하지 않으므로 호출 시 fail-closed 로 예외를 던지는 stub 을 둔다.
     */
    @Bean
    fun jwtDecoder(): JwtDecoder {
        return JwtDecoder { throw JwtException("WebSocket JWT not exercised in email integration test") }
    }
}
