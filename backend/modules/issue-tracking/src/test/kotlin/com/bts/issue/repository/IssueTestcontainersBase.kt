// IssueTestcontainersBase — Testcontainers PostgreSQL singleton + Flyway migrate.
// PR #8 learning #2 패턴 (JVM 단위 라이프사이클 + Ryuk 자동 정리).

package com.bts.issue.repository

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * IssueRepository 통합 테스트 공통 기반 클래스.
 *
 * **PR #8 learning #2 패턴 — JVM 단위 singleton container.**
 *
 * `@Container` annotation 대신 companion object 에서 `.apply { start() }` 로 JVM 라이프사이클에
 * 컨테이너를 바인딩한다. `@Container` 기본 라이프사이클은 클래스 단위이므로, 자식 클래스가
 * 동일한 Spring ApplicationContext cache key 를 공유하면 첫 클래스 종료 시 container stop →
 * 두 번째 클래스가 stopped container 의 stale port 로 연결 시도 → 실패. JVM 종료 시 Ryuk 이
 * container 를 자동 정리하므로 명시적 stop 불필요.
 *
 * **사용 가이드.**
 * - 자식 클래스에 `@Testcontainers` annotation 을 붙이지 않는다 — JVM singleton 라이프사이클 사용.
 * - `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 이 선언되어 있으므로 자식 클래스도 동일 적용.
 * - Flyway 설정을 변경하려면 `configureFlyway(builder)` 를 override 한다.
 *   기본 = placeholderReplacement(false) + classpath:db/migration/issue-tracking.
 *   ```kotlin
 *   override fun configureFlyway(builder: FluentConfiguration) =
 *       super.configureFlyway(builder).locations("classpath:db/migration", "classpath:db/test-migration")
 *   ```
 * - `dsl`, `testProjectId`, `repository` 는 `bootstrap()` 이후 초기화됨 — `@BeforeAll` 이전 접근 불가.
 *
 * **자식 클래스 최소 선언 예시.**
 * ```kotlin
 * @TestMethodOrder(MethodOrderer.OrderAnnotation::class)
 * class IssueRepositoryTest : IssueTestcontainersBase() { ... }
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class IssueTestcontainersBase {
    companion object {
        /**
         * quay.io/tembo/pg16-pgmq:latest 이미지로 생성되는 JVM 단위 singleton PostgreSQL container.
         * `.apply { start() }` 로 JVM 시작 시점에 한 번만 기동되며, Ryuk 이 JVM 종료 시 자동 정리.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }
    }

    // @TestInstance(PER_CLASS) 덕분에 instance 멤버로 선언 가능 — lateinit 으로 bootstrap 이후 초기화
    lateinit var dsl: DSLContext
        protected set

    lateinit var testProjectId: UUID
        protected set

    lateinit var repository: IssueRepository
        protected set

    private var bootstrapped = false

    /**
     * Flyway 설정 훅 — 자식 클래스가 placeholder 사용 여부 등을 override 가능.
     *
     * 기본 구현 = `placeholderReplacement(false)` + `classpath:db/migration/issue-tracking`.
     * 자식이 다른 마이그레이션 위치가 필요한 경우 이 메서드만 override 한다.
     */
    protected open fun configureFlyway(builder: FluentConfiguration): FluentConfiguration =
        builder.placeholderReplacement(false).locations("classpath:db/migration/issue-tracking")

    /**
     * JVM 당 1회 실행 — Flyway migrate + DSLContext 생성 + 테스트용 프로젝트 1건 삽입.
     * `@TestInstance(PER_CLASS)` + `@BeforeAll` 조합으로 instance 메서드로 동작 → `configureFlyway` override 가능.
     */
    @BeforeAll
    fun bootstrap() {
        if (bootstrapped) return

        // Flyway — DB 스키마 변경을 버전 관리하는 도구 (V001, V002 마이그레이션 적용)
        configureFlyway(
            Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password),
        ).load().migrate()

        val dataSource =
            org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )

        // jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        repository = IssueRepository(dsl)

        // 테스트용 프로젝트 1건 삽입 — key_sequence = 0 으로 시작
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES ('TPRJ', 'Test Project') RETURNING id",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    testProjectId = rs.getObject(1) as UUID
                }
            }
        }

        bootstrapped = true
    }

    /**
     * 각 테스트가 독립적으로 실행되도록 테스트마다 issues 행을 삭제하고 key_sequence 를 초기화.
     */
    @BeforeEach
    fun cleanIssues() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = 'TPRJ'")
            }
        }
    }
}
