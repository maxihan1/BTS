// LDAP 인증 성공 후 Auto-provisioning 에 필요한 속성 묶음 VO

package com.atlas.bts.identity.provider.ldap

/**
 * LDAP 인증 성공 시 AutoProvisionService 로 전달하는 사용자 속성 VO (Task 15).
 *
 * LdapProvider 가 bind 결과와 LDAP 속성을 파싱하여 채운다.
 * AutoProvisionService 는 이 VO 만 의존하므로 LdapTemplate 에 직접 의존하지 않는다.
 *
 * @param username BTS 내부 users.username (보통 "uid@baseDomain" 형식)
 * @param email LDAP mail 속성값 — 없으면 null
 * @param displayName LDAP cn 속성값 — 없으면 username 으로 fallback
 * @param externalSubject LDAP DN 형식 고유 식별자 (user_external_accounts.external_subject)
 * @param groups LDAP 그룹 DN 목록 (현재는 빈 목록 — FR-PM-01 에서 그룹 매핑)
 */
data class LdapProvisionAttrs(
    val username: String,
    val email: String?,
    val displayName: String,
    val externalSubject: String,
    val groups: List<String>,
)
