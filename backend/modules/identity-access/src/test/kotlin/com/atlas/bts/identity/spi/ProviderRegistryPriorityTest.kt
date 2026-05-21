// ProviderRegistry priority 정렬 동작 검증 테스트 — FR-AU-09 Task 35 (EC-24, EC-25)

package com.atlas.bts.identity.spi

import com.atlas.bts.identity.spi.fake.FakeLdapProvider
import com.atlas.bts.identity.spi.fake.FakeLocalProvider
import com.atlas.bts.identity.spi.fake.FakePatProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ProviderRegistry.findFor(credential) — priority 내림차순 정렬 후 supports 첫 번째 반환.
 *
 * 검증 시나리오:
 * (a) LdapBind → LDAP(80) 선택.
 * (b) UsernamePassword → LOCAL(70) 선택. LDAP 도 UsernamePassword 는 지원 안 함.
 * (c) Pat → PAT(60) 선택.
 * (d) LDAP unavailable 시 UsernamePassword → LOCAL 폴백 (EC-22/23).
 *     단, LDAP 은 UsernamePassword 를 supports 하지 않으므로 LDAP→LOCAL 폴백은
 *     credential 타입 분리로 자연 처리됨. 동일 credential 타입에 다른 priority 두 Provider
 *     등록 시 높은 priority 가 먼저 선택되는 것을 검증.
 * (e) 동률 시 type.ordinal 오름차순 fallback (EC-25).
 */
class ProviderRegistryPriorityTest {

    // -----------------------------------------------------------------------
    // (a) LdapBind credential → LDAP provider (priority 80) 선택
    // -----------------------------------------------------------------------

    @Test
    fun `findFor LdapBind selects LdapProvider (priority 80) over others`() {
        val registry = ProviderRegistry(listOf(FakeLocalProvider(), FakePatProvider(), FakeLdapProvider()))
        val cred = Credential.LdapBind("alice", "secret".toCharArray())

        val result = registry.findFor(cred)

        assertThat(result).isInstanceOf(FakeLdapProvider::class.java)
    }

    // -----------------------------------------------------------------------
    // (b) UsernamePassword credential → LOCAL provider (priority 70) 선택
    //     LDAP 은 LdapBind 만 supports 하므로 건너뜀
    // -----------------------------------------------------------------------

    @Test
    fun `findFor UsernamePassword selects LocalProvider (priority 70)`() {
        val registry = ProviderRegistry(listOf(FakeLocalProvider(), FakePatProvider(), FakeLdapProvider()))
        val cred = Credential.UsernamePassword("alice", "correct".toCharArray())

        val result = registry.findFor(cred)

        assertThat(result).isInstanceOf(FakeLocalProvider::class.java)
    }

    // -----------------------------------------------------------------------
    // (c) Pat credential → PAT provider (priority 60) 선택
    // -----------------------------------------------------------------------

    @Test
    fun `findFor Pat selects PatProvider (priority 60)`() {
        val registry = ProviderRegistry(listOf(FakeLocalProvider(), FakePatProvider(), FakeLdapProvider()))
        val cred = Credential.Pat("valid")

        val result = registry.findFor(cred)

        assertThat(result).isInstanceOf(FakePatProvider::class.java)
    }

    // -----------------------------------------------------------------------
    // (d) 동일 Credential 타입을 지원하는 두 Provider 등록 시 높은 priority 우선
    //     — 커스텀 priority override 시나리오 (EC-24)
    // -----------------------------------------------------------------------

    @Test
    fun `findFor prefers provider with higher priority when both support same credential`() {
        // 두 Provider 모두 UsernamePassword 를 supports 하며, priority 를 달리 override
        val lowPriority = object : AuthenticationProvider {
            override val type: ProviderType = ProviderType.LOCAL
            override val priority: Int = 10
            override fun supports(credential: Credential): Boolean = credential is Credential.UsernamePassword
            override fun authenticate(credential: Credential): AuthnResult =
                AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
        }
        val highPriority = object : AuthenticationProvider {
            override val type: ProviderType = ProviderType.LDAP
            override val priority: Int = 90
            override fun supports(credential: Credential): Boolean = credential is Credential.UsernamePassword
            override fun authenticate(credential: Credential): AuthnResult =
                AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
        }
        // 낮은 priority 가 리스트 앞에 있어도 높은 priority 가 선택돼야 함
        val registry = ProviderRegistry(listOf(lowPriority, highPriority))
        val cred = Credential.UsernamePassword("bob", "pw".toCharArray())

        val result = registry.findFor(cred)

        assertThat(result).isSameAs(highPriority)
    }

    // -----------------------------------------------------------------------
    // (e) 동률 시 type.ordinal 오름차순 fallback (EC-25)
    //     LOCAL.ordinal=0, LDAP.ordinal=1 이므로 LOCAL 이 먼저 (더 작은 ordinal)
    // -----------------------------------------------------------------------

    @Test
    fun `findFor tie-breaks by type ordinal ascending when priority is equal`() {
        val priority = 99
        // LOCAL.ordinal < LDAP.ordinal 이므로 LOCAL 이 동률 시 우선
        val localProvider = object : AuthenticationProvider {
            override val type: ProviderType = ProviderType.LOCAL
            override val priority: Int = priority
            override fun supports(credential: Credential): Boolean = credential is Credential.UsernamePassword
            override fun authenticate(credential: Credential): AuthnResult =
                AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
        }
        val ldapProvider = object : AuthenticationProvider {
            override val type: ProviderType = ProviderType.LDAP
            override val priority: Int = priority
            override fun supports(credential: Credential): Boolean = credential is Credential.UsernamePassword
            override fun authenticate(credential: Credential): AuthnResult =
                AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
        }
        // LDAP 을 리스트 앞에 두어 순서 의존 없음을 검증
        val registry = ProviderRegistry(listOf(ldapProvider, localProvider))
        val cred = Credential.UsernamePassword("carol", "pw".toCharArray())

        val result = registry.findFor(cred)

        assertThat(result).isSameAs(localProvider)
    }

    // -----------------------------------------------------------------------
    // 회귀 가드 — provider 등록 순서가 달라도 결과 동일 (순서 독립성)
    // -----------------------------------------------------------------------

    @Test
    fun `findFor result is stable regardless of provider registration order`() {
        val cred = Credential.LdapBind("dave", "pw".toCharArray())

        // 등록 순서: PAT → LOCAL → LDAP
        val registry1 = ProviderRegistry(listOf(FakePatProvider(), FakeLocalProvider(), FakeLdapProvider()))
        // 등록 순서: LDAP → PAT → LOCAL
        val registry2 = ProviderRegistry(listOf(FakeLdapProvider(), FakePatProvider(), FakeLocalProvider()))

        assertThat(registry1.findFor(cred)).isInstanceOf(FakeLdapProvider::class.java)
        assertThat(registry2.findFor(cred)).isInstanceOf(FakeLdapProvider::class.java)
    }
}
