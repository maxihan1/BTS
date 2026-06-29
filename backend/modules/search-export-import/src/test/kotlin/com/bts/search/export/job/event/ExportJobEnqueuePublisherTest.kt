// ExportJobEnqueuePublisher 통합 테스트 — q_export_jobs pgmq outbox + MANDATORY 트랜잭션 강제 (FR-EX-02)

package com.bts.search.export.job.event

import com.bts.search.export.job.domain.ExportJobId
import com.bts.search.savedfilter.persistence.SearchPersistenceTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * ExportJobEnqueuePublisher 통합 테스트.
 *
 * quay.io/tembo/pg16-pgmq:latest 컨테이너 위에서
 * 실제 pgmq 큐에 exportJobId 메시지가 enqueue 되는지 검증한다.
 *
 * ## 검증 범위
 *
 * (a) 활성 트랜잭션 안에서 enqueue 호출 → q_export_jobs 에 메시지 1건 발행,
 *     JSON 내용(`{"exportJobId":"<UUID>"}`) 확인.
 * (b) 트랜잭션 없이 enqueue 호출 → [IllegalTransactionStateException] 발생
 *     (`@Transactional(MANDATORY)` Spring AOP 강제).
 *
 * [SearchPersistenceTestBase] 를 상속해 Testcontainers + Flyway V600~V602 마이그레이션
 * (q_export_jobs 큐 생성 포함)을 재사용한다. Spring 컨텍스트는 [TestConfig] 에 최소 빈으로 구성한다.
 *
 * ### 트랜잭션 공유 원리
 * [TransactionTemplate] 이 [DataSourceTransactionManager] 로 트랜잭션을 열면
 * Spring 은 현재 스레드에 커넥션을 바인딩한다.
 * jOOQ [DSLContext] 가 [DataSourceConnectionProvider] 를 통해
 * `DataSourceUtils.getConnection()` 을 호출하면 트랜잭션 바인딩 커넥션을 재사용하므로
 * pgmq.send 가 같은 트랜잭션 안에서 실행된다 (outbox 보장).
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ExportJobEnqueuePublisherTest.TestConfig::class])
class ExportJobEnqueuePublisherTest : SearchPersistenceTestBase() {
    /**
     * 최소 Spring 빈 구성 — DataSource/TM/DSLContext/Publisher/TransactionTemplate.
     *
     * DataSource 는 [SearchPersistenceTestBase.postgres] 컨테이너(이미 기동됨)의 JDBC URL 을 사용한다.
     * [SearchPersistenceTestBase.bootstrap] 이 Flyway 를 실행하여 q_export_jobs 큐를 생성하므로
     * 테스트 메서드 실행 시점에 큐가 보장된다.
     */
    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TestConfig {
        @Bean
        open fun dataSource(): DriverManagerDataSource =
            DriverManagerDataSource(
                SearchPersistenceTestBase.postgres.jdbcUrl,
                SearchPersistenceTestBase.postgres.username,
                SearchPersistenceTestBase.postgres.password,
            )

        @Bean
        open fun transactionManager(dataSource: DriverManagerDataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        open fun dslContext(dataSource: DriverManagerDataSource): DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)

        /** [ExportJobEnqueuePublisher] 빈 — @Component 어노테이션 기반 컴포넌트 스캔 없이 명시 등록. */
        @Bean
        open fun exportJobEnqueuePublisher(dsl: DSLContext): ExportJobEnqueuePublisher = ExportJobEnqueuePublisher(dsl)

        @Bean
        open fun transactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
            TransactionTemplate(transactionManager)
    }

    /** Spring 프록시가 적용된 [ExportJobEnqueuePublisher] 빈. @Transactional(MANDATORY) 가 적용된다. */
    @Autowired
    lateinit var publisher: ExportJobEnqueuePublisher

    /** Spring 트랜잭션 템플릿 — 테스트용 트랜잭션 경계 제공. */
    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    /** Spring 관리 DSLContext — pgmq.read / pgmq.purge_queue 직접 호출에 사용. */
    @Autowired
    lateinit var springDsl: DSLContext

    @AfterEach
    fun purgeQueue() {
        springDsl.execute("SELECT pgmq.purge_queue(?)", ExportJobEnqueuePublisher.QUEUE_NAME)
    }

    // ── (a) 트랜잭션 안에서 enqueue ──────────────────────────────────────────────

    @Test
    fun `트랜잭션 안에서 enqueue 호출 시 q_export_jobs 에 메시지 1건 발행`() {
        val id = ExportJobId(UUID.randomUUID())

        // TransactionTemplate 이 트랜잭션을 열고 publisher.enqueue 를 호출한다.
        // publisher 는 Spring 프록시이므로 @Transactional(MANDATORY) 가 활성 트랜잭션을 감지한다.
        transactionTemplate.execute { publisher.enqueue(id) }

        // 커밋 후 pgmq.read 로 메시지 1건 확인 (vt=1초, qty=10)
        val messages =
            springDsl.fetch(
                "SELECT * FROM pgmq.read(?, ?, ?)",
                ExportJobEnqueuePublisher.QUEUE_NAME,
                1,
                10,
            )
        assertThat(messages).hasSize(1)
        val body = messages.first().get("message", String::class.java)
        assertThat(body).contains("exportJobId")
        assertThat(body).contains(id.value.toString())
    }

    // ── (b) 트랜잭션 없이 enqueue ────────────────────────────────────────────────

    @Test
    fun `트랜잭션 없이 enqueue 호출 시 IllegalTransactionStateException 발생`() {
        val id = ExportJobId(UUID.randomUUID())

        // 트랜잭션 래퍼 없이 직접 호출 — Spring AOP 프록시가 활성 트랜잭션 부재를 감지하고 예외를 던진다.
        assertThatThrownBy { publisher.enqueue(id) }
            .isInstanceOf(IllegalTransactionStateException::class.java)
    }
}
