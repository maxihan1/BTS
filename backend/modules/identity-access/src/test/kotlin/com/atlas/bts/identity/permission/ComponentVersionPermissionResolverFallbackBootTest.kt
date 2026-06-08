// non-prod identity-access 컨텍스트의 Component/Version 권한 리졸버 fallback 빈 부팅 가드 (FR-PM-03 task-1).

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.bts.shared.permission.ComponentPermissionResolver
import com.bts.shared.permission.CustomFieldPermissionResolver
import com.bts.shared.permission.VersionPermissionResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

// OAuth2ClientAutoConfiguration: 테스트 환경에서 keycloak issuer-uri OIDC discovery 원격 호출 차단.
private const val EXCLUDE_OAUTH2_CLIENT =
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure" +
        ".security.oauth2.client.servlet.OAuth2ClientAutoConfiguration"

/**
 * non-prod identity-access 컨텍스트 [ComponentPermissionResolver]/[VersionPermissionResolver]
 * 부팅 회귀 가드 (FR-PM-03 task-1).
 *
 * ## 회귀 배경
 * `MyProjectPermissionController` (Task 2 확장)가 shared-kernel 포트
 * [ComponentPermissionResolver]/[VersionPermissionResolver] 를 주입받게 되면,
 * non-prod identity-access 컨텍스트에 그 포트를 채우는 Bean 이 하나도 없어
 * (운영용 prod adapter 는 `@Profile("prod")`, issue-tracking 의
 * `AlwaysAllowComponentPermissionResolver`/`AlwaysAllowVersionPermissionResolver` 는
 * 다른 BC 라 component-scan 안 됨) 통합테스트가 `UnsatisfiedDependency` /
 * `No qualifying bean` 으로 부팅 실패한다(메모리 profile-scoped-bean-boot-failure, PR #55).
 *
 * ## 가드 내용
 * non-prod(기본 profile, `prod` 미지정) 컨텍스트에 각 포트 Bean 이
 * 정확히 1개 존재하며 그 구현이 [DevAllowComponentPermissionResolver] /
 * [DevAllowVersionPermissionResolver] 임을 단언한다(C4 — 단순 @Autowired 는
 * 빈 2개 중복도 통과해 가드가 약하다). fallback 이 사라지면 컨텍스트 부팅 자체가
 * 실패하므로 이 테스트가 RED 가 된다.
 */
@SpringBootTest(properties = [EXCLUDE_OAUTH2_CLIENT])
@Testcontainers
class ComponentVersionPermissionResolverFallbackBootTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
        }
    }

    @MockBean
    lateinit var ldapProvider: LdapProvider

    @MockBean
    lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean
    lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean
    lateinit var autoProvisionService: AutoProvisionService

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun `non-prod 컨텍스트에 ComponentPermissionResolver fallback 빈이 정확히 1개 존재한다`() {
        val beans = context.getBeansOfType(ComponentPermissionResolver::class.java)

        assertThat(beans).hasSize(1)
        assertThat(beans.values.single()).isInstanceOf(DevAllowComponentPermissionResolver::class.java)
    }

    @Test
    fun `non-prod 컨텍스트에 VersionPermissionResolver fallback 빈이 정확히 1개 존재한다`() {
        val beans = context.getBeansOfType(VersionPermissionResolver::class.java)

        assertThat(beans).hasSize(1)
        assertThat(beans.values.single()).isInstanceOf(DevAllowVersionPermissionResolver::class.java)
    }

    // FR-IS-10 #98 이 MyProjectPermissionController 에 CustomFieldPermissionResolver 의존을 추가하면서
    // non-prod fallback 을 누락해 모든 통합테스트가 부팅 실패했다(PRE_EXISTING). 동일 회귀 가드를 추가한다.
    @Test
    fun `non-prod 컨텍스트에 CustomFieldPermissionResolver fallback 빈이 정확히 1개 존재한다`() {
        val beans = context.getBeansOfType(CustomFieldPermissionResolver::class.java)

        assertThat(beans).hasSize(1)
        assertThat(beans.values.single()).isInstanceOf(DevAllowCustomFieldPermissionResolver::class.java)
    }
}
