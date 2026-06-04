// DB 기반 RelyingPartyRegistrationRepository 구현 — saml_idp_configs → Spring SAML2 변환

package com.atlas.bts.identity.provider.saml

import org.springframework.security.saml2.core.Saml2X509Credential
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Spring Security 의 [RelyingPartyRegistrationRepository] 를 DB(saml_idp_configs) 로 구현한다 (FR-AU-03).
 *
 * SAML2 인증 필터가 registration_id 로 IdP 설정을 요청하면, [SamlIdpConfigRepository] 에서
 * **활성(enabled=true)** 설정을 읽어 [RelyingPartyRegistration] 으로 변환한다.
 * 비활성/미존재 registration_id 는 null 을 반환한다(계약 — EC5).
 *
 * **변환 매핑 (spring-security-saml2 6.3.x API)**:
 * - registrationId → [RelyingPartyRegistration.withRegistrationId]
 * - idp_entity_id → assertingPartyDetails.entityId
 * - idp_sso_url → assertingPartyDetails.singleSignOnServiceLocation
 * - idp_x509_cert(PEM) → [Saml2X509Credential.verification] (IdP 서명 검증용)
 *
 * assertionConsumerServiceLocation 은 Spring 표준 placeholder 템플릿을 사용한다
 * (빌더의 build() 가 ACS 위치를 요구). 실제 URL 은 런타임에 요청 baseUrl 로 치환된다.
 */
@Component
class DbRelyingPartyRegistrationRepository(
    private val configRepo: SamlIdpConfigRepository,
) : RelyingPartyRegistrationRepository {
    override fun findByRegistrationId(registrationId: String): RelyingPartyRegistration? {
        val config = configRepo.findEnabledByRegistrationId(registrationId) ?: return null

        val cert = PemCertificateParser.parse(config.idpX509Cert)

        return RelyingPartyRegistration
            .withRegistrationId(config.registrationId)
            .assertionConsumerServiceLocation(ACS_LOCATION_TEMPLATE)
            .assertingPartyDetails { idp ->
                idp.entityId(config.idpEntityId)
                idp.singleSignOnServiceLocation(config.idpSsoUrl)
                idp.verificationX509Credentials { creds ->
                    creds.add(Saml2X509Credential.verification(cert))
                }
            }
            .build()
    }

    private companion object {
        /** Spring Security 표준 ACS placeholder — 런타임에 baseUrl/registrationId 로 치환 */
        const val ACS_LOCATION_TEMPLATE = "{baseUrl}/login/saml2/sso/{registrationId}"
    }
}

/**
 * PEM 형식 X.509 인증서 문자열을 [X509Certificate] 로 파싱하는 헬퍼.
 *
 * IdP 의 공개 서명 검증 인증서는 DB 에 PEM(Base64 + BEGIN/END 헤더) 으로 저장된다.
 * 표준 JDK [CertificateFactory] 를 사용하므로 외부 의존성이 없다.
 */
private object PemCertificateParser {
    private const val X509_TYPE = "X.509"

    /**
     * PEM 인증서 문자열을 파싱한다.
     * 형식이 잘못되었으면 [java.security.cert.CertificateException] 을 던진다(은폐 금지).
     */
    fun parse(pem: String): X509Certificate {
        val factory = CertificateFactory.getInstance(X509_TYPE)
        ByteArrayInputStream(pem.toByteArray(StandardCharsets.UTF_8)).use { stream ->
            return factory.generateCertificate(stream) as X509Certificate
        }
    }
}
