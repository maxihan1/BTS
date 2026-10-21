// 모듈 전역 공용 PostgreSQL — 클래스마다 컨테이너를 띄우는 대신 템플릿 DB 를 복제해 격리를 지킨다

package com.bts.search.support

import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * project-workflow 의 `SharedPostgres` 를 이 모듈에 옮긴 것이다. 설계 근거는 identity-access 파일 머리말에 있다.
 *
 * ## 왜
 * 테스트 클래스마다 컨테이너를 띄우고 Flyway 를 V600 부터 다시 적용하면 느리다.
 * 컨테이너 1개를 JVM 수명으로 띄우고 마이그레이션은 **템플릿 DB 에 1회만** 적용한다.
 *
 * ## ★격리는 줄지 않는다
 * [freshDatabase] 는 템플릿을 복제한 **전용 데이터베이스**를 준다. 남의 클래스가 넣은 행은 보이지 않는다.
 *
 * ## 템플릿
 * search-export-import 마이그레이션 전부(V600~)를 적용한 상태다. V602 · V604 가 `CREATE EXTENSION pgmq` 와
 * 큐 생성을 스스로 하므로 사전 DDL 은 없다.
 *
 * ## 쓰는 법
 * ```
 * companion object {
 *     val pg = SharedPostgres.freshDatabase()   // jdbcUrl · username · password
 * }
 * ```
 * Spring Boot 자동 구성을 쓰는 테스트는 `spring.flyway.enabled=false` 와
 * `spring.datasource.hikari.maximum-pool-size` = [MAX_POOL_SIZE] 를 함께 배선한다.
 *
 * ## 쓰면 안 되는 곳
 * **마이그레이션 자체를 검증하는 테스트**(`*SchemaMigrationTest` · `SavedFiltersMigrationTest` 등).
 * 덜 적용된 DB 가 필요하다.
 */
object SharedPostgres {
    /** V602 pgmq 확장 때문에 tembo 이미지다 — ADR 2026-05-22-pgmq-postgres-image. */
    private val IMAGE: DockerImageName =
        DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest").asCompatibleSubstituteFor("postgres")

    private const val LOCATION = "classpath:db/migration/search-export-import"

    /** 유지보수용 접속 대상. `CREATE DATABASE` 는 템플릿에 접속하지 않은 채로 해야 한다. */
    private const val ROOT_DB = "bts_root"

    /** 마이그레이션이 1회 적용된 원본. 여기에는 아무도 계속 붙어 있으면 안 된다. */
    private const val TEMPLATE_DB = "bts_template"

    private const val USER = "bts"
    private const val PASSWORD = "bts_test"

    /** 컨테이너를 공유하면 커넥션 한도도 공유한다 — identity-access 파일럿에서 기본 100 으로 터졌다. */
    private const val MAX_CONNECTIONS = 400

    /** 각 테스트 context 가 잡을 커넥션 상한. Spring Boot 테스트가 `@DynamicPropertySource` 에서 배선한다. */
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
        Flyway
            .configure()
            .dataSource(urlFor(TEMPLATE_DB), USER, PASSWORD)
            .placeholderReplacement(false)
            .locations(LOCATION)
            .load()
            .migrate()
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
