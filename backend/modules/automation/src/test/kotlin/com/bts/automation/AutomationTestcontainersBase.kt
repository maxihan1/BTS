// automation 모듈 통합 테스트용 Testcontainers PostgreSQL singleton + DataSource/JdbcTemplate 배선 (FR-AT-01 Task 1)

package com.bts.automation

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
 * automation 모듈 `@SpringBootTest` 전용 Testcontainers + DataSource 설정 (FR-AT-01 Task 1).
 *
 * ## 왜 Task 1부터 DataSource/JdbcTemplate을 배선하는가
 * Task 4가 첫 `@Repository`(JdbcTemplate 기반)를 추가할 때 이 빈들이 없으면 test-boot 컨텍스트
 * 로드가 `NoSuchBeanDefinitionException`으로 깨진다([[new-bc-first-repository-testboot-context-regression]]).
 * 이를 예방하기 위해 부트스트랩 시점부터 DataSource/JdbcTemplate/트랜잭션 매니저를 미리 배선한다
 * (plan-eng-review E6).
 *
 * ## JVM 단위 singleton container ([[concurrent-testcontainers-suite-flaky]])
 * `@Container` 애너테이션 대신 companion object 의 `.apply { start() }` 로 JVM 라이프사이클에
 * 컨테이너를 바인딩한다. JVM 종료 시 Ryuk 이 자동 정리하므로 명시적 stop 이 불필요하고,
 * 여러 테스트 클래스가 동시 실행돼도 컨테이너를 재사용해 stale port 충돌을 회피한다
 * (slack-integration `SlackTestcontainersConfig` 동형).
 *
 * ## `@TestConfiguration` — 스캔 비대상
 * 이 클래스는 [AutomationTestBootApplication] 컴포넌트 스캔에 잡히지 않으며(`@TestConfiguration`
 * 은 명시 `@Import` 로만 등록), 이를 import 하지 않는 다른 테스트를 오염하지 않는다.
 *
 * ## Flyway 마이그레이션은 이 클래스 책임이 아님
 * Task 1 시점에는 마이그레이션이 없다(Task 2 가 `db/migration/automation` 을 추가). 스키마가
 * 필요한 후속 테스트(SchemaMigrationTest 등)는 [postgres] 컨테이너를 직접 참조해 자체 Flyway
 * 를 실행한다(notification `NotificationTestcontainersConfig` 선례).
 */
@TestConfiguration
class AutomationTestcontainersBase {
    /**
     * Testcontainers PostgreSQL 에 연결하는 [DataSource] 빈.
     *
     * @return Testcontainers DB 에 연결된 [DriverManagerDataSource]
     */
    @Bean
    fun dataSource(): DataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

    /**
     * 후속 `@Repository`(Task 4)가 주입받는 [NamedParameterJdbcTemplate] 빈.
     *
     * @param dataSource Testcontainers DataSource.
     */
    @Bean
    fun namedParameterJdbcTemplate(dataSource: DataSource): NamedParameterJdbcTemplate {
        return NamedParameterJdbcTemplate(dataSource)
    }

    /**
     * 통합 테스트가 테이블을 직접 조회/정리할 때 쓰는 [JdbcTemplate] 빈.
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

    companion object {
        /**
         * JVM 단위 singleton PostgreSQL 16-alpine container.
         * `.apply { start() }` 로 JVM 시작 시 한 번만 기동. Ryuk 이 종료 시 자동 정리한다.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_automation_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }
    }
}
