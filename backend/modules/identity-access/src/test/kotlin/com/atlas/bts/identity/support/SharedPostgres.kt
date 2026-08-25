// 모듈 전역 공용 PostgreSQL — 클래스마다 컨테이너를 띄우는 대신 템플릿 DB 를 복제해 격리를 지킨다

package com.atlas.bts.identity.support

import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * ## 왜 이 파일이 생겼나
 *
 * 2026-08-25 실측. `:modules:identity-access:test` 태스크 벽시계가 **9분 48초**인데
 * JUnit 이 재는 testsuite time 합계는 **82초**뿐이었다. 나머지 506초(86%)는 테스트가 아니라
 * **컨테이너 기동 + Flyway 마이그레이션 + Spring context 부팅**이다.
 *
 * 원인은 단순했다. 109개 테스트 클래스가 각자 이렇게 적고 있었다.
 * ```
 * @Testcontainers
 * class XTest { companion object {
 *     @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")...
 * } }
 * ```
 * `@Container` 는 **클래스 단위 수명**이다. 클래스마다 컨테이너를 띄우고 내리고, 그 위에
 * 마이그레이션 36개를 처음부터 다시 적용한다. 클래스당 약 4.6초 × 109 = 506초.
 *
 * ## 무엇을 하는가
 *
 * 컨테이너 **1개**를 JVM 수명으로 띄우고, 마이그레이션을 **템플릿 DB 에 1회만** 적용한다.
 * 테스트 클래스는 `freshDatabase()` 로 그 템플릿을 복제한 **자기 전용 DB** 를 받는다.
 * `CREATE DATABASE ... TEMPLATE` 은 파일 복사라 마이그레이션 재적용보다 훨씬 싸다.
 *
 * ## ★격리는 줄지 않는다
 *
 * 클래스마다 **별도 데이터베이스**를 준다. 공용 DB 를 나눠 쓰는 방식이 아니다 — 그쪽은
 * 이 저장소가 이미 데인 양식이다(`shared-dev-db-preexisting-rows-fake-green`). 남의 클래스가
 * 넣은 행이 보이는 일이 없고, 테스트 순서에 결과가 의존하지도 않는다.
 *
 * ## 쓰는 법 — 드롭인
 *
 * ```
 * companion object {
 *     @JvmStatic
 *     val postgres = SharedPostgres.freshDatabase()   // jdbcUrl · username · password
 *
 *     @DynamicPropertySource @JvmStatic
 *     fun configureProperties(registry: DynamicPropertyRegistry) {
 *         registry.add("spring.datasource.url") { postgres.jdbcUrl }
 *         registry.add("spring.datasource.username") { postgres.username }
 *         registry.add("spring.datasource.password") { postgres.password }
 *         registry.add("spring.flyway.enabled") { "false" }   // ★템플릿에서 이미 적용됐다
 *         registry.add("spring.datasource.hikari.maximum-pool-size") { SharedPostgres.MAX_POOL_SIZE }
 *     }
 * }
 * ```
 * `@Testcontainers` 와 `@Container` 는 지운다. 이름을 `postgres` 로 두면 본문의
 * `postgres.jdbcUrl` 참조가 그대로 산다.
 *
 * ## 쓰면 안 되는 곳
 *
 * **마이그레이션 자체를 검증하는 테스트.** 그쪽은 마이그레이션이 안 적용된 빈 DB 가 필요하다
 * (`V0xxMigrationTest` 계열, `Flyway.configure()` 를 직접 부르는 14개 파일). 그대로 둔다.
 *
 * 컨테이너를 명시적으로 stop 하지 않는 것은 이 모듈의 기존 관례를 따른 것이다
 * ([LdapTestcontainersBase] 참조) — JVM 종료 시 Ryuk 이 정리한다.
 */
object SharedPostgres {
    private const val IMAGE = "postgres:16-alpine"

    /** 유지보수용 접속 대상. `CREATE DATABASE` 는 템플릿에 접속하지 않은 채로 해야 한다. */
    private const val ROOT_DB = "bts_root"

    /** 마이그레이션이 1회 적용된 원본. 여기에는 아무도 계속 붙어 있으면 안 된다. */
    private const val TEMPLATE_DB = "bts_template"

    private const val USER = "bts"
    private const val PASSWORD = "bts_test"

    private val sequence = AtomicInteger(0)

    /** 테스트가 쓰는 접속 정보. 종전 `PostgreSQLContainer` 에서 실제로 읽던 3개와 같은 이름이다. */
    data class Handle(
        val jdbcUrl: String,
        val username: String,
        val password: String,
    )

    /**
     * ★`max_connections` 를 올린다. 컨테이너를 공유하면 **커넥션 한도도 공유**한다.
     *
     * 2026-08-25 파일럿에서 실제로 터졌다 — 12개 클래스를 공용 컨테이너로 돌리자
     * `FATAL: sorry, too many clients already`. 클래스마다 Spring context 가 Hikari 풀을
     * 들고 있고(기본 10), Spring 이 context 를 최대 32개까지 캐시한다. 기본값 100 으로는
     * 모자란다. 상한 상향과 함께 각 테스트가 풀 크기도 [MAX_POOL_SIZE] 로 낮춘다 —
     * 둘 중 하나만으로는 클래스 수가 늘면 다시 터진다.
     */
    private const val MAX_CONNECTIONS = 400

    /** 각 테스트 context 가 잡을 커넥션 상한. `@DynamicPropertySource` 에서 함께 배선한다. */
    const val MAX_POOL_SIZE = "4"

    private val container: PostgreSQLContainer<*> =
        PostgreSQLContainer<Nothing>(IMAGE).apply {
            withDatabaseName(ROOT_DB)
            withUsername(USER)
            withPassword(PASSWORD)
            withCommand("postgres", "-c", "max_connections=$MAX_CONNECTIONS")
            start()
        }

    /** 템플릿 준비는 첫 사용 시 1회. Kotlin `by lazy` 가 스레드 안전을 보장한다. */
    private val templateReady: Unit by lazy {
        execOnRoot("CREATE DATABASE $TEMPLATE_DB")
        Flyway
            .configure()
            .dataSource(urlFor(TEMPLATE_DB), USER, PASSWORD)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        // Flyway 커넥션이 남아 있으면 아래 CREATE ... TEMPLATE 이 거부된다. 확실히 끊는다.
        disconnectFrom(TEMPLATE_DB)
    }

    /**
     * 마이그레이션이 적용된 **전용 데이터베이스**를 새로 만들어 접속 정보를 준다.
     * 테스트 클래스당 한 번 부른다.
     */
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

    private const val POSTGRES_PORT = 5432
}
