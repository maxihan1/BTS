// WebhookDispatchWorker E2E 통합 테스트 — 실 pgmq(Testcontainers) + JDK HttpServer stub

package com.bts.notification.webhook

import com.bts.notification.NotificationTestBootApplication
import com.bts.notification.TestPermissionConfig
import com.bts.notification.config.WebhookHttpClientConfig
import com.bts.notification.worker.WebhookDispatchWorker
import com.bts.shared.http.OutboundUrlValidator
import com.bts.shared.http.UrlCheck
import com.bts.shared.issue.IssueRecipientLookupPort
import com.bts.shared.issue.IssueRecipients
import com.bts.shared.issue.ProjectRecipientLookupPort
import com.bts.shared.issue.ProjectRecipients
import com.bts.shared.permission.IssueVisibilityPort
import com.bts.shared.user.UserLookupPort
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DataSourceConnectionProvider
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.DefaultDSLContext
import org.jooq.impl.DefaultExecuteListenerProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.jooq.JooqExceptionTranslator
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * [WebhookDispatchWorker] End-to-End 통합 테스트.
 *
 * ## 인프라
 * - Testcontainers `quay.io/tembo/pg16-pgmq:latest` — pgmq 확장 사전 설치.
 * - `q_transition_events` 큐를 테스트 config에서 수동 생성 (V022 큐 선례).
 * - JDK 내장 [HttpServer] 를 외부 Webhook stub 으로 사용 — 신규 의존성 0.
 *
 * ## 검증 시나리오
 * - E2E-A. 전달 happy-path: pgmq 발행 → testWorker(validator mock 우회) pollAndProcess()
 *   → stub 서버가 POST + 엔벨로프 실수신 + 큐 비어있음(delete 확인).
 * - E2E-B. SSRF 차단 경로: 실 validator(autowired worker)가 127.0.0.1 차단
 *   → Rejected → delete. stub 전송 0회.
 * - E2E-C. 부팅 안전: 신규 @Component(worker/dispatcher/validator/RestClient 빈)가
 *   전체 컨텍스트 부팅을 깨지 않음.
 *
 * ## 주의 (memory: concurrent-testcontainers-suite-flaky)
 * 이 클래스를 다른 Testcontainers 클래스와 동시에 실행하면 워커 크래시가 발생할 수 있다.
 * test-results XML에 실패가 기록된 경우에만 실제 실패로 처리한다.
 */
@SpringBootTest(
    classes = [
        NotificationTestBootApplication::class,
        WebhookDispatchEndToEndIntegrationTest.WebhookE2EConfig::class,
        TestPermissionConfig::class,
        // OutboundUrlValidator 는 shared-kernel(com.bts.shared.http)로 이동했으므로
        // NotificationTestBootApplication(scanBasePackages=["com.bts.notification"]) 의 스캔 대상이 아니다.
        // WebhookDispatcher 가 주입받는 빈이라 명시 등록하지 않으면 부팅이 NoSuchBeanDefinition 으로 깨진다.
        OutboundUrlValidator::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("DEPRECATION") // JooqExceptionTranslator: Spring Boot 3.3 deprecated, package-private 후계 미공개
class WebhookDispatchEndToEndIntegrationTest {
    @Autowired
    lateinit var dsl: DSLContext

    /**
     * Spring 컨텍스트가 조립한 autowired 워커 — 실 [OutboundUrlValidator]를 사용한다.
     * E2E-B(SSRF 차단) 시나리오 전용.
     */
    @Autowired
    lateinit var worker: WebhookDispatchWorker

    // JDK 내장 stub 서버 — 신규 의존성 0
    private val stubHitCount = AtomicInteger(0)
    private val stubLastMethod = AtomicReference<String>()
    private val stubLastBody = AtomicReference<String>()
    private lateinit var stubServer: HttpServer
    private var stubPort: Int = 0

    @BeforeEach
    fun setUpStub() {
        stubServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stubServer.createContext("/webhook") { exchange ->
            stubHitCount.incrementAndGet()
            stubLastMethod.set(exchange.requestMethod)
            stubLastBody.set(exchange.requestBody.bufferedReader().readText())
            val responseBytes = "OK".toByteArray()
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        stubServer.executor = null
        stubServer.start()
        stubPort = stubServer.address.port
        stubHitCount.set(0)
        stubLastMethod.set(null)
        stubLastBody.set(null)
    }

    @AfterEach
    fun tearDownStub() {
        stubServer.stop(0)
    }

    // ── E2E-A. happy-path 전달 ─────────────────────────────────────────────────

    /**
     * 실 파이프라인(실 pgmq → 실 worker → 실 dispatcher → 실 RestClient → 실 HTTP POST → stub 수신)을 검증한다.
     *
     * ## SSRF validator mock 우회 이유
     * stub 서버는 loopback(127.0.0.1)에서 실행되므로 실 [OutboundUrlValidator]가 "내부망 주소 차단"으로
     * 거부한다. 따라서 happy-path 전달 파이프라인을 검증하려면 validator를 permissive mock으로 교체해야 한다.
     *
     * validator의 실제 SSRF 탐지 로직은 [OutboundUrlValidatorTest](T1~T5)가 완전 검증한다.
     * 이 테스트에서는 "validator가 Allowed를 반환했을 때 메시지가 stub까지 전달되는가"를 검증한다.
     */
    @Test
    fun `E2E-A WebhookRequested 발행 후 pollAndProcess 호출 시 stub 서버가 POST와 엔벨로프를 실수신한다`() {
        val webhookUrl = "http://127.0.0.1:$stubPort/webhook"

        // SSRF validator를 permissive mock으로 교체 — loopback stub 허용
        val permissiveValidator = mockk<OutboundUrlValidator>()
        every { permissiveValidator.check(any()) } returns UrlCheck.Allowed

        // 실 RestClient — 리다이렉트 NEVER + 기본 타임아웃 적용 (WebhookHttpClientConfig 팩토리 메서드 사용)
        val testRestClient = WebhookHttpClientConfig().webhookRestClient()

        // 워커를 직접 조립 — validator만 mock, 나머지(dispatcher/RestClient/ObjectMapper)는 실 구현
        val testDispatcher = WebhookDispatcher(permissiveValidator, testRestClient, ObjectMapper())
        val testWorker = WebhookDispatchWorker(dsl, testDispatcher, ObjectMapper())

        // pgmq 큐에 WebhookRequested 메시지 발행
        dsl.execute(
            "SELECT pgmq.send(?, ?::jsonb)",
            WebhookDispatchWorker.QUEUE_NAME,
            """{"type":"WebhookRequested","payload":{"issueKey":"TEST-1","url":"$webhookUrl","method":"POST"}}""",
        )

        // testWorker(permissive validator) 로 폴링 → stub으로 HTTP POST 전송됨
        testWorker.pollAndProcess()

        // stub 서버가 POST + 엔벨로프를 실수신했는지 검증
        assertThat(stubHitCount.get())
            .describedAs("stub 서버가 정확히 1회 HTTP 요청을 수신해야 한다")
            .isEqualTo(1)

        assertThat(stubLastMethod.get())
            .describedAs("stub 서버가 POST 메서드를 수신해야 한다")
            .isEqualTo("POST")

        // 엔벨로프 JSON 검증 — 키 순서 무관하게 파싱 비교
        val mapper = ObjectMapper()
        val receivedNode = mapper.readTree(stubLastBody.get())
        assertThat(receivedNode.path("event").asText())
            .describedAs("엔벨로프 event 필드가 WebhookRequested 이어야 한다")
            .isEqualTo("WebhookRequested")
        assertThat(receivedNode.path("issueKey").asText())
            .describedAs("엔벨로프 issueKey 필드가 TEST-1 이어야 한다")
            .isEqualTo("TEST-1")

        // 처리 후 큐가 비어있음(delete 완료) 확인
        val remaining =
            dsl.fetch(
                "SELECT * FROM pgmq.read(?, ?, ?)",
                WebhookDispatchWorker.QUEUE_NAME,
                1,
                10,
            )
        assertThat(remaining.isEmpty())
            .describedAs("pollAndProcess 후 큐가 비어있어야 한다 (메시지 delete 완료)")
            .isTrue()
    }

    // ── E2E-B. SSRF 차단 경로 ─────────────────────────────────────────────────

    /**
     * 실 [OutboundUrlValidator](autowired worker)가 127.0.0.1을 SSRF로 차단한다.
     *
     * 차단 흐름: Rejected → pgmq.delete (영구 거부, 재전달 없음).
     * stub 서버는 전송을 수신하지 않아야 한다.
     */
    @Test
    fun `E2E-B 실 validator가 127-0-0-1을 차단하면 stub에 전송하지 않고 큐를 비운다`() {
        val webhookUrl = "http://127.0.0.1:$stubPort/webhook"

        // pgmq 큐에 WebhookRequested 메시지 발행
        dsl.execute(
            "SELECT pgmq.send(?, ?::jsonb)",
            WebhookDispatchWorker.QUEUE_NAME,
            """{"type":"WebhookRequested","payload":{"issueKey":"TEST-2","url":"$webhookUrl","method":"POST"}}""",
        )

        // autowired worker(실 validator) 호출 → SSRF 차단 → Rejected → delete
        worker.pollAndProcess()

        // stub 서버에 전송이 없어야 한다 (SSRF 차단 확인)
        assertThat(stubHitCount.get())
            .describedAs("SSRF 차단 시 stub 서버에 전송이 없어야 한다")
            .isEqualTo(0)

        // Rejected 후 큐가 비어있음(delete 완료) 확인
        val remaining =
            dsl.fetch(
                "SELECT * FROM pgmq.read(?, ?, ?)",
                WebhookDispatchWorker.QUEUE_NAME,
                1,
                10,
            )
        assertThat(remaining.isEmpty())
            .describedAs("Rejected 처리 후 큐가 비어있어야 한다 (영구 거부, delete 완료)")
            .isTrue()
    }

    // ── E2E-C. 부팅 안전 ──────────────────────────────────────────────────────

    @Test
    fun `E2E-C 부팅 안전 — WebhookDispatchWorker 및 관련 빈이 컨텍스트를 깨지 않는다`() {
        // 컨텍스트 로드 자체가 성공하면 통과 — worker 빈 주입 확인
        assertThat(worker).isNotNull()
    }

    // ── TestConfiguration ──────────────────────────────────────────────────────

    /**
     * WebhookDispatchWorker E2E 테스트 전용 Spring 설정.
     *
     * ## pgmq 이미지
     * `quay.io/tembo/pg16-pgmq:latest` — pgmq 확장 사전 설치.
     * `q_transition_events` 큐를 수동 생성 ([NotificationDeliveryTestcontainersConfig]의 `q_issue_events` 선례).
     *
     * ## @EnableScheduling 제거 이유
     * `@Scheduled` 워커가 백그라운드에서 폴링을 시작하면 `testWorker.pollAndProcess()` 호출 전에
     * autowired `worker`(실 validator)가 메시지를 먼저 소비하는 경쟁이 발생한다.
     * 모든 폴링은 테스트 메서드 안에서 명시적으로 호출하므로 스케줄링이 불필요하다.
     */
    @TestConfiguration
    @Suppress("DEPRECATION")
    open class WebhookE2EConfig {
        companion object {
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_webhook_e2e_test")
                    .withUsername("bts")
                    .withPassword("bts_webhook_e2e")
                    .apply { start() }

            private var migrated = false

            @Synchronized
            fun migrateOnce(
                jdbcUrl: String,
                username: String,
                password: String,
            ) {
                if (migrated) return

                java.sql.DriverManager.getConnection(jdbcUrl, username, password).use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("CREATE EXTENSION IF NOT EXISTS pgmq CASCADE")
                        // q_transition_events 큐 생성 — PR1 V022 마이그레이션 선례
                        stmt.execute("SELECT pgmq.create('q_transition_events')")
                        // q_issue_events 큐 생성 — NotificationWorker(@Scheduled) 가 읽는 큐
                        // 이 테스트 DB에 없으면 background scheduler 오류 발생
                        stmt.execute("SELECT pgmq.create('q_issue_events')")
                    }
                }

                Flyway.configure()
                    .dataSource(jdbcUrl, username, password)
                    .placeholderReplacement(false)
                    .locations("classpath:db/migration/notification")
                    .load()
                    .migrate()
                migrated = true
            }
        }

        @Bean
        open fun dataSource(): DataSource {
            migrateOnce(postgres.jdbcUrl, postgres.username, postgres.password)
            return DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        }

        @Bean
        open fun dslContext(dataSource: DataSource): DSLContext {
            val configuration =
                DefaultConfiguration()
                    .set(DataSourceConnectionProvider(TransactionAwareDataSourceProxy(dataSource)))
                    .set(SQLDialect.POSTGRES)
                    .set(DefaultExecuteListenerProvider(JooqExceptionTranslator()))
            return DefaultDSLContext(configuration)
        }

        @Bean
        open fun transactionManager(dataSource: DataSource): PlatformTransactionManager {
            return DataSourceTransactionManager(dataSource)
        }

        @Bean
        open fun taskScheduler(): ThreadPoolTaskScheduler =
            ThreadPoolTaskScheduler().apply {
                poolSize = 1
                setThreadNamePrefix("test-webhook-scheduler-")
                initialize()
            }

        @Bean
        @Primary
        open fun jwtDecoder(): JwtDecoder =
            JwtDecoder { _ ->
                throw JwtException("test stub: no JWT decoding needed for webhook e2e")
            }

        @Bean
        open fun issueRecipientLookupPort(): IssueRecipientLookupPort =
            object : IssueRecipientLookupPort {
                override fun findRecipients(issueKey: String): IssueRecipients = IssueRecipients.empty()
            }

        @Bean
        open fun userLookupPort(): UserLookupPort =
            object : UserLookupPort {
                override fun exists(userId: UUID): Boolean = false
            }

        /**
         * 테스트 전용 ProjectRecipientLookupPort fail-safe stub 빈.
         *
         * EventRecipientResolver(@Component) 가 cross-BC [ProjectRecipientLookupPort] 를 주입받으므로
         * 전체 컨텍스트를 띄우는 이 테스트에도 빈이 필요하다. 이 테스트는 PROJECT_MEMBER 수신자 fanout 을
         * 검증하지 않으므로 빈 수신자를 반환하는 fail-safe stub 으로 충분하다.
         */
        @Bean
        open fun projectRecipientLookupPort(): ProjectRecipientLookupPort =
            object : ProjectRecipientLookupPort {
                override fun findProjectRecipients(projectKey: String): ProjectRecipients = ProjectRecipients.empty()
            }

        /**
         * 테스트 전용 IssueVisibilityPort allow-all stub 빈.
         *
         * EventRecipientResolver(@Component) 가 cross-BC [IssueVisibilityPort] 를 주입받으므로
         * 전체 컨텍스트를 띄우는 이 테스트에도 빈이 필요하다. 이 테스트는 visibility 누출을 검증하지 않으므로
         * allow-all stub 이 정당하다. 실 판정은 T6, worker 배선은 T7 이 검증한다.
         */
        @Bean
        open fun issueVisibilityPort(): IssueVisibilityPort =
            object : IssueVisibilityPort {
                override fun filterVisibleUserIds(
                    issueKey: String,
                    candidateUserIds: Set<UUID>,
                ): Set<UUID> = candidateUserIds
            }
    }
}
