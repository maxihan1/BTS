// Credential.LdapBind sealed 변종 — equals/hashCode/wipe contract 검증 테스트

package com.atlas.bts.identity.spi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Credential.LdapBind sealed 변종 단위 테스트.
 * PR #3 ADR 2026-05-20-authentication-provider-spi-naming.md line 51~57 약속 이행 확인.
 */
class CredentialLdapBindTest {
    @Test
    fun `LdapBind equals same content CharArray`() {
        val a = Credential.LdapBind("alice", "Test1234!".toCharArray())
        val b = Credential.LdapBind("alice", "Test1234!".toCharArray())
        assertThat(a == b).isTrue()
    }

    @Test
    fun `LdapBind equals false when username differs`() {
        val a = Credential.LdapBind("alice", "Test1234!".toCharArray())
        val b = Credential.LdapBind("bob", "Test1234!".toCharArray())
        assertThat(a == b).isFalse()
    }

    @Test
    fun `LdapBind equals false when password content differs`() {
        val a = Credential.LdapBind("alice", "Test1234!".toCharArray())
        val b = Credential.LdapBind("alice", "wrong".toCharArray())
        assertThat(a == b).isFalse()
    }

    @Test
    fun `LdapBind hashCode same content CharArray`() {
        val a = Credential.LdapBind("alice", "Test1234!".toCharArray())
        val b = Credential.LdapBind("alice", "Test1234!".toCharArray())
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `LdapBind is a sealed Credential variant`() {
        // when 분기 망라성 검증 — 컴파일 시점에 sealed 변종이 포함됨을 런타임에도 확인
        val cred: Credential = Credential.LdapBind("alice", "Test1234!".toCharArray())
        val result =
            when (cred) {
                is Credential.UsernamePassword -> "username-password"
                is Credential.Pat -> "pat"
                is Credential.LdapBind -> "ldap-bind"
                is Credential.SamlAssertion -> "saml-assertion"
            }
        assertThat(result).isEqualTo("ldap-bind")
    }

    @Test
    fun `LdapBind password CharArray separate from username String`() {
        val password = "Test1234!".toCharArray()
        val cred = Credential.LdapBind("alice", password)
        assertThat(cred.username).isEqualTo("alice")
        // password 참조 동일성 — 같은 배열 인스턴스
        assertThat(cred.password).isSameAs(password)
    }

    @Test
    fun `LdapBind password fill wipe 후 equals false`() {
        val a = Credential.LdapBind("alice", "Test1234!".toCharArray())
        val b = Credential.LdapBind("alice", "Test1234!".toCharArray())
        a.password.fill(' ')
        // wipe 후에는 내용이 달라졌으므로 동등하지 않아야 함
        assertThat(a == b).isFalse()
    }
}
