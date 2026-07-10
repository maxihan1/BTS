// automation 모듈 통합 테스트용 Testcontainers PostgreSQL singleton + DataSource/JdbcTemplate 배선 (FR-AT-01 Task 1)

package com.bts.automation

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
import java.sql.DriverManager
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
 * ## pgmq 이미지 + Flyway 선적용 (Task 2 이후 강화)
 * automation 은 pgmq 큐(q_automation_execution 소비·q_automation_events 발화)를 test-boot 에서
 * 사용하므로 컨테이너 이미지는 pgmq 확장이 사전 설치된 `quay.io/tembo/pg16-pgmq` 여야 한다
 * (`postgres:16-alpine` 은 pgmq 미탑재 → V301 `pgmq.create` 실패. ADR 2026-05-22-pgmq-postgres-image).
 * 컨테이너 기동 직후 `db/migration/automation` Flyway 를 한 번 적용해 Spring 컨텍스트 기반 후속
 * 테스트(Task 4 Repository·Task 6 컨트롤러·Task 7/8 워커)가 스키마+큐를 즉시 사용하게 한다.
 *
 * ## q_automation_events 는 test 픽스처로 생성 (plan-eng-review E1)
 * prod 에서는 producer 인 issue-tracking(Task 10)이 `q_automation_events` 를 소유·생성한다. automation
 * 소비자 테스트(Task 7 AutomationEventWorker)는 이 큐가 필요하므로 test 픽스처로만 생성한다
 * ([[bts-cross-bc-test-migration]] 패턴 — automation 마이그레이션에 넣으면 prod 이중생성이 되므로 금지).
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
        // quay.io/tembo/pg16-pgmq:latest — pgmq 확장 사전 설치. asCompatibleSubstituteFor 로 Testcontainers
        // 이미지 호환성 검증을 우회한다(SchemaMigrationTest 동형).
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        /**
         * JVM 단위 singleton pgmq PostgreSQL container.
         * `.apply { start() }` 로 JVM 시작 시 한 번만 기동(Ryuk 종료 시 자동 정리), `.also { }` 에서
         * Flyway 선적용 + q_automation_events 픽스처 생성을 1회 수행한다.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_automation_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }
                .also { container ->
                    Flyway.configure()
                        .dataSource(container.jdbcUrl, container.username, container.password)
                        .placeholderReplacement(false)
                        .locations("classpath:db/migration/automation")
                        .load()
                        .migrate()
                    // q_automation_events: prod 은 issue-tracking 소유(E1). automation 소비자 테스트용 픽스처.
                    DriverManager
                        .getConnection(container.jdbcUrl, container.username, container.password)
                        .use { c ->
                            c.prepareStatement("SELECT pgmq.create('q_automation_events')").use { it.execute() }
                        }
                }
    }
}
