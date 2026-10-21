// 모듈 전역 공용 PostgreSQL — 클래스마다 컨테이너를 띄우는 대신 템플릿 DB 를 복제해 격리를 지킨다

package com.bts.workflow.testsupport

import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * identity-access 의 `SharedPostgres` 를 이 모듈에 옮긴 것이다. 설계 근거는 그 파일 머리말에 있다.
 *
 * ## 왜
 * 2026-10-21 관문에서 이 모듈의 컨테이너 테스트 클래스가 각자 컨테이너를 띄우고 마이그레이션을
 * V001 부터 다시 적용해, 디스크 경쟁이 붙자 클래스 하나가 40~45초가 됐다. 컨테이너 1개를 JVM 수명으로
 * 띄우고 마이그레이션은 **템플릿 DB 에 1회만** 적용한다.
 *
 * ## ★격리는 줄지 않는다
 * [freshDatabase] 는 템플릿을 복제한 **클래스 전용 데이터베이스**를 준다. 남의 클래스가 넣은 행은
 * 보이지 않는다(`shared-dev-db-preexisting-rows-fake-green`).
 *
 * ## 템플릿 = 종전 2단계 준비를 그대로
 * 이 모듈의 테스트들이 `@BeforeAll` 에서 하던 것과 같다 — issue-tracking + project-workflow 를 V200 까지,
 * `issue_types` 대체 테이블(V201 의 cross-BC FK 대상), 나머지 전부. issue-tracking V003 이 이미
 * `issue_types` 를 만들어 두 번째 단계는 대개 아무 일도 하지 않지만, 종전 준비와 같은 결과를 보장하려고 둔다.
 *
 * ## 쓰는 법 — 드롭인
 * ```
 * companion object {
 *     val postgres = SharedPostgres.freshDatabase()   // jdbcUrl · username · password
 * }
 * ```
 * `@Testcontainers` · `@Container` 와 `@BeforeAll` 의 Flyway 블록 · `issue_types` 생성을 지운다.
 * Spring context 를 띄우는 테스트는 `spring.flyway.enabled=false` 와
 * `spring.datasource.hikari.maximum-pool-size` = [MAX_POOL_SIZE] 를 함께 배선한다.
 *
 * ## 쓰면 안 되는 곳
 * **마이그레이션 자체를 검증하는 테스트**(`V2xxMigrationTest` 계열 · 중간 버전에서 멈추는 테스트).
 * 그쪽은 마이그레이션이 덜 적용된 DB 가 필요하다. 그대로 둔다.
 */
object SharedPostgres {
    /** V002 pgmq 확장 때문에 tembo 이미지다 — ADR 2026-05-22-pgmq-postgres-image. */
    private val IMAGE: DockerImageName =
        DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest").asCompatibleSubstituteFor("postgres")

    private val LOCATIONS = arrayOf("classpath:db/migration/issue-tracking", "classpath:db/migration/project-workflow")

    /** 유지보수용 접속 대상. `CREATE DATABASE` 는 템플릿에 접속하지 않은 채로 해야 한다. */
    private const val ROOT_DB = "bts_root"

    /** 마이그레이션이 1회 적용된 원본. 여기에는 아무도 계속 붙어 있으면 안 된다. */
    private const val TEMPLATE_DB = "bts_template"

    private const val USER = "bts"
    private const val PASSWORD = "bts_test"

    /** 컨테이너를 공유하면 커넥션 한도도 공유한다 — identity-access 파일럿에서 기본 100 으로 터졌다. */
    private const val MAX_CONNECTIONS = 400

    /** 각 테스트 context 가 잡을 커넥션 상한. Spring 테스트가 `@DynamicPropertySource` 에서 배선한다. */
    const val MAX_POOL_SIZE = "4"

    private const val POSTGRES_PORT = 5432

    private val sequence = AtomicInteger(0)

    /** 테스트가 쓰는 접속 정보. 종전 `PostgreSQLContainer` 에서 읽던 3개와 같은 이름이다. */
    data class Handle(
        val jdbcUrl: String,
        val username: String,
        val password: String,
    )

    private val container: PostgreSQLContainer<*> =
        PostgreSQLContainer(IMAGE).apply {
            withDatabaseName(ROOT_DB)
            withUsername(USER)
            withPassword(PASSWORD)
            withCommand("postgres", "-c", "max_connections=$MAX_CONNECTIONS")
            start()
        }

    /** 템플릿 준비는 첫 사용 시 1회. Kotlin `by lazy` 가 스레드 안전을 보장한다. */
    private val templateReady: Unit by lazy {
        execOnRoot("CREATE DATABASE $TEMPLATE_DB")
        migrate(target = "200")
        DriverManager.getConnection(urlFor(TEMPLATE_DB), USER, PASSWORD).use { conn ->
            conn.createStatement().use {
                it.execute(
                    "CREATE TABLE IF NOT EXISTS issue_types (" +
                        "id BIGSERIAL PRIMARY KEY, key VARCHAR(30) NOT NULL UNIQUE, " +
                        "name VARCHAR(255) NOT NULL, is_standard BOOLEAN NOT NULL DEFAULT FALSE, " +
                        "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), " +
                        "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)",
                )
            }
        }
        migrate(target = null)
        // Flyway 커넥션이 남아 있으면 아래 CREATE ... TEMPLATE 이 거부된다. 확실히 끊는다.
        disconnectFrom(TEMPLATE_DB)
    }

    /** 마이그레이션이 적용된 **전용 데이터베이스**를 새로 만들어 접속 정보를 준다. 테스트 클래스당 한 번 부른다. */
    @JvmStatic
    fun freshDatabase(): Handle {
        templateReady
        val name = "bts_test_${sequence.incrementAndGet()}"
        synchronized(this) {
            disconnectFrom(TEMPLATE_DB)
            execOnRoot("CREATE DATABASE $name TEMPLATE $TEMPLATE_DB")
        }
        return Handle(urlFor(name), USER, PASSWORD)
    }

    private fun migrate(target: String?) {
        Flyway
            .configure()
            .dataSource(urlFor(TEMPLATE_DB), USER, PASSWORD)
            .placeholderReplacement(false)
            .locations(*LOCATIONS)
            .let { if (target == null) it else it.target(target) }
            .load()
            .migrate()
    }

    private fun urlFor(database: String): String {
        val hostPort = "${container.host}:${container.getMappedPort(POSTGRES_PORT)}"
        return "jdbc:postgresql://$hostPort/$database"
    }

    private fun execOnRoot(sql: String) {
        DriverManager.getConnection(urlFor(ROOT_DB), USER, PASSWORD).use { conn ->
            conn.createStatement().use { it.execute(sql) }
        }
    }

    /** 템플릿에 남은 커넥션을 끊는다. 하나라도 살아 있으면 TEMPLATE 복제가 실패한다. */
    private fun disconnectFrom(database: String) {
        execOnRoot(
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                "WHERE datname = '$database' AND pid <> pg_backend_pid()",
        )
    }
}
