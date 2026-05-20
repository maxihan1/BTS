// 인증 완료 후 주체 식별 정보를 담는 VO — PII(externalSubject) 마스킹 toString 포함

package com.atlas.bts.identity.spi

import java.util.UUID

/**
 * 인증 성공 후의 주체(Principal) 식별 정보 VO.
 *
 * - [externalSubject]: 외부 IdP(Keycloak/LDAP 등)의 고유 식별자. PII이므로 toString에서 마스킹.
 * - [displayName]: 사용자 표시 이름. 로그에 노출 허용.
 * - toString: userId 끝 8자 + displayName + providerType + externalSubject=<masked> (DEVELOPMENT.md §1.2)
 */
data class Principal(
    val userId: UUID,
    val providerType: ProviderType,
    val displayName: String,
    val externalSubject: String?,
) {
    @Suppress("MagicNumber")
    override fun toString(): String {
        val shortId = userId.toString().takeLast(SHORT_ID_LENGTH)
        return "Principal(userId=...$shortId, displayName=$displayName," +
            " providerType=$providerType, externalSubject=<masked>)"
    }

    companion object {
        private const val SHORT_ID_LENGTH = 8
    }
}
