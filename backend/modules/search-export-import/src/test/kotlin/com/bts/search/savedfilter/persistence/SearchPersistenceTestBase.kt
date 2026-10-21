// search-export-import BC 영속성 통합테스트 공통 기반 클래스 — SharedPostgres 템플릿 복제 DB (FR-SR-03)

package com.bts.search.savedfilter.persistence

import com.bts.search.support.SharedPostgres
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.datasource.DriverManagerDataSource

/**
 * search-export-import 모듈 영속성 통합 테스트 공통 기반 클래스.
 *
 * **공용 컨테이너 + 템플릿 복제 DB** — 컨테이너와 V600~ 마이그레이션은 [SharedPostgres] 가 JVM 당 1회 처리한다.
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
         * [SharedPostgres] 템플릿을 복제한 DB — 이 기반 클래스를 상속한 테스트들이 JVM 안에서 함께 쓴다.
         * (`ExportJobEnqueuePublisherTest` 의 Spring 설정이 정적으로 이 접속 정보를 읽기 때문에 JVM 단위다.)
         */
        @JvmStatic
        val postgres = SharedPostgres.freshDatabase()
    }

    /** jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점. bootstrap() 이후 초기화됨. */
    lateinit var dsl: DSLContext
        protected set

    private var bootstrapped = false

    /**
     * JVM 당 1회 실행 — DSLContext 생성. 마이그레이션은 [SharedPostgres] 템플릿에 이미 적용돼 있다.
     * `@TestInstance(PER_CLASS)` + `@BeforeAll` 조합으로 instance 메서드로 동작.
     */
    @BeforeAll
    fun bootstrap() {
        if (bootstrapped) return

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
