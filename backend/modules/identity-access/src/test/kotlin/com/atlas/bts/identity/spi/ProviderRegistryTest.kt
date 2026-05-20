// ProviderRegistry — 공급자 등록/조회 동작 검증 테스트 (Spring 컨텍스트 없는 단위 테스트)

package com.atlas.bts.identity.spi

import com.atlas.bts.identity.spi.fake.FakeLocalProvider
import com.atlas.bts.identity.spi.fake.FakePatProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ProviderRegistry 단위 테스트.
 * Spring 전체 컨텍스트(Keycloak OAuth2 자동설정 포함) 없이 Registry와 Fake Provider만 직접 조립.
 */
class ProviderRegistryTest {
    private lateinit var localProvider: FakeLocalProvider
    private lateinit var patProvider: FakePatProvider
    private lateinit var registry: ProviderRegistry

    @BeforeEach
    fun setUp() {
        localProvider = FakeLocalProvider()
        patProvider = FakePatProvider()
        registry = ProviderRegistry(listOf(localProvider, patProvider))
    }

    @Test
    fun `findByType LOCAL returns FakeLocalProvider`() {
        val provider = registry.findByType(ProviderType.LOCAL)
        assertThat(provider).isInstanceOf(FakeLocalProvider::class.java)
    }

    @Test
    fun `findByType PAT returns FakePatProvider`() {
        val provider = registry.findByType(ProviderType.PAT)
        assertThat(provider).isInstanceOf(FakePatProvider::class.java)
    }

    @Test
    fun `findByType LDAP returns null when not registered`() {
        val provider = registry.findByType(ProviderType.LDAP)
        assertThat(provider).isNull()
    }

    @Test
    fun `findFor UsernamePassword returns FakeLocalProvider`() {
        val cred = Credential.UsernamePassword("alice", "correct".toCharArray())
        val provider = registry.findFor(cred)
        assertThat(provider).isInstanceOf(FakeLocalProvider::class.java)
    }

    @Test
    fun `findFor Pat returns FakePatProvider`() {
        val cred = Credential.Pat("valid")
        val provider = registry.findFor(cred)
        assertThat(provider).isInstanceOf(FakePatProvider::class.java)
    }

    @Test
    fun `registry with no providers — findByType returns null`() {
        val emptyRegistry = ProviderRegistry(emptyList())
        assertThat(emptyRegistry.findByType(ProviderType.LOCAL)).isNull()
    }

    @Test
    fun `registry with no providers — findFor returns null`() {
        val emptyRegistry = ProviderRegistry(emptyList())
        assertThat(emptyRegistry.findFor(Credential.Pat("any"))).isNull()
    }

    @Test
    fun `registry with no providers — all() is empty`() {
        val emptyRegistry = ProviderRegistry(emptyList())
        assertThat(emptyRegistry.all()).isEmpty()
    }

    @Test
    fun `all() returns immutable copy — UnsupportedOperationException on mutation`() {
        val all = registry.all()
        assertThat(all.size).isEqualTo(2)
        // toList()는 불변 복사본을 반환하므로 add 시 UnsupportedOperationException 발생
        assertThatThrownBy { (all as MutableList).add(FakeLocalProvider()) }
            .isInstanceOf(UnsupportedOperationException::class.java)
    }
}
