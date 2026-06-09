// /api/v1/auth/route permitAll 미인증 접근 통합 검증 — 로그인 전 SSO 라우트 조회 (FR-AU-07 Task 3)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * `/api/v1/auth/route` permitAll 미인증 접근 통합 검증 (FR-AU-07 Task 3 / SDD §19).
 *
 * ## 목적
 * 로그인 화면은 미인증 상태에서 [com.atlas.bts.identity.web.DomainRouteController] 를 호출해
 * 입력 도메인이 SSO 로 라우팅되는지 조회한다. 따라서 이 경로는 SecurityConfig 에서 permitAll 이어야 한다.
 * 실제 Spring Security 필터 체인(STATELESS API 체인, Order=3)을 통과시켜 **미인증 요청이 401 이 아님**을
 * 단언한다. 도메인 매칭 여부는 무관하며(미매칭 시 matched=false 200), permitAll 통과 자체가 검증 대상이다.
 *
 * ## RED→GREEN
 * - RED: SecurityConfig 에 permitAll 미등록 상태 → 미인증 요청은 api authenticated 가드에 걸려 401.
 * - GREEN: ROUTE_PATH permitAll 등록 후 → 미인증 요청 200.
 *
 * ## 테스트 환경 (LocalAuthFlowIntegrationTest 부팅 레시피 모방)
 * - `@SpringBootTest(RANDOM_PORT)` — 실제 내장 Tomcat + Spring Security 필터 체인 전체 구동.
 * - `!prod` 프로필 — DevMemoryKeyProvider 사용 (PEM 파일 불필요).
 * - Testcontainers PostgreSQL 16 — Flyway V001~V020 자동 마이그레이션 적용.
 * - OAuth2ClientAutoConfiguration 제외 — keycloak issuer-uri OIDC discovery 원격 호출 차단.
 * - LDAP Bean @MockBean — 인증 흐름 무관, 컨텍스트 부팅용 목킹.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@Testcontainers
class RoutePermitAllIntegrationTest {
    companion object {
        /** Testcontainers PostgreSQL 16 — Flyway V001~V020 적용 대상 */
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
        }
    }

    // 인증 흐름 무관 — 컨텍스트 부팅을 위해 LDAP Bean 을 목킹한다 (LocalAuthFlowIntegrationTest 참조).
    @MockBean
    lateinit var ldapProvider: LdapProvider

    @MockBean
    lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean
    lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean
    lateinit var autoProvisionService: AutoProvisionService

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    /**
     * 미인증 GET `/api/v1/auth/route?domain=partner.com` 은 401 이 아니어야 한다 (permitAll).
     *
     * 매칭 여부는 무관하다 — 미매칭이면 matched=false 200, 매칭이면 200. 어느 쪽이든 permitAll 통과로 200 이며,
     * 핵심 단언은 "401(미인증 거부)이 아님"이다. permitAll 미등록 시 api authenticated 가드가 401 을 던진다.
     */
    @Test
    fun `route 경로는 미인증으로 접근해도 401 이 아니다 (FR-AU-07 permitAll)`() {
        val response =
            restTemplate.getForEntity(
                "http://localhost:$port/api/v1/auth/route?domain=partner.com",
                String::class.java,
            )

        assertThat(response.statusCode)
            .withFailMessage(
                "미인증 GET /api/v1/auth/route 가 401 을 반환했습니다. " +
                    "SecurityConfig 에 ROUTE_PATH permitAll 이 등록되지 않았습니다. 실제: ${response.statusCode}",
            )
            .isNotEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }
}
