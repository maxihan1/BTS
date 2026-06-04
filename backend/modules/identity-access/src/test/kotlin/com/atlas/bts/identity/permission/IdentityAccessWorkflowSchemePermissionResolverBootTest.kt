// prod/비-prod 프로파일에서 IdentityAccessWorkflowSchemePermissionResolver 빈 등록/배타성을 검증하는 부팅 가드 (FR-PM-04 task-5).

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.ApplicationContext
import org.springframework.ldap.core.LdapTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Security

// OAuth2ClientAutoConfiguration: 테스트 환경에서 keycloak issuer-uri OIDC discovery 원격 호출 차단.
private const val EXCLUDE_OAUTH2_CLIENT =
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure" +
        ".security.oauth2.client.servlet.OAuth2ClientAutoConfiguration"

/**
 * 테스트용 임시 RSA 2048 PEM 파일 경로.
 *
 * prod 프로파일에서 `PemFileKeyProvider` 가 PEM 파일 경로를 `@Value` 로 요구한다(통합테스트 선례 동형).
 */
private val pemFilePath: String =
    run {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pemContent =
            buildString {
                appendLine("-----BEGIN PRIVATE KEY-----")
                val mimeEncoder = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
                appendLine(mimeEncoder.encodeToString(keyPair.private.encoded))
                append("-----END PRIVATE KEY-----")
            }
        val tmpFile = Files.createTempFile("bts-test-boot-key-", ".pem")
        Files.writeString(tmpFile, pemContent)
        tmpFile.toAbsolutePath().toString()
    }

/**
 * 공통 컨테이너/프로퍼티 구성을 [DynamicPropertyRegistry] 에 등록한다.
 *
 * prod·비-prod 두 부팅 컨텍스트가 동일한 PostgreSQL/PEM/LDAP 더미 설정을 공유한다.
 */
private fun configureBootProperties(
    registry: DynamicPropertyRegistry,
    postgres: PostgreSQLContainer<*>,
) {
    registry.add("spring.datasource.url") { postgres.jdbcUrl }
    registry.add("spring.datasource.username") { postgres.username }
    registry.add("spring.datasource.password") { postgres.password }
    registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
    registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
    registry.add("spring.ldap.urls") { "ldap://localhost:389" }
    registry.add("spring.ldap.base") { "dc=bts,dc=local" }
    registry.add("bts.auth.jwt.private-key-pem-path") { pemFilePath }
}

/**
 * prod 프로파일 [IdentityAccessWorkflowSchemePermissionResolver] 빈 등록 부팅 가드 (FR-PM-04 task-5).
 *
 * ## 무엇을 검증하나
 * [WorkflowSchemePermissionResolver] 포트는 **project-workflow 가 소비(주입)** 하고,
 * identity-access 는 그 포트의 **prod 판정 adapter([IdentityAccessWorkflowSchemePermissionResolver],
 * `@Profile("prod")`) 만 제공** 한다. identity-access 컨텍스트는 이 포트를 어디서도 주입하지 않으므로
 * "AlwaysAllow 미등록" 류 단언은 이 컨텍스트에서 vacuous(항상참)다(plan W2 재정의).
 *
 * 따라서 의미 있는 검증은 **프로파일 분기에 따른 adapter 빈 등록 배타성**이며, 본 클래스는 그중
 * prod 프로파일에서 adapter 빈이 **정확히 1개** 등록됨을 확인한다.
 * (비-prod 0개 단언은 [WorkflowSchemePermissionResolverNonProdBootTest] 참조.)
 *
 * 거부(403) end-to-end 진실 판정은 Task3 의 prod-프로파일 Testcontainers 통합테스트(S1~S6)가
 * ground-truth 이며, 이 부팅 테스트는 빈 등록/배타성이라는 부팅 관심사만 가볍게 가드한다.
 */
@SpringBootTest(properties = [EXCLUDE_OAUTH2_CLIENT])
@ActiveProfiles("prod")
@Testcontainers
class IdentityAccessWorkflowSchemePermissionResolverBootTest {
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
        fun configureProperties(registry: DynamicPropertyRegistry) = configureBootProperties(registry, postgres)
    }

    @MockBean lateinit var ldapProvider: LdapProvider

    @MockBean lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean lateinit var autoProvisionService: AutoProvisionService

    @MockBean lateinit var ldapTemplate: LdapTemplate

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun `prod 컨텍스트에 WorkflowScheme prod adapter 빈이 정확히 1개 등록된다`() {
        val beans = context.getBeansOfType(IdentityAccessWorkflowSchemePermissionResolver::class.java)

        assertThat(beans).hasSize(1)
    }
}

/**
 * 비-prod(기본) 프로파일 [IdentityAccessWorkflowSchemePermissionResolver] 미등록 부팅 가드 (FR-PM-04 task-5).
 *
 * `@Profile("prod")` 배타성의 실제 분기를 검증한다 — 비-prod 컨텍스트에는 prod adapter 빈이
 * **0개** 여야 한다. `@Profile("prod")` 가 풀리거나 무조건 등록으로 바뀌면 이 단언이 RED 가 되므로
 * vacuous(항상참)가 아니다. 자세한 맥락은 [IdentityAccessWorkflowSchemePermissionResolverBootTest] KDoc 참조.
 */
@SpringBootTest(properties = [EXCLUDE_OAUTH2_CLIENT])
@Testcontainers
class WorkflowSchemePermissionResolverNonProdBootTest {
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
        fun configureProperties(registry: DynamicPropertyRegistry) = configureBootProperties(registry, postgres)
    }

    @MockBean lateinit var ldapProvider: LdapProvider

    @MockBean lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean lateinit var autoProvisionService: AutoProvisionService

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun `비-prod 컨텍스트에 WorkflowScheme prod adapter 빈이 등록되지 않는다`() {
        val beans = context.getBeansOfType(IdentityAccessWorkflowSchemePermissionResolver::class.java)

        // RED 의도: 실제로는 0개이나, 테스트가 빈 카운트를 실제로 본다는 것을 증명하기 위해 1개를 단언해 실패시킨다.
        assertThat(beans).hasSize(1)
    }
}
