// ProvidersController 슬라이스 테스트 — FR-09-22 providers 목록 조회 (permitAll, priority 내림차순)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.spi.AuthenticationProvider
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.ProviderRegistry
import com.atlas.bts.identity.spi.ProviderType
import com.atlas.bts.identity.spi.fake.FakeLdapProvider
import com.atlas.bts.identity.spi.fake.FakeLocalProvider
import com.atlas.bts.identity.spi.fake.FakePatProvider
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * ProvidersController 슬라이스 테스트 (FR-AU-09-22 / Task 22).
 *
 * 검증 항목.
 * - (a) LDAP + Local + PAT 등록 시 priority 내림차순 응답, PAT 제외
 * - (b) LDAP unavailable 시 available=false 반영
 * - (c) 인증 없이 200 반환 (permitAll)
 */
@WebMvcTest(
    controllers = [ProvidersController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(
    SecurityConfig::class,
    ProvidersControllerTest.MockBeans::class,
)
class ProvidersControllerTest {

    @TestConfiguration
    class MockBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            val clock = Clock.fixed(Instant.parse("2026-05-21T10:00:00Z"), ZoneOffset.UTC)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource =
            CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))

        /**
         * LDAP(80) + Local(70) + PAT(60) 세 공급자 등록.
         * FakeLdapProvider / FakeLocalProvider / FakePatProvider 는 @Profile("test-spi")이므로
         * 여기서 직접 인스턴스화하여 ProviderRegistry 에 공급한다.
         */
        @Bean
        fun providerRegistry(): ProviderRegistry =
            ProviderRegistry(listOf(FakeLdapProvider(), FakeLocalProvider(), FakePatProvider()))

        /** LDAP unavailable 상태를 시뮬레이션하는 ProviderRegistry. */
        @Bean("unavailableLdapRegistry")
        fun unavailableLdapRegistry(): ProviderRegistry =
            ProviderRegistry(listOf(FakeLdapProvider(_available = false), FakeLocalProvider()))
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    // --- (a) LDAP + Local + PAT 등록 시 priority 내림차순, PAT 제외 -------------------

    @Test
    fun `providers 목록은 PAT 제외 후 priority 내림차순으로 반환된다`() {
        // LDAP(80) > Local(70). PAT 는 응답에 포함되지 않는다.
        mockMvc.perform(get("/api/v1/auth/providers"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.providers").isArray)
            .andExpect(jsonPath("$.providers.length()").value(2))
            .andExpect(jsonPath("$.providers[0].type").value("LDAP"))
            .andExpect(jsonPath("$.providers[0].priority").value(80))
            .andExpect(jsonPath("$.providers[1].type").value("LOCAL"))
            .andExpect(jsonPath("$.providers[1].priority").value(70))
    }

    @Test
    fun `providers 응답에는 id, type, displayName, priority, available 필드가 포함된다`() {
        mockMvc.perform(get("/api/v1/auth/providers"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.providers[0].id").isString)
            .andExpect(jsonPath("$.providers[0].type").isString)
            .andExpect(jsonPath("$.providers[0].displayName").isString)
            .andExpect(jsonPath("$.providers[0].priority").isNumber)
            .andExpect(jsonPath("$.providers[0].available").isBoolean)
    }

    @Test
    fun `PAT provider 는 응답 목록에 포함되지 않는다`() {
        mockMvc.perform(get("/api/v1/auth/providers"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.providers[?(@.type == 'PAT')]").isEmpty)
    }

    // --- (b) LDAP unavailable 시 available=false 반영 ----------------------------------

    @Test
    fun `LDAP 공급자가 unavailable 상태이면 available=false 로 응답된다`() {
        // unavailableLdapRegistry 는 별도 Bean — 이 테스트 슬라이스는 기본 providerRegistry Bean 사용.
        // available=false 케이스를 직접 검증하기 위해 별도 컨트롤러 인스턴스를 수동 생성.
        val registry = MockBeans().unavailableLdapRegistry()
        val controller = ProvidersController(registry)
        val response = controller.listProviders()

        val ldapEntry = response.providers.first { it.type == "LDAP" }
        assert(!ldapEntry.available) {
            "LDAP unavailable 상태에서 available=false 이어야 합니다. 실제: ${ldapEntry.available}"
        }
    }

    // --- (c) 인증 없이 200 반환 (permitAll) --------------------------------------------

    @Test
    fun `providers 경로는 인증 없이 200 을 반환한다 (permitAll)`() {
        mockMvc.perform(get("/api/v1/auth/providers"))
            .andExpect(status().isOk)
    }
}
