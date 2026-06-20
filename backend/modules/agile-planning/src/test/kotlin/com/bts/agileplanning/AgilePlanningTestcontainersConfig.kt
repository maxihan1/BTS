// agile-planning 통합 테스트 전용 Testcontainers + DataSource + cross-BC 포트 stub 설정
@file:Suppress("DEPRECATION") // JooqExceptionTranslator: Spring Boot 3.3 deprecated, package-private 후계 미공개

package com.bts.agileplanning

import com.bts.shared.board.BoardIssueView
import com.bts.shared.board.BoardIssueLookupPort
import com.bts.shared.board.BoardTransitionCommand
import com.bts.shared.board.BoardTransitionResult
import com.bts.shared.board.IssueTransitionPort
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowStateView
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.issue.IssueTypeKey
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DataSourceConnectionProvider
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.DefaultDSLContext
import org.jooq.impl.DefaultExecuteListenerProvider
import org.springframework.boot.autoconfigure.jooq.JooqExceptionTranslator
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import javax.sql.DataSource

/**
 * agile-planning 모듈 `@SpringBootTest` 전용 Testcontainers + DataSource + cross-BC 포트 stub 설정.
 *
 * Testcontainers PostgreSQL 16-alpine 을 JVM 단위 singleton 으로 기동하고,
 * Flyway V500(DDL) 을 적용한 뒤 DataSource / DSLContext / TransactionManager 빈을 제공한다.
 *
 * ## cross-BC 포트 stub
 * agile-planning 모듈 단독 테스트 컨텍스트에는 issue-tracking / project-workflow 구현체가 없다.
 * 포트 non-null 주입(fail-closed) 요건을 충족하기 위해 테스트 stub 빈을 등록한다.
 * (memory: crossbc-resolver-nullable-fail-open — 빈 부재 시 fail-open 위험 차단,
 *  fr-nt-02/03 전례 — @Component 신규 포트의 cross-BC 의존이 전체-컨텍스트 통합테스트 부팅 깸.)
 *
 * ## 주의 사항
 * - Spring Boot FlywayAutoConfiguration 은 [AgilePlanningTestBootApplication] 에서 exclude.
 *   여기서 직접 Flyway 를 실행해 V500 마이그레이션을 적용한다.
 * - [IssueTransitionPort] 는 fail-closed(default 구현 없음). 테스트에서 MockK 로 교체 가능.
 */
@TestConfiguration
class AgilePlanningTestcontainersConfig {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL 16-alpine container.
         *
         * `.apply { start() }` 로 companion object 초기화 시점에 한 번만 기동.
         * Ryuk 이 JVM 종료 시 자동 정리하므로 명시적 stop 불필요.
         * (memory: concurrent-testcontainers-suite-flaky — singleton 패턴 표준.)
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_agileplanning_e2e_test")
                .withUsername("bts")
                .withPassword("bts_e2e_test")
                .apply { start() }

        private var migrated = false

        /**
         * Flyway V500(DDL) 마이그레이션을 1회만 실행한다.
         *
         * 동일 JVM 에서 여러 테스트 클래스가 이 설정을 공유해도 migrate 는 한 번만 수행된다.
         */
        @Synchronized
        fun migrateOnce(
            jdbcUrl: String,
            username: String,
            password: String,
        ) {
            if (migrated) return
            Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/agile-planning")
                .load()
                .migrate()
            migrated = true
        }
    }

    /**
     * Testcontainers PostgreSQL 에 연결하는 [DataSource] 빈.
     */
    @Bean
    fun dataSource(): DataSource {
        migrateOnce(postgres.jdbcUrl, postgres.username, postgres.password)
        return DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    /**
     * jOOQ [DSLContext] 빈.
     *
     * [JooqExceptionTranslator] 를 등록해 jOOQ UNIQUE 위반이 Spring [org.springframework.dao.DuplicateKeyException] 으로
     * 변환되도록 한다. (memory: jooq-exception-translator-409-dependency)
     */
    @Bean
    fun dslContext(dataSource: DataSource): DSLContext {
        val configuration =
            DefaultConfiguration()
                .set(DataSourceConnectionProvider(TransactionAwareDataSourceProxy(dataSource)))
                .set(SQLDialect.POSTGRES)
                .set(DefaultExecuteListenerProvider(JooqExceptionTranslator()))
        return DefaultDSLContext(configuration)
    }

    /**
     * Spring 트랜잭션 매니저 빈.
     */
    @Bean
    fun transactionManager(dataSource: DataSource): PlatformTransactionManager =
        DataSourceTransactionManager(dataSource)

    /**
     * [WorkflowStateCatalog] 테스트 stub 빈.
     *
     * 보드 생성 시 default 워크플로우 상태를 반환하는 stub.
     * 테스트별로 행동을 직접 지정할 수 있도록 기본 구현은 빈 목록을 반환한다.
     * 각 통합 테스트가 io.mockk.mockk() 로 교체하거나 이 default 를 사용한다.
     */
    @Bean
    fun workflowStateCatalog(): WorkflowStateCatalog =
        object : WorkflowStateCatalog {
            override fun listStates(
                projectKey: ProjectKey,
                issueTypeKey: IssueTypeKey?,
            ): List<WorkflowStateView> = emptyList()
        }

    /**
     * [BoardIssueLookupPort] 테스트 stub 빈.
     *
     * default 구현(빈 목록)이 이미 interface 에 있으나, non-null 주입 요건 충족을 위해 명시 등록.
     * (memory: crossbc-resolver-nullable-fail-open — 빈 등록 자체가 fail-closed 보장.)
     */
    @Bean
    fun boardIssueLookupPort(): BoardIssueLookupPort =
        object : BoardIssueLookupPort {
            override fun listVisibleIssuesByProject(
                projectKey: String,
                viewerUserId: UUID,
            ): List<BoardIssueView> = emptyList()
        }

    /**
     * [IssueTransitionPort] 테스트 stub 빈.
     *
     * fail-closed 포트이므로 default 구현이 없다. 테스트가 MockK spy 로 교체하지 않는 한
     * 호출 시 예외를 던져 미결선 상태를 표면화한다.
     * 각 통합 테스트 메서드에서 MockK 로 원하는 행동을 주입한다.
     */
    @Bean
    fun issueTransitionPort(): IssueTransitionPort =
        object : IssueTransitionPort {
            override fun transition(cmd: BoardTransitionCommand): BoardTransitionResult {
                error("IssueTransitionPort stub — 테스트에서 MockK 로 교체해야 합니다: cmd=$cmd")
            }
        }

    /**
     * [IssuePermissionResolver] 테스트 stub 빈.
     *
     * 보드 컨트롤러(T9) 권한 게이트에서 사용. 서비스 통합테스트는 권한을 검증하지 않으므로
     * allow-all stub 으로 충분하다. T9 에서 deny stub 으로 교체해 403 경로 검증.
     */
    @Bean
    fun issuePermissionResolver(): IssuePermissionResolver =
        object : IssuePermissionResolver {
            override fun hasPermission(
                actorId: UUID,
                permission: IssuePermission,
                scope: IssueScope,
            ): Boolean = true
        }
}
