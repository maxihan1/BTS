// users 테이블 행 매핑 엔티티 — 모든 인증 공급자 공통 BTS 사용자 (SDD 19.2)

package com.atlas.bts.identity.user

import java.time.Instant
import java.util.UUID

/**
 * BTS 내부 사용자 계정 엔티티 (FR-AU-09 Task 32 / SDD §19.2).
 *
 * Local / LDAP / SAML / OIDC 등 모든 인증 공급자에 공통으로 대응하는 단일 엔티티다.
 * 공급자별 식별자는 [com.atlas.bts.identity.provider.ldap.ExternalAccount] 에 저장한다.
 *
 * ## 필드
 * - [id]: DB gen_random_uuid() 발급 PK.
 * - [username]: 로그인 식별자. UNIQUE. LDAP uid / OIDC sub / 이메일 등.
 * - [email]: 외부 IdP 에서 미제공 시 null 허용.
 * - [displayName]: 화면 표시 이름. LDAP cn 등.
 * - [createdAt]: 계정 최초 생성 시각 — 변경 불가.
 * - [updatedAt]: 마지막 갱신 시각 (email / displayName 변경, 마지막 로그인 갱신 시 NOW()).
 *
 * ## toString 보안
 * PII인 [email] 은 toString 에서 마스킹된다.
 *
 * @see com.atlas.bts.identity.provider.ldap.ExternalAccount 외부 IdP 식별자 매핑 엔티티
 */
data class User(
    val id: UUID,
    val username: String,
    /** 이메일 — 외부 IdP 미제공 시 null */
    val email: String?,
    val displayName: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String =
        "User(id=$id, username=$username, email=<masked>, displayName=$displayName," +
            " createdAt=$createdAt, updatedAt=$updatedAt)"
}
