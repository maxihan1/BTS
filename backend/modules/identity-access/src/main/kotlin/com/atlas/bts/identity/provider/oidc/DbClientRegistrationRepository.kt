// DB 기반 ClientRegistrationRepository 구현 — oidc_provider_configs → Spring OAuth2 ClientRegistration 변환

package com.atlas.bts.identity.provider.oidc

import com.atlas.bts.identity.config.SecretEncryptor
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ClientRegistrations
import org.springframework.stereotype.Component

/**
 * issuer discovery 추상화 — .well-known/openid-configuration 조회로 [ClientRegistration.Builder] 를 얻는다.
 *
 * 실 네트워크 의존을 [DbClientRegistrationRepository] 에서 분리해 단위 테스트가 가능하도록 한다.
 * 프로덕션 구현은 [SpringIssuerLocationDiscovery](= [ClientRegistrations.fromIssuerLocation]).
 */
fun interface IssuerLocationDiscovery {
    /**
     * issuer URI 의 .well-known/openid-configuration 을 조회해 빌더를 반환한다.
     *
     * @param issuerUri OIDC issuer URI.
     * @return endpoint(authorization/token/jwks 등)가 채워진 [ClientRegistration.Builder].
     * @throws IllegalArgumentException issuer 조회 실패(IdP 다운/오설정) 시.
     */
    fun discover(issuerUri: String): ClientRegistration.Builder
}

/**
 * 프로덕션 issuer discovery — Spring 의 [ClientRegistrations.fromIssuerLocation] 위임.
 *
 * 호출 시점에만 네트워크를 사용한다(생성자에서 호출하지 않음 — EC6 부팅 분리).
 */
@Component
class SpringIssuerLocationDiscovery : IssuerLocationDiscovery {
    override fun discover(issuerUri: String): ClientRegistration.Builder {
        return ClientRegistrations.fromIssuerLocation(issuerUri)
    }
}

/**
 * Spring Security 의 [ClientRegistrationRepository] 를 DB(oidc_provider_configs) 로 구현한다 (FR-AU-04).
 *
 * OAuth2/OIDC 인증 필터가 registration_id 로 client 설정을 요청하면, [OidcProviderConfigReader] 에서
 * **활성(enabled=true)** 설정을 읽어 [ClientRegistration] 으로 변환한다.
 * 비활성/미존재 registration_id 는 null 을 반환한다(계약 — EC5).
 *
 * **변환 매핑**:
 * - issuer discovery([IssuerLocationDiscovery])로 endpoint 빌더 획득(.well-known/openid-configuration).
 * - registrationId / clientId → config 값.
 * - clientSecret → [SecretEncryptor.decrypt] 결과(평문). **복호화된 secret 은 절대 로깅 금지**(§1.1.2) —
 *   여기에서도 로그를 남기지 않는다(필요 시 registrationId 만 로깅 가능).
 * - scope → config.scopes 를 콤마로 분리.
 * - redirectUri → Spring 표준 템플릿 {baseUrl}/login/oauth2/code/{registrationId} (런타임 baseUrl 치환).
 * - authorizationGrantType → AUTHORIZATION_CODE. clientAuthenticationMethod 는 discovery 기본값 사용.
 *
 * **EC6 lazy**: discovery 호출은 [findByRegistrationId] 요청 시점에만 발생한다. 생성자/빈 초기화에서
 * 모든 issuer 를 미리 호출하지 않으므로 IdP 다운이 BTS 부팅을 막지 않는다.
 *
 * **캐시 결정**: discovery 결과([ClientRegistration.Builder])는 매 호출 mutable 객체이고
 * 호출자가 clientId/secret 등으로 변형하므로 빌더 자체를 공유 캐시하면 thread-safety/오염 위험이 있다.
 * 또 enabled 토글·secret 회전 즉시 반영을 위해 config 는 매 호출 재조회가 안전하다. 따라서 영구 캐시는
 * 두지 않는다(과한 캐시 회피). discovery 네트워크 비용 최적화가 필요해지면 issuer 메타데이터(불변)만
 * 별도 캐시하는 방식을 [IssuerLocationDiscovery] 구현 내부에서 추가한다(본 클래스 계약 불변).
 */
@Component
class DbClientRegistrationRepository(
    private val configReader: OidcProviderConfigReader,
    private val secretEncryptor: SecretEncryptor,
    private val discovery: IssuerLocationDiscovery,
) : ClientRegistrationRepository {
    /**
     * registration_id 로 활성 OIDC 설정을 읽어 [ClientRegistration] 으로 변환한다.
     * 비활성/미존재면 null 을 반환한다(EC5). discovery 는 활성 설정이 있을 때만 호출된다(EC6).
     */
    override fun findByRegistrationId(registrationId: String): ClientRegistration? {
        val config = configReader.findByRegistrationId(registrationId)?.takeIf { it.enabled } ?: return null

        val clientSecret = secretEncryptor.decrypt(config.clientSecretEncrypted)
        val scopes = config.scopes.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        return discovery
            .discover(config.issuerUri)
            .registrationId(config.registrationId)
            .clientId(config.clientId)
            .clientSecret(clientSecret)
            .redirectUri(REDIRECT_URI_TEMPLATE)
            .scope(scopes)
            .build()
    }

    private companion object {
        /** Spring Security 표준 OAuth2 콜백 placeholder — 런타임에 baseUrl/registrationId 로 치환 */
        const val REDIRECT_URI_TEMPLATE = "{baseUrl}/login/oauth2/code/{registrationId}"
    }
}
