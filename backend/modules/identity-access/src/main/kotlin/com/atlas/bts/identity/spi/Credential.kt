// 인증 시도 입력 VO — sealed interface UsernamePassword/Pat/LdapBind (메모리 전용, DB 저장 금지)

package com.atlas.bts.identity.spi

/**
 * 인증 시도 시 공급자에게 전달되는 자격증명 VO.
 * 메모리에만 존재하며 DB에 저장되지 않는다.
 *
 * 구현체:
 * - [UsernamePassword]: 로컬 인증용. password는 CharArray로 메모리 즉시 초기화 가능.
 * - [Pat]: Personal Access Token 인증용.
 * - [LdapBind]: LDAP/AD 인증용. password는 CharArray로 메모리 즉시 초기화 가능.
 */
sealed interface Credential {
    /**
     * 사용자 이름 + 패스워드 자격증명.
     *
     * Contract: 인증 완료 후 반드시 [password] 배열을 초기화해야 한다.
     * 예: `de.mkammerer.argon2.Argon2Helper.wipeArray(password)` 또는 `password.fill(' ')`.
     * 이 책임은 [com.atlas.bts.identity.spi.AuthenticationProvider] 구현체에 있다.
     *
     * equals/hashCode는 CharArray 내용 기반으로 override됨 (기본 참조 동등 대신).
     */
    data class UsernamePassword(
        val username: String,
        val password: CharArray,
    ) : Credential {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UsernamePassword) return false
            return username == other.username && password.contentEquals(other.password)
        }

        override fun hashCode(): Int {
            var result = username.hashCode()
            result = 31 * result + password.contentHashCode()
            return result
        }
    }

    /**
     * Personal Access Token 자격증명.
     * token은 raw 값이며, 저장 시 sha256 해시로 변환된다 (DATA.md §8).
     */
    data class Pat(val token: String) : Credential

    /**
     * LDAP/AD bind 자격증명 (FR-AU-02).
     *
     * Contract: 인증 완료 후 반드시 [password] 배열을 즉시 초기화해야 한다.
     * 예: `password.fill(' ')`.
     * 이 책임은 [com.atlas.bts.identity.provider.ldap.LdapProvider] 구현체에 있다.
     * 특히 try-finally 블록으로 예외 발생 시에도 반드시 wipe 되어야 한다.
     *
     * equals/hashCode는 CharArray 내용 기반으로 override됨 (기본 참조 동등 대신).
     */
    data class LdapBind(
        val username: String,
        val password: CharArray,
    ) : Credential {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LdapBind) return false
            return username == other.username && password.contentEquals(other.password)
        }

        override fun hashCode(): Int {
            var result = username.hashCode()
            result = 31 * result + password.contentHashCode()
            return result
        }
    }
}
