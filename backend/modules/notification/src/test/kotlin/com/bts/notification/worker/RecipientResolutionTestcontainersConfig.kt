// 수신자 해석 통합 테스트 전용 — pgmq 이미지 컨테이너 + DataSource + JwtDecoder 빈 설정
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
 * 수신자 해석 통합 테스트 전용 Testcontainers + DataSource 설정.
 *
 * [com.bts.notification.NotificationDeliveryTestcontainersConfig] 를 기반으로 하되,
 * cross-BC 포트 빈은 포함하지 않는다. 포트 빈은 [RecipientResolutionTestPortsConfig] 가 전담한다.
 * (두 Config 를 함께 사용하면 같은 이름의 빈이 충돌한다 — BeanDefinitionOverrideException.)
 *
 * ## pgmq 이미지
 * NotificationWorker 가 `pgmq.read / pgmq.send / pgmq.delete` 를 사용하므로
 * pgmq 확장이 사전 설치된 `quay.io/tembo/pg16-pgmq:latest` 이미지를 사용한다.
 *
 * ## JwtDecoder stub
 * WebSocketConfig 가 JwtDecoder 빈을 요구하나 이 테스트는 STOMP 를 사용하지 않으므로
 * 호출 시 JwtException 을 던지는 fail-closed stub 으로 충분하다.
 */
@TestConfiguration
class RecipientResolutionTestcontainersConfig {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container — pgmq 확장 사전 설치 이미지.
         *
         * NotificationDeliveryTestcontainersConfig 와 별도 DB 이름으로 격리한다.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_notification_rr_test")
                .withUsername("bts")
                .withPassword("bts_rr_test")
                .apply { start() }

        private var migrated = false

        /**
         * Flyway V400~V403 마이그레이션을 1회만 실행한다.
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
    fun jwtDecoder(): JwtDecoder = JwtDecoder { throw JwtException("STOMP not exercised in recipient resolution test") }

    /**
     * 테스트 전용 UserLookupPort fail-safe stub 빈.
     *
     * EmailChannelSender 가 [com.bts.shared.user.UserLookupPort] 를 주입받으므로 빈이 필요하다.
     * 이 테스트는 이메일 채널을 발송하지 않으므로 exists=false 로 fail-safe.
     */
    @Bean
    fun userLookupPort(): com.bts.shared.user.UserLookupPort =
        object : com.bts.shared.user.UserLookupPort {
            override fun exists(userId: java.util.UUID): Boolean = false
        }
}
