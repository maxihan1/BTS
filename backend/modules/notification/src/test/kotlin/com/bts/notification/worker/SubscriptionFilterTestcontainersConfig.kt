// 구독 필터 통합 테스트 전용 — pgmq 이미지 컨테이너 + V400~V404 마이그레이션 + JwtDecoder stub
@file:Suppress("DEPRECATION") // JooqExceptionTranslator: Spring Boot 3.3 deprecated, package-private 후계 미공개

package com.bts.notification.worker

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
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/**
 * 구독 필터 통합 테스트 전용 Testcontainers + DataSource 설정.
 *
 * [RecipientResolutionTestcontainersConfig] 와 같은 구조이나,
 * - DB 이름이 다르므로 컨테이너가 격리된다.
 * - **V404 (user_notification_subs 테이블) 까지 마이그레이션** 한다 — 구독 행 삽입이 필요하기 때문.
 * - UserLookupPort stub 은 [SubscriptionFilterTestPortsConfig] 에서 분리 제공한다.
 *
 * ## pgmq 이미지
 * NotificationWorker 가 pgmq.read / pgmq.delete 를 사용하므로
 * pgmq 확장이 사전 설치된 `quay.io/tembo/pg16-pgmq:latest` 이미지를 사용한다.
 */
@TestConfiguration
class SubscriptionFilterTestcontainersConfig {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container.
         *
         * 다른 테스트 컨텍스트와 격리하기 위해 별도 DB 이름을 사용한다.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_notification_subfilter_test")
                .withUsername("bts")
                .withPassword("bts_subfilter_test")
                .apply { start() }

        private var migrated = false

        /**
         * Flyway V400~V404 마이그레이션을 1회만 실행한다.
         *
         * - V400: notification_policies 테이블
         * - V401: 기본 정책 시드
         * - V402: notifications 테이블
         * - V403: issue.mentioned 정책 시드
         * - V404: user_notification_subs 테이블 (구독 필터 핵심)
         */
        @Synchronized
        fun migrateOnce(
            jdbcUrl: String,
            username: String,
            password: String,
        ) {
            if (migrated) return

            java.sql.DriverManager.getConnection(jdbcUrl, username, password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE EXTENSION IF NOT EXISTS pgmq CASCADE")
                    stmt.execute("SELECT pgmq.create('q_issue_events')")
                }
            }

            Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/notification")
                .load()
                .migrate()

            migrated = true
        }
    }

    @Bean
    fun dataSource(): DataSource {
        migrateOnce(postgres.jdbcUrl, postgres.username, postgres.password)
        return DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    @Bean
    fun dslContext(dataSource: DataSource): DSLContext {
        val configuration =
            DefaultConfiguration()
                .set(DataSourceConnectionProvider(TransactionAwareDataSourceProxy(dataSource)))
                .set(SQLDialect.POSTGRES)
                .set(DefaultExecuteListenerProvider(JooqExceptionTranslator()))
        return DefaultDSLContext(configuration)
    }

    @Bean
    fun transactionManager(dataSource: DataSource): PlatformTransactionManager {
        return DataSourceTransactionManager(dataSource)
    }

    /**
     * 테스트 전용 JwtDecoder stub — 이 테스트는 STOMP 를 사용하지 않으므로 호출되지 않는다.
     *
     * WebSocketConfig 가 JwtDecoder 빈을 요구하므로 fail-closed stub 을 제공한다.
     */
    @Bean
    @Primary
    fun jwtDecoder(): JwtDecoder = JwtDecoder { throw JwtException("STOMP not exercised in subscription filter test") }
}
