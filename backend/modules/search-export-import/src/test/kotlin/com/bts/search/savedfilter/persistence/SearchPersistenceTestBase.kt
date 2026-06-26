// search-export-import BC 영속성 통합테스트 공통 기반 클래스 — Testcontainers PostgreSQL singleton + Flyway V600~ migrate (FR-SR-03)

package com.bts.search.savedfilter.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * search-export-import 모듈 영속성 통합 테스트 공통 기반 클래스.
 *
 * **JVM 단위 singleton container 패턴** — notification BC 의 NotificationTestcontainersBase 와 동일.
 *
 * `@Container` 어노테이션 대신 companion object 에서 `.apply { start() }` 로
 * JVM 라이프사이클에 컨테이너를 바인딩한다. JVM 종료 시 Ryuk 이 컨테이너를 자동 정리하므로
 * 명시적 stop 불필요. 다중 동시 실행 워커 크래시(concurrent-testcontainers-suite-flaky)를 회피한다.
 *
 * Flyway 는 `classpath:db/migration/search-export-import` 의 V600~ 마이그레이션을 적용한다.
 *
 * **자식 클래스 최소 선언 예시.**
 * ```kotlin
 * class JooqSavedFilterRepositoryIntegrationTest : SearchPersistenceTestBase() { ... }
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
open class SearchPersistenceTestBase {

    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container.
         * `.apply { start() }` 로 JVM 시작 시점에 한 번만 기동되며, Ryuk 이 JVM 종료 시 자동 정리.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_search_persist_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }
    }

    /** jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점. bootstrap() 이후 초기화됨. */
    lateinit var dsl: DSLContext
        protected set

    private var bootstrapped = false

    /**
     * Flyway 설정 훅 — 자식 클래스가 필요 시 override 가능.
     * 기본 구현 = placeholderReplacement(false) + search-export-import V600~ 마이그레이션 경로.
     */
    protected open fun configureFlyway(builder: FluentConfiguration): FluentConfiguration =
        builder
            .placeholderReplacement(false)
            .locations("classpath:db/migration/search-export-import")

    /**
     * JVM 당 1회 실행 — Flyway migrate + DSLContext 생성.
     * `@TestInstance(PER_CLASS)` + `@BeforeAll` 조합으로 instance 메서드로 동작.
     */
    @BeforeAll
    fun bootstrap() {
        if (bootstrapped) return

        // Flyway — DB 스키마 변경을 버전 관리하는 도구 (V600 마이그레이션 적용)
        configureFlyway(
            Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password),
        ).load().migrate()

        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )

        dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        bootstrapped = true
    }
}
