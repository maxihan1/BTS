// 아웃바운드 webhook 컨트롤러 통합테스트 전용 Spring 컨텍스트 fixture — 실 DB + fail-closed 권한 stub + 명시 SSRF 검증기 빈 (FR-API-03 PR2)

package com.bts.search.webhook.web

import com.bts.search.webhook.application.OutboundWebhookRepository
import com.bts.search.webhook.application.OutboundWebhookService
import com.bts.search.webhook.config.WebhookEncryptionConfig
import com.bts.search.webhook.persistence.JooqOutboundWebhookRepository
import com.bts.shared.crypto.SecretEncryptor
import com.bts.shared.http.OutboundUrlValidator
import com.bts.shared.permission.SystemPermissionResolver
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 아웃바운드 webhook 컨트롤러 통합테스트 전용 Spring 컨텍스트.
 *
 * 실 DataSource(Testcontainers) + Flyway(V600~V603) + DSLContext + Repository + Service + 컨트롤러 +
 * 스코프 예외 핸들러를 조립한다. cross-BC 포트는 search test-boot 에 실 구현이 없으므로 아래처럼 대체한다.
 * - [SystemPermissionResolver] → [StubSystemPermissionResolver]. 기본 admins 집합이 비어 있어
 *   **fail-closed(모두 비-admin → 403)** 이며, 테스트가 admin actor UUID 를 명시 등록해야 통과한다
 *   (fail-open 금지, 교훈 crossbc-resolver-nullable-fail-open).
 * - [OutboundUrlValidator] → 무인자 생성자 **명시 @Bean**. `com.bts.shared.http` 패키지 스캔은 PR2
 *   범위 밖인 RestClient 빈(OutboundHttpClientConfig)까지 끌어오므로 회피한다.
 *
 * secret 암호화 빈은 실 [WebhookEncryptionConfig] 를 @Import 로 재사용하며, 키/salt 는 통합테스트가
 * `bts.webhook-encryption.{key,salt}` test property 로 주입한다(secret 암호화 경로 커버).
 *
 * ## 트랜잭션
 * 형제 SavedFilterIntegrationTest 와 동일하게 PlatformTransactionManager 를 등록하지 않는다. 서비스의
 * `@Transactional` 은 트랜잭션 인프라 부재 시 무시되고 각 jOOQ 문이 auto-commit 되며, 이 통합테스트가
 * 검증하는 CRUD/OCC/소프트삭제 경로에는 다중문 원자성이 필요 없다.
 */
@Configuration
@EnableWebMvc
@Import(WebhookEncryptionConfig::class)
open class WebhookIntegrationConfig {
    /**
     * 실 PostgreSQL(Testcontainers) 에 Flyway V600~V603 을 적용하고 [DSLContext] 를 구성한다.
     *
     * @return search-export-import 스키마가 적용된 jOOQ [DSLContext].
     */
    @Bean
    open fun dslContext(): DSLContext {
        Flyway
            .configure()
            .dataSource(pg.jdbcUrl, pg.username, pg.password)
            .placeholderReplacement(false)
            .locations("classpath:db/migration/search-export-import")
            .load()
            .migrate()
        val dataSource = DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password)
        return DSL.using(dataSource, SQLDialect.POSTGRES)
    }

    /**
     * jOOQ 기반 [OutboundWebhookRepository] 구현.
     *
     * @param dsl 컨테이너 기반 [DSLContext].
     */
    @Bean
    open fun repository(dsl: DSLContext): OutboundWebhookRepository = JooqOutboundWebhookRepository(dsl)

    /**
     * fail-closed [SystemPermissionResolver] stub — 테스트가 admin actor 를 명시 등록한다.
     */
    @Bean
    open fun systemPermissionResolver(): StubSystemPermissionResolver = StubSystemPermissionResolver()

    /**
     * SSRF 검증기 — 무인자 생성자로 직접 주입(패키지 스캔 회피).
     */
    @Bean
    open fun outboundUrlValidator(): OutboundUrlValidator = OutboundUrlValidator()

    /**
     * 아웃바운드 webhook 구독 서비스.
     *
     * @param systemPermissionResolver 전역 admin 판정 포트(stub).
     * @param outboundUrlValidator SSRF 검증기.
     * @param secretEncryptor webhook 전용 키로 구성된 암호화 유틸([WebhookEncryptionConfig] 제공).
     * @param repository 구독 영속성 포트.
     */
    @Bean
    open fun service(
        systemPermissionResolver: SystemPermissionResolver,
        outboundUrlValidator: OutboundUrlValidator,
        secretEncryptor: SecretEncryptor,
        repository: OutboundWebhookRepository,
    ): OutboundWebhookService =
        OutboundWebhookService(systemPermissionResolver, outboundUrlValidator, secretEncryptor, repository)

    /**
     * `/api/v1/webhooks` REST 컨트롤러.
     *
     * @param service 아웃바운드 webhook 구독 서비스.
     */
    @Bean
    open fun controller(service: OutboundWebhookService): OutboundWebhookController = OutboundWebhookController(service)

    /**
     * 스코프 예외 핸들러([OutboundWebhookController] 전용).
     */
    @Bean
    open fun exceptionHandler(): OutboundWebhookExceptionHandler = OutboundWebhookExceptionHandler()

    /**
     * 테스트가 admin actor 를 명시 등록하는 fail-closed [SystemPermissionResolver] stub.
     *
     * [admins] 집합에 등록된 UUID 만 SYSTEM_ADMIN 으로 판정한다. 기본은 비어 있어 모두 거부(403)한다.
     */
    class StubSystemPermissionResolver : SystemPermissionResolver {
        /** SYSTEM_ADMIN 으로 취급할 actor UUID 집합. 테스트가 채우고 비운다. */
        val admins: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

        override fun isSystemAdmin(actorId: UUID): Boolean = actorId in admins
    }

    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container.
         *
         * 이미지 = quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치). search-export-import 마이그레이션 체인의
         * V602(`CREATE EXTENSION pgmq`) 때문에 postgres:16-alpine 으로는 실패하므로 tembo 이미지가 필수다
         * (형제 SavedFilterIntegrationTest·SearchPersistenceTestBase 와 동일, ADR 2026-05-22-pgmq-postgres-image).
         * Ryuk 이 JVM 종료 시 자동 정리하므로 명시적 stop 은 불필요하다.
         */
        @JvmStatic
        val pg: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName
                    .parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("bts_webhook_it")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }
    }
}
