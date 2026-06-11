// NotificationTestcontainersBase — Testcontainers PostgreSQL singleton + Flyway V400~V401 migrate.
// IssueTestcontainersBase 패턴 준수 (JVM 단위 라이프사이클 + Ryuk 자동 정리).

package com.bts.notification.support

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Notification 모듈 통합 테스트 공통 기반 클래스.
 *
 * **JVM 단위 singleton container 패턴** — IssueTestcontainersBase 와 동일.
 *
 * `@Container` annotation 대신 companion object 에서 `.apply { start() }` 로
 * JVM 라이프사이클에 컨테이너를 바인딩한다.
 * JVM 종료 시 Ryuk 이 컨테이너를 자동 정리하므로 명시적 stop 불필요.
 *
 * **사용 가이드.**
 * - 자식 클래스에 `@Testcontainers` annotation 을 붙이지 않는다.
 * - `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 가 상속되므로 자식 클래스도 동일 적용.
 * - `dsl` 과 `repository` 는 `bootstrap()` 이후 초기화됨 — `@BeforeAll` 이전 접근 불가.
 *
 * **자식 클래스 최소 선언 예시.**
 * ```kotlin
 * @TestMethodOrder(MethodOrderer.OrderAnnotation::class)
 * class NotificationPolicyRepositoryIntegrationTest : NotificationTestcontainersBase() { ... }
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
open class NotificationTestcontainersBase {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container.
         * `.apply { start() }` 로 JVM 시작 시점에 한 번만 기동되며, Ryuk 이 JVM 종료 시 자동 정리.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("postgres:16-alpine"),
            )
                .withDatabaseName("bts_notification_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }
    }

    // @TestInstance(PER_CLASS) 덕분에 instance 멤버로 선언 가능
    lateinit var dsl: DSLContext
        protected set

    private var bootstrapped = false

    /**
     * Flyway 설정 훅 — 자식 클래스가 필요 시 override 가능.
     *
     * 기본 구현 = `placeholderReplacement(false)` + `classpath:db/migration/notification`.
     */
    protected open fun configureFlyway(builder: FluentConfiguration): FluentConfiguration =
        builder
            .placeholderReplacement(false)
            .locations("classpath:db/migration/notification")

    /**
     * JVM 당 1회 실행 — Flyway migrate + DSLContext 생성.
     * `@TestInstance(PER_CLASS)` + `@BeforeAll` 조합으로 instance 메서드로 동작.
     */
    @BeforeAll
    fun bootstrap() {
        if (bootstrapped) return

        // Flyway — DB 스키마 변경을 버전 관리하는 도구 (V400, V401 마이그레이션 적용)
        configureFlyway(
            Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password),
        ).load().migrate()

        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )

        // jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES)

        bootstrapped = true
    }

    /**
     * 각 테스트가 독립적으로 실행되도록 테스트마다 사용자가 삽입한 행(시드 제외)을 삭제한다.
     *
     * V401 시드(created_by=NULL)는 유지하고, 테스트에서 삽입한 행(created_by IS NOT NULL)만 제거한다.
     * 이 전략으로 시드 기반 테스트는 매 테스트 동일한 베이스라인을 보장받는다.
     */
    @BeforeEach
    fun cleanNonSeedPolicies() {
        dsl.execute("DELETE FROM notification_policies WHERE created_by IS NOT NULL")
    }
}
