// DbClientRegistrationRepository 단위 테스트 — OidcProviderConfig → ClientRegistration 변환

package com.atlas.bts.identity.provider.oidc

import com.bts.shared.crypto.SecretEncryptor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import java.util.UUID

/**
 * DbClientRegistrationRepository 단위 테스트 (FR-AU-04).
 *
 * - OidcProviderConfigRepository / SecretEncryptor / issuer discovery 를 stub 으로 대체해
 *   config → ClientRegistration 변환 로직만 검증한다(실 네트워크 의존 제거).
 * - issuer discovery 호출은 [IssuerLocationDiscovery] stub 으로 대체한다(실 Keycloak discovery 는
 *   Task 7 통합테스트 위임). 따라서 .well-known 네트워크 호출이 발생하지 않는다.
 * - client_secret 복호화가 적용되는지(SecretEncryptor.decrypt 결과가 clientSecret 에 반영).
 * - 비활성/미존재 registrationId → null.
 */
class DbClientRegistrationRepositoryTest {
    // SecretEncryptor 는 final class 라 mockk 대신 실제 인스턴스 사용(round-trip 검증 겸).
    private val encryptor =
        SecretEncryptor(
            password = "unit-test-key",
            // 16바이트 hex salt (Encryptors.stronger 가 hex 검증)
            hexSalt = "0123456789abcdef0123456789abcdef",
        )

    /** 테스트용 in-memory config 저장소 — 네트워크/DB 없이 동작 */
    private class FakeConfigRepo(
        private val byRegId: Map<String, OidcProviderConfig>,
    ) : OidcProviderConfigReader {
        override fun findByRegistrationId(registrationId: String): OidcProviderConfig? = byRegId[registrationId]

        // FR-AU-08b B1 — enabled 만 반환(콜백 enabled 재해소). 본 변환 테스트에서는 호출되지 않으나
        // 인터페이스 확장(findEnabledByRegistrationId)에 맞춰 stub 을 둬 컴파일을 유지한다.
        override fun findEnabledByRegistrationId(registrationId: String): OidcProviderConfig? =
            byRegId[registrationId]?.takeIf { it.enabled }
    }

    /**
     * issuer discovery stub — 실 네트워크(.well-known) 대신 고정 엔드포인트로 빌더를 만든다.
     * 호출 여부/인자를 기록해 lazy 호출(EC6)을 검증한다.
     */
    private class StubDiscovery : IssuerLocationDiscovery {
        val calledIssuers = mutableListOf<String>()

        override fun discover(issuerUri: String): ClientRegistration.Builder {
            calledIssuers.add(issuerUri)
            return ClientRegistration
                .withRegistrationId("stub")
                .authorizationUri("$issuerUri/auth")
                .tokenUri("$issuerUri/token")
                .userInfoUri("$issuerUri/userinfo")
                .jwkSetUri("$issuerUri/jwks")
                .userNameAttributeName("sub")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .clientId("PLACEHOLDER")
        }
    }

    private fun config(
        registrationId: String,
        rawSecret: String = "super-secret",
        scopes: String = "openid,profile,email",
    ): OidcProviderConfig =
        OidcProviderConfig(
            id = UUID.randomUUID(),
            registrationId = registrationId,
            displayName = "Keycloak",
            issuerUri = "https://idp.example.com",
            clientId = "bts-client",
            clientSecretEncrypted = encryptor.encrypt(rawSecret),
            scopes = scopes,
            authnProviderId = UUID.fromString("00000000-0000-4a04-8000-000000000004"),
            enabled = true,
        )

    @Test
    fun `findByRegistrationId — config 를 ClientRegistration 으로 변환하고 secret 복호화 적용`() {
        val cfg = config("keycloak", rawSecret = "super-secret")
        val discovery = StubDiscovery()
        val sut = DbClientRegistrationRepository(FakeConfigRepo(mapOf("keycloak" to cfg)), encryptor, discovery)

        val reg = sut.findByRegistrationId("keycloak")

        assertThat(reg).isNotNull()
        assertThat(reg!!.registrationId).isEqualTo("keycloak")
        assertThat(reg.clientId).isEqualTo("bts-client")
        // 복호화된 secret 이 ClientRegistration 에 반영된다(암호문이 아님)
        assertThat(reg.clientSecret).isEqualTo("super-secret")
        assertThat(reg.scopes).containsExactlyInAnyOrder("openid", "profile", "email")
        assertThat(reg.authorizationGrantType).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE)
        assertThat(reg.redirectUri).isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}")
    }

    @Test
    fun `findByRegistrationId — discovery 는 요청 시점에만 호출된다 (EC6 lazy)`() {
        val cfg = config("keycloak")
        val discovery = StubDiscovery()
        val sut = DbClientRegistrationRepository(FakeConfigRepo(mapOf("keycloak" to cfg)), encryptor, discovery)

        // 생성자에서 discovery 가 호출되지 않아야 한다(부팅이 IdP 다운에 막히지 않음)
        assertThat(discovery.calledIssuers).isEmpty()

        sut.findByRegistrationId("keycloak")

        assertThat(discovery.calledIssuers).containsExactly("https://idp.example.com")
    }

    @Test
    fun `findByRegistrationId — 미존재 registrationId 는 null 이고 discovery 호출 안 함`() {
        val discovery = StubDiscovery()
        val sut = DbClientRegistrationRepository(FakeConfigRepo(emptyMap()), encryptor, discovery)

        assertThat(sut.findByRegistrationId("missing")).isNull()
        assertThat(discovery.calledIssuers).isEmpty()
    }
}
