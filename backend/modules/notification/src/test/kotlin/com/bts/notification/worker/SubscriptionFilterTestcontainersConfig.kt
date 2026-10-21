// 구독 필터 통합 테스트 전용 — pgmq 이미지 컨테이너 + V400~V404 마이그레이션 + JwtDecoder stub
@file:Suppress("DEPRECATION") // JooqExceptionTranslator: Spring Boot 3.3 deprecated, package-private 후계 미공개

package com.bts.notification.worker

import com.bts.notification.support.SharedPostgres
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
        /** [SharedPostgres] 템플릿을 복제한 이 설정 전용 DB. 컨테이너와 마이그레이션은 공용이다. */
        @JvmStatic
        val postgres = SharedPostgres.freshDatabase()
    }

    @Bean
    fun dataSource(): DataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

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
