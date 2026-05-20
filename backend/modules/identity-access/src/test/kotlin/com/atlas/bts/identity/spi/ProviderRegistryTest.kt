// ProviderRegistry — 공급자 등록/조회 동작 검증 테스트

package com.atlas.bts.identity.spi

import com.atlas.bts.identity.spi.fake.FakeLocalProvider
import com.atlas.bts.identity.spi.fake.FakePatProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test-spi")
class ProviderRegistryTest {
    @Autowired
    private lateinit var registry: ProviderRegistry

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
    fun `registry with no providers returns null`() {
        val emptyRegistry = ProviderRegistry(emptyList())
        assertThat(emptyRegistry.findByType(ProviderType.LOCAL)).isNull()
        assertThat(emptyRegistry.findFor(Credential.Pat("any"))).isNull()
        assertThat(emptyRegistry.all()).isEmpty()
    }

    @Test
    fun `all() returns immutable copy — size check`() {
        val all = registry.all()
        assertThat(all.size).isEqualTo(2)
        // 불변 리스트 검증: UnsupportedOperationException 발생
        assertThatThrownBy { (all as MutableList).add(FakeLocalProvider()) }
            .isInstanceOf(UnsupportedOperationException::class.java)
    }
}
