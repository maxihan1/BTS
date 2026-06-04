// Keycloak SAML descriptor(IdP 메타데이터) 추출 결과 VO + HTTP 추출기 — FR-AU-03 Task 7 테스트 인프라

package com.atlas.bts.identity.integration

/**
 * 실 Keycloak SAML descriptor 에서 추출한 IdP 메타데이터 (FR-AU-03 Task 7).
 *
 * saml_idp_configs 동적 주입에 필요한 세 값만 담는다.
 *
 * @property entityId IdP EntityID (descriptor 의 EntityDescriptor/@entityID)
 * @property singleSignOnUrl IdP SSO 엔드포인트(SingleSignOnService Redirect 바인딩 Location)
 * @property signingCertificatePem IdP 서명 검증용 공개 인증서(PEM, 공개값)
 */
data class KeycloakSamlMetadata(
    val entityId: String,
    val singleSignOnUrl: String,
    val signingCertificatePem: String,
)

/**
 * Keycloak SAML descriptor 엔드포인트(`/realms/{realm}/protocol/saml/descriptor`) 에서
 * IdP 메타데이터를 HTTP 로 가져와 [KeycloakSamlMetadata] 로 파싱한다 (FR-AU-03 Task 7).
 *
 * GREEN 단계에서 구현한다(현재 RED — TODO).
 */
object KeycloakSamlMetadataExtractor {
    fun extract(descriptorUrl: String): KeycloakSamlMetadata = TODO("GREEN 단계에서 descriptor XML 파싱 구현 — $descriptorUrl")
}
