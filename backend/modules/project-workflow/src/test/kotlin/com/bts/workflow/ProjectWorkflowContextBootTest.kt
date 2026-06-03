// project-workflow BC의 production 구현체 빈이 test-assembled 컨텍스트에서 올바르게 결선되는지 확인하는 부팅 테스트

package com.bts.workflow

import com.bts.workflow.adapter.AlwaysAllowPermissionResolver
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.engine.DefaultWorkflowPostActionFactory
import com.bts.workflow.engine.DefaultWorkflowValidatorFactory
import com.bts.workflow.engine.WorkflowEngine
import com.bts.workflow.engine.WorkflowEngineConfig
import com.bts.workflow.expression.SpelEvaluator
import com.bts.workflow.port.outbound.PermissionResolver
import com.bts.workflow.repository.DefaultWorkflowDefinitionRepository
import com.bts.workflow.repository.WorkflowRepository
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * project-workflow BC 컨텍스트 부팅 테스트.
 *
 * C3/D10=B 결정에 따라 @SpringBootApplication 전체 부팅 대신
 * @ContextConfiguration(TestConfig) 으로 test-assembled 컨텍스트를 구성한다.
 * production 배포 조립 BC 부재(no-cross-bc-deployment-assembly) 상황에서
 * 현 단계의 검증 표준이다.
 *
 * 검증 항목.
 * 1. 컨텍스트 부팅 성공 — WorkflowEngine 빈 주입 가능.
 * 2. validatorFactory 가 production 구현체 타입(DefaultWorkflowValidatorFactory)인지 확인.
 * 3. postActionFactory 가 production 구현체 타입(DefaultWorkflowPostActionFactory)인지 확인.
 * 4. definitionRepo 가 production 구현체 타입(DefaultWorkflowDefinitionRepository)인지 확인.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ProjectWorkflowContextBootTest.TestConfig::class])
class ProjectWorkflowContextBootTest {
    /**
     * test-assembled TestConfig.
     *
     * component-scan 없이 필요한 빈을 명시 선언한다.
     * AlwaysAllowPermissionResolver 는 @Profile("!prod") Bean 이므로
     * TestConfig 에서 직접 등록해 profile-scoped-bean-boot-failure 패턴을 회피한다.
     */
    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TestConfig {
        companion object {
            /** JVM 단위 singleton Testcontainers — 컨텍스트 부팅 검증용 최소 DB */
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_boot_test")
                    .withUsername("bts")
                    .withPassword("bts_test")
                    .apply { start() }

            @JvmStatic
            private var migrated = false

            @JvmStatic
            fun ensureMigrated() {
                if (migrated) return
                // 2-phase Flyway — V201 이 issue_types FK 를 참조하므로 target 200 적용 후 stub 생성 뒤 전체 적용
                Flyway.configure()
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .placeholderReplacement(false)
                    .locations(
                        "classpath:db/migration/issue-tracking",
                        "classpath:db/migration/project-workflow",
                    )
                    .target("200")
                    .load()
                    .migrate()

                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute(
                            """
                            CREATE TABLE IF NOT EXISTS issue_types (
                                id          BIGSERIAL    PRIMARY KEY,
                                key         VARCHAR(30)  NOT NULL UNIQUE,
                                name        VARCHAR(255) NOT NULL,
                                is_standard BOOLEAN      NOT NULL DEFAULT FALSE,
                                created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                deleted_at  TIMESTAMPTZ
                            )
                            """.trimIndent(),
                        )
                    }
                }

                Flyway.configure()
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .placeholderReplacement(false)
                    .locations(
                        "classpath:db/migration/issue-tracking",
                        "classpath:db/migration/project-workflow",
                    )
                    .load()
                    .migrate()

                migrated = true
            }
        }

        @Bean
        open fun dataSource(): DriverManagerDataSource {
            ensureMigrated()
            return DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        }

        @Bean
        open fun transactionManager(dataSource: DriverManagerDataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        open fun dslContext(dataSource: DriverManagerDataSource): DSLContext {
            return DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        // ── production 구현체 빈 ─────────────────────────────────────────────────

        @Bean
        open fun permissionResolver(): PermissionResolver = AlwaysAllowPermissionResolver()

        @Bean
        open fun workflowEngineConfig(): WorkflowEngineConfig = WorkflowEngineConfig()

        @Bean
        open fun spelEvaluator(config: WorkflowEngineConfig): SpelEvaluator {
            return config.spelEvaluator(config.spelExecutorService())
        }

        @Bean
        open fun workflowValidatorFactory(
            permissionResolver: PermissionResolver,
            spelEvaluator: SpelEvaluator,
        ): DefaultWorkflowValidatorFactory = DefaultWorkflowValidatorFactory(permissionResolver, spelEvaluator)

        @Bean
        open fun workflowPostActionFactory(): DefaultWorkflowPostActionFactory = DefaultWorkflowPostActionFactory()

        @Bean
        open fun workflowRepository(dsl: DSLContext): WorkflowRepository = WorkflowRepository(dsl)

        @Bean
        open fun workflowCache(
            workflowRepo: WorkflowRepository,
            dsl: DSLContext,
        ): WorkflowCache = WorkflowCache(workflowRepo, dsl)

        @Bean
        open fun workflowDefinitionRepository(dsl: DSLContext): DefaultWorkflowDefinitionRepository =
            DefaultWorkflowDefinitionRepository(dsl)

        @Bean
        open fun workflowEngine(
            cache: WorkflowCache,
            validatorFactory: DefaultWorkflowValidatorFactory,
            postActionFactory: DefaultWorkflowPostActionFactory,
            definitionRepo: DefaultWorkflowDefinitionRepository,
        ): WorkflowEngine = WorkflowEngine(cache, validatorFactory, postActionFactory, definitionRepo)
    }

    @Autowired
    lateinit var workflowEngine: WorkflowEngine

    @Autowired
    lateinit var validatorFactory: DefaultWorkflowValidatorFactory

    @Autowired
    lateinit var postActionFactory: DefaultWorkflowPostActionFactory

    @Autowired
    lateinit var definitionRepo: DefaultWorkflowDefinitionRepository

    /**
     * 컨텍스트 부팅 성공 + WorkflowEngine 빈 주입 확인.
     *
     * 이 테스트가 통과하면 production 구현체 4종이 모두 결선된 것이다.
     * 부팅 실패는 컨텍스트 로드 단계에서 예외로 표면화된다.
     */
    @Test
    fun `컨텍스트 부팅 성공 — WorkflowEngine 빈이 주입된다`() {
        assertThat(workflowEngine).isNotNull
    }

    /**
     * validatorFactory 가 production 구현체 타입인지 확인.
     *
     * DefaultWorkflowValidatorFactory 가 주입되지 않으면 이 assert 가 실패해
     * mock/stub 으로 결선된 상황을 잡아낸다.
     */
    @Test
    fun `validatorFactory 는 DefaultWorkflowValidatorFactory production 구현체이다`() {
        assertThat(validatorFactory).isInstanceOf(DefaultWorkflowValidatorFactory::class.java)
    }

    /**
     * postActionFactory 가 production 구현체 타입인지 확인.
     */
    @Test
    fun `postActionFactory 는 DefaultWorkflowPostActionFactory production 구현체이다`() {
        assertThat(postActionFactory).isInstanceOf(DefaultWorkflowPostActionFactory::class.java)
    }

    /**
     * definitionRepo 가 production 구현체 타입인지 확인.
     */
    @Test
    fun `definitionRepo 는 DefaultWorkflowDefinitionRepository production 구현체이다`() {
        assertThat(definitionRepo).isInstanceOf(DefaultWorkflowDefinitionRepository::class.java)
    }
}
