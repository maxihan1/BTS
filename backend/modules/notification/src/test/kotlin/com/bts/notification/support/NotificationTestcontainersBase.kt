// NotificationTestcontainersBase — SharedPostgres 템플릿 복제 전용 DB 에 붙는 통합 테스트 공통 기반.

package com.bts.notification.support

import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.datasource.DriverManagerDataSource

/**
 * Notification 모듈 통합 테스트 공통 기반 클래스.
 *
 * **공용 컨테이너 + 클래스 전용 DB** — 컨테이너와 마이그레이션은 [SharedPostgres] 가 JVM 당 1회 처리하고,
 * 이 클래스를 상속한 테스트 클래스마다 템플릿을 복제한 전용 DB 를 받는다.
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
    // @TestInstance(PER_CLASS) 덕분에 instance 멤버로 선언 가능
    lateinit var dsl: DSLContext
        protected set

    private var bootstrapped = false

    /**
     * 클래스당 1회 실행 — [SharedPostgres] 템플릿을 복제한 전용 DB 에 붙는 DSLContext 생성.
     * `@TestInstance(PER_CLASS)` + `@BeforeAll` 조합으로 instance 메서드로 동작.
     */
    @BeforeAll
    fun bootstrap() {
        if (bootstrapped) return

        val postgres = SharedPostgres.freshDatabase()
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

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
