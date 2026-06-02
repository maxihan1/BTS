// non-prod identity-access 컨텍스트에 IssuePermissionResolver fallback 빈이 존재하는지 검증하는 부팅 회귀 가드 (FR-PM-02 task-1).

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.permission.DevAllowIssuePermissionResolver
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.bts.shared.permission.IssuePermissionResolver
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
 * non-prod identity-access 컨텍스트 [IssuePermissionResolver] 부팅 회귀 가드 (FR-PM-02 task-1).
 *
 * ## 회귀 배경
 * `MyIssuePermissionController` 가 shared-kernel 포트 [IssuePermissionResolver] 를 주입받는데,
 * non-prod identity-access 컨텍스트에는 그 포트를 채우는 Bean 이 하나도 없어
 * (운영용 [com.atlas.bts.identity.permission.IdentityAccessIssuePermissionResolver] 는 `@Profile("prod")`,
 * issue-tracking 의 `AlwaysAllowIssuePermissionResolver` 는 다른 BC 라 component-scan 안 됨)
 * 66개 @SpringBootTest 통합테스트가 `UnsatisfiedDependency` / `No qualifying bean` 으로 부팅 실패했다.
 *
 * ## 가드 내용
 * non-prod(기본 profile, `prod` 미지정) 컨텍스트에 [IssuePermissionResolver] Bean 이
 * 정확히 1개 존재하며 그 구현이 [DevAllowIssuePermissionResolver] 임을 단언한다.
 * fallback 이 사라지면 컨텍스트 부팅 자체가 실패하므로 이 테스트가 RED 가 된다.
 */
@SpringBootTest(properties = [EXCLUDE_OAUTH2_CLIENT])
@Testcontainers
class IssuePermissionResolverBootTest {
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
    fun `non-prod 컨텍스트에 IssuePermissionResolver fallback 빈이 정확히 1개 존재한다`() {
        val beans = context.getBeansOfType(IssuePermissionResolver::class.java)

        assertThat(beans).hasSize(1)
        assertThat(beans.values.single()).isInstanceOf(DevAllowIssuePermissionResolver::class.java)
    }
}
