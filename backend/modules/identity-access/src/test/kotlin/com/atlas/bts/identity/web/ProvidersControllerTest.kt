// ProvidersController 슬라이스 테스트 — FR-AU-06 username/password 계열만 + DB enabled/sort_order 오버레이

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.provider.AuthnProviderConfigRepository
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.spi.AuthenticationProvider
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import com.atlas.bts.identity.spi.ProviderRegistry
import com.atlas.bts.identity.spi.ProviderType
import com.atlas.bts.identity.spi.fake.FakeLdapProvider
import com.atlas.bts.identity.spi.fake.FakeLocalProvider
import com.atlas.bts.identity.spi.fake.FakePatProvider
import io.mockk.every
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
 * ProvidersController 슬라이스 테스트 (FR-AU-06 / Task 5).
 *
 * 검증 항목.
 * - (a) username/password 계열(LOCAL/LDAP)만 응답, SAML/OIDC/PAT/OAUTH 제외
 * - (b) DB enabled 오버레이 — isEnabled(LDAP)=false 면 LDAP 제외
 * - (c) sort_order 오름차순 정렬 (DB row 없는 type 은 기본값 처리로 노출)
 * - (d) 기존 필드(id/type/displayName/priority/available) 보존, "auto" 항목 없음
 * - (e) 인증 없이 200 반환 (permitAll)
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
        fun corsConfigurationSource(): CorsConfigurationSource {
            return CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))
        }

        // SecurityConfig 가 PatAuthenticationFilter 생성을 위해 요구하는 Bean.
        // ProvidersController 자체는 PAT 를 사용하지 않으나 SecurityFilterChain 빌드 시점에 필요하다.
        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        /**
         * LDAP(80) + Local(70) + PAT(60) + SAML(40) + OIDC(50) 다섯 공급자 등록.
         * Fake LDAP/Local/PAT 은 @Profile("test-spi")이므로 직접 인스턴스화한다.
         * SAML/OIDC 는 fake 가 없으므로 익명 [AuthenticationProvider] 로 등록해 제외 동작을 검증한다.
         */
        @Bean
        fun providerRegistry(): ProviderRegistry =
            ProviderRegistry(
                listOf(
                    FakeLdapProvider(),
                    FakeLocalProvider(),
                    FakePatProvider(),
                    StubSsoProvider(ProviderType.SAML),
                    StubSsoProvider(ProviderType.OIDC),
                ),
            )

        /**
         * 슬라이스 테스트 기본 mock — 모든 type 활성(isEnabled=true),
         * listEnabledByTypes 는 비어있음(DB row 없음 → 기본값 sort_order 처리 경로).
         */
        @Bean
        fun authnProviderConfigRepository(): AuthnProviderConfigRepository {
            val repo: AuthnProviderConfigRepository = mockk()
            every { repo.isEnabled(any()) } returns true
            every { repo.listEnabledByTypes(any()) } returns emptyList()
            return repo
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    /** SAML/OIDC 제외 동작 검증용 stub — supports/authenticate 는 호출되지 않는다. */
    class StubSsoProvider(override val type: ProviderType) : AuthenticationProvider {
        override fun supports(credential: Credential): Boolean = false

        override fun authenticate(credential: Credential): AuthnResult {
            return AuthnResult.Failure(FailureReason.INVALID_INPUT)
        }
    }

    // --- (a) username/password 계열(LOCAL/LDAP)만, SAML/OIDC/PAT/OAUTH 제외 -------------

    @Test
    fun `providers 목록은 LOCAL LDAP 만 포함하고 SAML OIDC PAT 는 제외한다`() {
        // 기본 mock: 모든 type 활성, DB row 없음 → sort_order 기본값.
        // 기본값 동률 시 priority 내림차순 → LDAP(80) > LOCAL(70).
        mockMvc.perform(get("/api/v1/auth/providers"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.providers").isArray)
            .andExpect(jsonPath("$.providers.length()").value(2))
            .andExpect(jsonPath("$.providers[?(@.type == 'PAT')]").isEmpty)
            .andExpect(jsonPath("$.providers[?(@.type == 'SAML')]").isEmpty)
            .andExpect(jsonPath("$.providers[?(@.type == 'OIDC')]").isEmpty)
            .andExpect(jsonPath("$.providers[?(@.type == 'OAUTH')]").isEmpty)
            .andExpect(jsonPath("$.providers[?(@.type == 'LOCAL')]").exists())
            .andExpect(jsonPath("$.providers[?(@.type == 'LDAP')]").exists())
    }

    // --- (b) DB enabled 오버레이 — isEnabled(LDAP)=false 면 LDAP 제외 -------------------

    @Test
    fun `isEnabled(LDAP)=false 이면 LDAP 는 응답 목록에서 제외된다`() {
        val registry = MockBeans().providerRegistry()
        val repo: AuthnProviderConfigRepository = mockk()
        every { repo.isEnabled(ProviderType.LDAP) } returns false
        every { repo.isEnabled(ProviderType.LOCAL) } returns true
        every { repo.listEnabledByTypes(any()) } returns emptyList()

        val response = ProvidersController(registry, repo).listProviders()

        val types = response.providers.map { it.type }
        assert(types == listOf("LOCAL")) {
            "LDAP disabled 시 LOCAL 만 남아야 합니다. 실제: $types"
        }
    }

    // --- (c) sort_order 오름차순 정렬 --------------------------------------------------

    @Test
    fun `DB sort_order 오름차순으로 정렬된다`() {
        val registry = MockBeans().providerRegistry()
        val repo: AuthnProviderConfigRepository = mockk()
        every { repo.isEnabled(any()) } returns true
        // LOCAL sort_order=1 (앞), LDAP sort_order=2 (뒤) — priority 만 보면 LDAP 이 앞이지만 sort_order 가 우선.
        every { repo.listEnabledByTypes(any()) } returns
            listOf(ProviderType.LOCAL to 1, ProviderType.LDAP to 2)

        val response = ProvidersController(registry, repo).listProviders()

        val types = response.providers.map { it.type }
        assert(types == listOf("LOCAL", "LDAP")) {
            "sort_order 오름차순(LOCAL=1, LDAP=2)이어야 합니다. 실제: $types"
        }
    }

    @Test
    fun `DB row 없는 type 은 기본값으로 처리되어 노출되고 동률 시 priority 내림차순이다`() {
        val registry = MockBeans().providerRegistry()
        val repo: AuthnProviderConfigRepository = mockk()
        every { repo.isEnabled(any()) } returns true
        // listEnabledByTypes 가 비어있음 → LOCAL/LDAP 모두 sort_order 기본값(동률).
        every { repo.listEnabledByTypes(any()) } returns emptyList()

        val response = ProvidersController(registry, repo).listProviders()

        val types = response.providers.map { it.type }
        // 동률 → priority 내림차순 → LDAP(80) > LOCAL(70).
        assert(types == listOf("LDAP", "LOCAL")) {
            "DB row 없으면 기본값 동률→priority 내림차순(LDAP, LOCAL)이어야 합니다. 실제: $types"
        }
    }

    @Test
    fun `DB row 있는 type 이 row 없는 type 보다 앞선다`() {
        val registry = MockBeans().providerRegistry()
        val repo: AuthnProviderConfigRepository = mockk()
        every { repo.isEnabled(any()) } returns true
        // LDAP 만 row 존재(sort_order=5), LOCAL 은 row 없음(기본값 = 큰 상수).
        every { repo.listEnabledByTypes(any()) } returns listOf(ProviderType.LDAP to 5)

        val response = ProvidersController(registry, repo).listProviders()

        val types = response.providers.map { it.type }
        // LDAP(5) < LOCAL(기본 큰 상수) → LDAP 가 앞.
        assert(types == listOf("LDAP", "LOCAL")) {
            "row 있는 LDAP(5)가 row 없는 LOCAL(기본값)보다 앞서야 합니다. 실제: $types"
        }
    }

    // --- (d) 필드 보존 + "auto" 항목 없음 ----------------------------------------------

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
    fun `응답에 auto 항목은 존재하지 않는다`() {
        mockMvc.perform(get("/api/v1/auth/providers"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.providers[?(@.id == 'auto')]").isEmpty)
            .andExpect(jsonPath("$.providers[?(@.type == 'AUTO')]").isEmpty)
    }

    @Test
    fun `LDAP 공급자가 unavailable 상태이면 available=false 로 응답된다`() {
        val registry = ProviderRegistry(listOf(FakeLdapProvider(_available = false), FakeLocalProvider()))
        val repo: AuthnProviderConfigRepository = mockk()
        every { repo.isEnabled(any()) } returns true
        every { repo.listEnabledByTypes(any()) } returns emptyList()

        val response = ProvidersController(registry, repo).listProviders()

        val ldapEntry = response.providers.first { it.type == "LDAP" }
        assert(!ldapEntry.available) {
            "LDAP unavailable 상태에서 available=false 이어야 합니다. 실제: ${ldapEntry.available}"
        }
    }

    // --- (e) 인증 없이 200 반환 (permitAll) --------------------------------------------

    @Test
    fun `providers 경로는 인증 없이 200 을 반환한다 (permitAll)`() {
        mockMvc.perform(get("/api/v1/auth/providers"))
            .andExpect(status().isOk)
    }
}
