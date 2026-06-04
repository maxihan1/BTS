// Keycloak SAML descriptor(IdP 메타데이터) 추출 결과 VO + HTTP 추출기 — FR-AU-03 Task 7 테스트 인프라

package com.atlas.bts.identity.integration

import org.w3c.dom.Element
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

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
 * ## 파싱 대상
 * Keycloak SAML descriptor 는 SAML 2.0 메타데이터 표준 `EntityDescriptor` 다.
 * - `EntityDescriptor/@entityID` → [KeycloakSamlMetadata.entityId]
 * - `IDPSSODescriptor/SingleSignOnService[@Binding=HTTP-Redirect]/@Location` → [KeycloakSamlMetadata.singleSignOnUrl]
 * - `IDPSSODescriptor/KeyDescriptor[@use=signing]//X509Certificate` → PEM 으로 래핑한 서명 인증서
 *
 * ## XXE 방어 (DEVELOPMENT.md §1 — 외부 입력 불신)
 * descriptor XML 은 컨테이너의 IdP 가 주는 외부 입력이다. [DocumentBuilderFactory] 에
 * `disallow-doctype-decl` + 외부 엔티티 비활성화를 적용해 XXE(XML External Entity) 주입을 차단한다.
 *
 * ## 의존성 표면
 * JDK 내장 [HttpClient] + DOM 파서만 사용한다(새 라이브러리 의존 없음).
 */
object KeycloakSamlMetadataExtractor {
    /** SAML 2.0 메타데이터 네임스페이스. */
    private const val NS_METADATA = "urn:oasis:names:tc:SAML:2.0:metadata"

    /** XML Digital Signature 네임스페이스(X509Certificate 요소). */
    private const val NS_XMLDSIG = "http://www.w3.org/2000/09/xmldsig#"

    /** HTTP-Redirect 바인딩 식별자(SSO URL 선택 기준). */
    private const val BINDING_HTTP_REDIRECT = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect"

    /** PEM 본문 줄바꿈 폭(64자) — 표준 PEM 인코딩. */
    private const val PEM_LINE_WIDTH = 64

    private val httpClient: HttpClient = HttpClient.newHttpClient()

    /**
     * descriptor URL 에서 IdP 메타데이터를 가져와 파싱한다.
     *
     * @throws IllegalStateException HTTP 비정상 응답 또는 필수 요소 부재(서명 인증서/SSO URL/EntityID) 시.
     */
    fun extract(descriptorUrl: String): KeycloakSamlMetadata {
        val xml = fetch(descriptorUrl)
        val root = parseSecurely(xml)

        val entityId =
            root.getAttribute("entityID").ifBlank {
                error("descriptor 에 entityID 없음 — url=$descriptorUrl")
            }
        val ssoUrl =
            singleSignOnRedirectLocation(root)
                ?: error("descriptor 에 HTTP-Redirect SingleSignOnService 없음 — url=$descriptorUrl")
        val cert =
            signingCertificatePem(root)
                ?: error("descriptor 에 signing X509Certificate 없음 — url=$descriptorUrl")

        return KeycloakSamlMetadata(entityId = entityId, singleSignOnUrl = ssoUrl, signingCertificatePem = cert)
    }

    /** descriptor XML 본문을 HTTP GET 으로 가져온다. */
    private fun fetch(descriptorUrl: String): String {
        val request = HttpRequest.newBuilder(URI.create(descriptorUrl)).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in OK_RANGE) {
            "descriptor HTTP ${response.statusCode()} — url=$descriptorUrl"
        }
        return response.body()
    }

    /** XXE 차단 설정으로 XML 을 DOM 으로 파싱하고 root [Element] 를 반환한다. */
    private fun parseSecurely(xml: String): Element {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // XXE 방어 — DOCTYPE/외부 엔티티 비활성화.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
        return document.documentElement
    }

    /** HTTP-Redirect 바인딩의 SingleSignOnService Location 을 반환한다(없으면 null). */
    private fun singleSignOnRedirectLocation(root: Element): String? {
        val nodes = root.getElementsByTagNameNS(NS_METADATA, "SingleSignOnService")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            if (el.getAttribute("Binding") == BINDING_HTTP_REDIRECT) {
                return el.getAttribute("Location").ifBlank { null }
            }
        }
        return null
    }

    /** signing 용 KeyDescriptor 의 X509Certificate 본문을 PEM 으로 래핑해 반환한다(없으면 null). */
    private fun signingCertificatePem(root: Element): String? {
        val keyDescriptors = root.getElementsByTagNameNS(NS_METADATA, "KeyDescriptor")
        for (i in 0 until keyDescriptors.length) {
            val kd = keyDescriptors.item(i) as Element
            // use 속성이 signing 이거나, use 미지정(양용) 인 첫 인증서를 채택.
            val use = kd.getAttribute("use")
            if (use.isNotBlank() && use != "signing") continue
            val certNodes = kd.getElementsByTagNameNS(NS_XMLDSIG, "X509Certificate")
            if (certNodes.length > 0) {
                val base64 = certNodes.item(0).textContent.replace("\\s".toRegex(), "")
                return wrapPem(base64)
            }
        }
        return null
    }

    /** Base64 DER 본문을 표준 PEM(64자 줄바꿈 + BEGIN/END 헤더) 으로 래핑한다. */
    private fun wrapPem(base64: String): String {
        val body = base64.chunked(PEM_LINE_WIDTH).joinToString("\n")
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----"
    }

    /** 성공 HTTP 상태코드 범위(2xx). */
    private val OK_RANGE = 200..299
}
