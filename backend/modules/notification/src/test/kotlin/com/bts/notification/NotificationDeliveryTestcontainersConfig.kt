// STOMP E2E 통합테스트 전용 — pgmq 이미지 기반 컨테이너 + JwtDecoder 테스트 빈 제공
@file:Suppress("DEPRECATION") // JooqExceptionTranslator: Spring Boot 3.3 deprecated, package-private 후계 미공개

package com.bts.notification

import com.bts.shared.issue.IssueRecipientLookupPort
import com.bts.shared.issue.IssueRecipients
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import org.flywaydb.core.Flyway
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DataSourceConnectionProvider
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.DefaultDSLContext
import org.jooq.impl.DefaultExecuteListenerProvider
import org.springframework.boot.autoconfigure.jooq.JooqExceptionTranslator
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.Date
import javax.sql.DataSource

/**
 * STOMP end-to-end 통합테스트 전용 설정 클래스.
 *
 * ## pgmq 이미지
 * NotificationPolicyEndToEndIntegrationTest 와 달리 이 설정은 pgmq 확장이 사전 설치된
 * `quay.io/tembo/pg16-pgmq:latest` 이미지를 사용한다.
 * NotificationWorker 가 `pgmq.read / pgmq.send / pgmq.delete` 를 호출하기 때문이다.
 * (memory: concurrent-testcontainers-suite-flaky — JVM 단위 singleton 패턴 표준.)
 * (issue-tracking IssueTestcontainersBase 와 동일한 이미지 채택.)
 *
 * ## JwtDecoder 테스트 빈 — 방법 (b) 선택 이유
 * WebSocketConfig 가 주입받는 JwtDecoder 빈은 런타임에 identity-access 모듈이 제공하지만,
 * notification 단독 컨텍스트에서는 identity-access 가 없다.
 * RSA 키쌍(방법 a)보다 단순한 방법으로: 특정 고정 토큰 문자열 → Jwt(subject=userId) 를 반환하는
 * stub JwtDecoder 를 @Primary 빈으로 등록한다.
 * 알 수 없는 토큰은 JwtException 을 던져 StompAuthChannelInterceptor 가 거부하도록 한다.
 * (S6 미인증 거부 시나리오 검증 가능.)
 */
@TestConfiguration
class NotificationDeliveryTestcontainersConfig {

    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container — pgmq 확장 사전 설치 이미지.
         *
         * NotificationTestcontainersConfig 와 별도 컨테이너로 격리:
         * 기존 테스트는 V400~V401, 이 컨테이너는 V400~V403(notifications 테이블 + pgmq 큐 필요).
         * `.apply { start() }` 로 JVM 초기화 시점에 1회 기동, Ryuk 이 JVM 종료 시 자동 정리.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_notification_delivery_test")
                .withUsername("bts")
                .withPassword("bts_delivery_test")
                .apply { start() }

        private var migrated = false

        /**
         * Flyway V400~V403 마이그레이션을 1회만 실행한다.
         *
         * - V400: notification_policies 테이블
         * - V401: 기본 정책 시드
         * - V402: notifications 테이블
         * - V403: issue.mentioned 정책 시드 (MENTIONED×IN_APP)
         */
        @Synchronized
        fun migrateOnce(
            jdbcUrl: String,
            username: String,
            password: String,
        ) {
            if (migrated) return

            // pgmq extension 설치 — quay.io/tembo/pg16-pgmq 이미지에 바이너리는 있지만
            // DB 단위로 CREATE EXTENSION 을 명시적으로 실행해야 스키마가 생성된다.
            // issue-tracking IssueTestcontainersBase 와 동일한 이미지 채택 + V002 마이그레이션 선례.
            java.sql.DriverManager.getConnection(jdbcUrl, username, password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE EXTENSION IF NOT EXISTS pgmq CASCADE")
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

    /**
     * pgmq 이미지 기반 Testcontainers DataSource 빈.
     *
     * Flyway V400~V403 를 적용한 뒤 DataSource 를 반환한다.
     */
    @Bean
    fun dataSource(): DataSource {
        migrateOnce(postgres.jdbcUrl, postgres.username, postgres.password)
        return DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    /**
     * jOOQ DSLContext 빈.
     *
     * NotificationTestcontainersConfig 와 동일 구성:
     * JooqExceptionTranslator 로 UNIQUE 위반을 DuplicateKeyException 으로 변환.
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
     * 테스트 전용 SecurityFilterChain — /ws WebSocket 업그레이드 경로를 permitAll 한다.
     *
     * Spring Boot 자동 구성으로 Spring Security 의 기본 HttpBasic 필터가 활성화되면
     * WebSocket Upgrade 요청에 401 이 반환되어 Tomcat WS 클라이언트가 실패한다.
     * STOMP 인증은 StompAuthChannelInterceptor(CONNECT frame JWT)가 담당하므로
     * HTTP 레이어는 WebSocket 경로를 무조건 통과시킨다.
     *
     * S6 미인증 거부 검증은 STOMP CONNECT frame 레벨에서 수행되므로 HTTP permitAll 과 충돌 없음.
     */
    @Bean
    fun testSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { authz ->
                authz.anyRequest().permitAll()
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
        return http.build()
    }

    /**
     * 테스트 전용 IssueRecipientLookupPort fail-safe stub 빈.
     *
     * MENTIONED 역할 수신자는 이벤트 payload 의 mentionedUserIds 에서 직접 추출하므로
     * 포트 조회가 필요하지 않다. 이 stub 는 항상 빈 수신자를 반환한다(안전 방향).
     * (memory: crossbc-resolver-nullable-fail-open — 빈 부재 시 fail-closed 가 아닌 fail-open 위험.)
     */
    @Bean
    fun issueRecipientLookupPort(): IssueRecipientLookupPort =
        object : IssueRecipientLookupPort {
            override fun findRecipients(issueKey: String): IssueRecipients = IssueRecipients.empty()
        }

    /**
     * 테스트 전용 JwtDecoder stub 빈.
     *
     * WebSocketConfig 가 주입받는 Spring 표준 [JwtDecoder] 를 @Primary 로 교체한다.
     * identity-access 의 prod JwtDecoder(RSA 검증) 없이 StompAuthChannelInterceptor 가 동작하게 한다.
     *
     * ## 동작 규칙
     * - [NotificationDeliveryEndToEndIntegrationTest.RECIPIENT_JWT_TOKEN] → subject=[RECIPIENT_USER_ID]
     * - 그 외 모든 토큰 → JwtException 던짐 (S6 미인증 거부 경로 활성화)
     *
     * @return stub [JwtDecoder] 구현
     */
    @Bean
    @Primary
    fun jwtDecoder(): JwtDecoder =
        JwtDecoder { token ->
            if (token == NotificationDeliveryEndToEndIntegrationTest.RECIPIENT_JWT_TOKEN) {
                buildTestJwt(
                    subject = NotificationDeliveryEndToEndIntegrationTest.RECIPIENT_USER_ID.toString(),
                    token = token,
                )
            } else {
                throw JwtException("test stub: unknown token '$token'")
            }
        }

    /**
     * 테스트용 [Jwt] 객체를 생성한다.
     *
     * Spring Security OAuth2 [Jwt] 는 생성자가 없고 [Jwt.withTokenValue] builder 를 사용한다.
     * PlainJWT (Nimbus) 로 claims 를 채운 뒤 [Jwt.withTokenValue] 로 래핑한다.
     *
     * @param subject JWT sub claim — userId
     * @param token 원본 토큰 문자열 (builder 에 저장, 필드 참조용)
     * @return Spring Security 호환 [Jwt]
     */
    private fun buildTestJwt(
        subject: String,
        token: String,
    ): Jwt {
        val now = Instant.now()
        val claimsSet =
            JWTClaimsSet.Builder()
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build()
        // PlainJWT: 서명 없는 JWT — 테스트 stub 전용. 프로덕션에서 절대 사용 금지.
        PlainJWT(claimsSet)

        return Jwt.withTokenValue(token)
            .header("alg", "none")
            .subject(subject)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(3600))
            .build()
    }
}
