// notification 통합 테스트 전용 Testcontainers + DataSource + Flyway 설정
@file:Suppress("DEPRECATION") // JooqExceptionTranslator: Spring Boot 3.3 deprecated, package-private 후계 미공개

package com.bts.notification

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
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/**
 * notification 모듈 `@SpringBootTest` 전용 Testcontainers + DataSource 설정.
 *
 * Testcontainers PostgreSQL 16-alpine 을 JVM 단위 singleton 으로 기동하고,
 * Flyway V400(DDL) + V401(시드) 를 적용한 뒤 DataSource / DSLContext / TransactionManager 빈을 제공한다.
 *
 * [NotificationTestcontainersBase] 와 동일한 singleton 패턴을 따른다.
 * (memory: concurrent-testcontainers-suite-flaky — JVM 단위 singleton으로 컨테이너 재사용.)
 *
 * ## 주의 사항
 * - Spring Boot FlywayAutoConfiguration 은 [NotificationTestBootApplication] 에서 exclude.
 *   여기서 직접 Flyway 를 실행해 V400/V401 마이그레이션을 적용한다.
 * - DataSource 빈이 `@SpringBootTest` 컨텍스트에 등록되므로 `spring.datasource.*` 프로퍼티 불필요.
 * - `@TestConfiguration` → 자동으로 test 전용 컨텍스트에만 포함되며 prod 컨텍스트를 오염하지 않는다.
 */
@TestConfiguration
class NotificationTestcontainersConfig {

    companion object {
        /**
         * JVM 단위 singleton PostgreSQL 16-alpine container.
         *
         * `.apply { start() }` 로 companion object 초기화 시점에 한 번만 기동.
         * Ryuk 이 JVM 종료 시 자동 정리하므로 명시적 stop 불필요.
         * (memory: concurrent-testcontainers-suite-flaky — singleton 패턴 표준.)
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_notification_e2e_test")
                .withUsername("bts")
                .withPassword("bts_e2e_test")
                .apply { start() }

        private var migrated = false

        /**
         * Flyway V400(DDL) + V401(시드) 마이그레이션을 1회만 실행한다.
         *
         * 동일 JVM 에서 여러 테스트 클래스가 이 설정을 공유해도 migrate 는 한 번만 수행된다.
         */
        @Synchronized
        fun migrateOnce(jdbcUrl: String, username: String, password: String) {
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
     * Flyway 마이그레이션을 여기서 직접 실행해 V400/V401 이 적용된 상태로 DataSource 를 제공한다.
     *
     * @return Testcontainers DB 에 연결된 [DriverManagerDataSource]
     */
    @Bean
    fun dataSource(): DataSource {
        migrateOnce(postgres.jdbcUrl, postgres.username, postgres.password)
        return DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    /**
     * jOOQ [DSLContext] 빈.
     *
     * SQL 을 코드로 안전하게 작성하는 라이브러리(jOOQ)의 핵심 진입점.
     *
     * [JooqExceptionTranslator] 를 [DefaultExecuteListenerProvider] 로 등록해
     * jOOQ 의 [org.jooq.exception.DataAccessException] 이 Spring 의
     * [org.springframework.dao.DuplicateKeyException] 등으로 변환되도록 한다.
     * 이 변환이 없으면 서비스의 catch (DuplicateKeyException) 블록이 동작하지 않아 500 이 반환된다.
     * (memory: cartesian-product-jooq-leftjoin-count — prod 환경 Spring DataSource 시 자동 변환됨.)
     *
     * `@Suppress("DEPRECATION")` — [JooqExceptionTranslator] 가 Spring Boot 3.3 에서 deprecated 됐지만
     * package-private 후계 클래스에 직접 접근이 불가하므로 테스트 설정 클래스에서 억제한다.
     *
     * @param dataSource Testcontainers DataSource
     * @return Spring 예외 변환기가 등록된 PostgreSQL 방언 [DSLContext]
     */
    @Bean
    fun dslContext(dataSource: DataSource): DSLContext {
        val configuration = DefaultConfiguration()
            .set(DataSourceConnectionProvider(TransactionAwareDataSourceProxy(dataSource)))
            .set(SQLDialect.POSTGRES)
            .set(DefaultExecuteListenerProvider(JooqExceptionTranslator()))
        return DefaultDSLContext(configuration)
    }

    /**
     * Spring 트랜잭션 매니저 빈.
     *
     * `@Transactional` AOP 프록시가 실제로 동작하도록 [DataSourceTransactionManager] 를 제공한다.
     *
     * @param dataSource Testcontainers DataSource
     * @return [PlatformTransactionManager] 구현체
     */
    @Bean
    fun transactionManager(dataSource: DataSource): PlatformTransactionManager =
        DataSourceTransactionManager(dataSource)
}
