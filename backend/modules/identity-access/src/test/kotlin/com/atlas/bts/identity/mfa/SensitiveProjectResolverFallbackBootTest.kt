// SensitiveProjectResolver fallback prod-profile 부팅 회귀 가드 — identity-access 단독 부팅 가용성 (FR-MF-04 회귀 수정)

package com.atlas.bts.identity.mfa

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.bts.shared.permission.SensitiveProjectResolver
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
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
import java.util.UUID

/**
 * [SensitiveProjectResolver] fallback 의 prod-profile 부팅 회귀 가드 (FR-MF-04 회귀 수정).
 *
 * ## 배경 — prod-profile 부팅 회귀
 * [com.atlas.bts.identity.jwt.JwtIssuer] 가 [MfaEnforcementPolicy] 를 주입받고, 그 정책이
 * [SensitiveProjectResolver] 를 hard-dependency 로 요구한다. fallback 인
 * [NonProdSensitiveProjectResolver] 가 과거 `@Profile("!prod")` 였던 탓에, identity-access 단독
 * `@ActiveProfiles("prod")` 통합테스트에서는 fallback 이 비활성 → 실 adapter(issue-tracking
 * 소속)도 부재 → `SensitiveProjectResolver` 빈 미해소 → ApplicationContext 로드 실패였다.
 *
 * ## 결정 — fallback 을 `@ConditionalOnMissingBean` 으로 (프로파일 무관)
 * fallback 을 `@ConditionalOnMissingBean(SensitiveProjectResolver::class)` 로 바꿔, 실 adapter 가
 * 부재한 단독 부팅(프로파일 무관)에서 등록되게 한다. assembled 부팅에서는 실
 * [com.bts.issue.project.repository.IssueTrackingSensitiveProjectResolver] 가 존재하므로 fallback 은
 * 등록되지 않는다(assembled 우선). 배포 모델(no-cross-bc-deployment-assembly)상 standalone-prod
 * 시나리오가 없어 loud-fail 로 막을 대상이 없으므로 부팅 가용성을 택한다.
 *
 * ## 이 테스트가 가드하는 것
 * `@ActiveProfiles("prod")` 로 부팅해 (a) 컨텍스트 로드 성공, (b) [SensitiveProjectResolver] 빈이
 * fallback 으로 주입됨, (c) [SensitiveProjectResolver.anyRequiresMfa] 가 임의 집합에 `false` 를 반환
 * (안전 기본 = '민감 아님')을 단언한다. 향후 fallback 이 다시 프로파일 한정으로 회귀해 prod 부팅이
 * 깨지면 이 테스트가 RED 로 차단한다.
 *
 * ## 부팅 셋업 — SystemAdminInfraIntegrationTest 선례 동일
 * Testcontainers PostgreSQL + prod PEM 파일 + LDAP MockBean + @DynamicPropertySource 셋업은
 * [com.atlas.bts.identity.systemrole.SystemAdminInfraIntegrationTest] 와 동일하다.
 *
 * @see NonProdSensitiveProjectResolver
 * @see SensitiveProjectResolver
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@ActiveProfiles("prod")
@Testcontainers
class SensitiveProjectResolverFallbackBootTest {
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
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            // LDAP 자동설정이 URL 을 요구하므로 placeholder 제공
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
            // prod 프로파일에서 PemFileKeyProvider 가 PEM 파일 경로를 @Value 로 요구한다.
            registry.add("bts.auth.jwt.private-key-pem-path") { pemFilePath }
        }

        /**
         * 테스트용 임시 RSA 2048 PEM 파일 경로.
         *
         * PemFileKeyProvider 가 BouncyCastle PEMParser 를 사용하므로 BC provider 를 먼저 등록한다.
         * PKCS#8(PRIVATE KEY) 형식으로 PEM 파일을 생성한다.
         */
        val pemFilePath: String =
            run {
                if (Security.getProvider("BC") == null) {
                    Security.addProvider(BouncyCastleProvider())
                }
                val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val privKey = keyPair.private
                val pemContent =
                    buildString {
                        appendLine("-----BEGIN PRIVATE KEY-----")
                        val mimeEncoder = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
                        appendLine(mimeEncoder.encodeToString(privKey.encoded))
                        append("-----END PRIVATE KEY-----")
                    }
                val tmpFile = Files.createTempFile("bts-test-key-", ".pem")
                Files.writeString(tmpFile, pemContent)
                tmpFile.toAbsolutePath().toString()
            }
    }

    // LDAP Bean 목킹 — 실제 LDAP 서버 없이 컨텍스트 부팅 (선례 동일)
    @MockBean lateinit var ldapProvider: LdapProvider

    @MockBean lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean lateinit var autoProvisionService: AutoProvisionService

    @MockBean lateinit var ldapTemplate: LdapTemplate

    /** 단독 prod 부팅에서 fallback 이 [SensitiveProjectResolver] 빈으로 주입되어야 한다. */
    @Autowired
    private lateinit var sensitiveProjectResolver: SensitiveProjectResolver

    /**
     * prod 프로파일 단독 부팅에서 [SensitiveProjectResolver] 빈이 fallback
     * ([NonProdSensitiveProjectResolver]) 으로 주입된다. 실 adapter(issue-tracking)가 부재하므로
     * `@ConditionalOnMissingBean` fallback 이 등록된다 — 이 주입이 성공하는 것 자체가 컨텍스트
     * 로드 성공(회귀 가드)이다.
     */
    @Test
    fun `prod 단독 부팅에서 SensitiveProjectResolver가 NonProd fallback으로 주입된다`() {
        assertThat(sensitiveProjectResolver).isInstanceOf(NonProdSensitiveProjectResolver::class.java)
    }

    /**
     * fallback 의 [SensitiveProjectResolver.anyRequiresMfa] 는 임의 프로젝트 집합에 대해 안전 기본인
     * `false`(민감 아님)를 반환한다 — 단독 부팅에서 '민감 프로젝트 멤버' 경로로 인한 MFA 강제가
     * 발생하지 않음을 확정한다.
     */
    @Test
    fun `fallback anyRequiresMfa는 임의 집합에 false를 반환한다`() {
        val arbitraryProjectIds = setOf(UUID.randomUUID(), UUID.randomUUID())
        assertThat(sensitiveProjectResolver.anyRequiresMfa(arbitraryProjectIds)).isFalse()
    }
}
