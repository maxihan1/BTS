// 연결 가능 provider 통합 목록 응답 DTO — LDAP/SAML/OIDC 한 배열로 노출 (FR-AU-08 D6 Task 2)

package com.atlas.bts.identity.web.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

/**
 * GET /api/v1/auth/account/linkable-providers 응답 — 연결 가능 provider 통합 목록 (FR-AU-08).
 *
 * LDAP/SAML/OIDC 활성 provider 를 한 [linkable] 배열에 kind 별로 담는다. 비면 빈 배열을 반환한다.
 *
 * @param linkable 연결 가능 provider 항목들(LDAP→SAML→OIDC 순).
 */
data class LinkableProvidersResponse(
    val linkable: List<LinkableProviderDto>,
)

/**
 * 연결 가능 provider 한 항목 (FR-AU-08).
 *
 * kind 에 따라 식별 키가 갈린다. LDAP 은 [providerId](authn_providers.id)로, SAML/OIDC 는
 * [registrationId](등록 식별자)로 식별한다. 해당 kind 에 무관한 키는 null 이며 [JsonInclude]
 * (NON_NULL)로 직렬화에서 **키 자체가 제거**된다 — LDAP 응답엔 registrationId 키가, SSO 응답엔
 * providerId 키가 부재하여 프론트 Zod discriminatedUnion(kind 기준) 과 호환된다.
 *
 * @param kind provider 유형 문자열 — "LDAP" | "SAML" | "OIDC".
 * @param providerId LDAP 의 authn_providers.id. SAML/OIDC 는 null(직렬화 제외).
 * @param registrationId SAML/OIDC 의 등록 식별자. LDAP 은 null(직렬화 제외).
 * @param displayName UI 표시용 이름.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class LinkableProviderDto(
    val kind: String,
    val providerId: UUID?,
    val registrationId: String?,
    val displayName: String,
)
