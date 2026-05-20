// Keycloak Testcontainers 통합 테스트 베이스 — 컨테이너 정의 + DynamicPropertySource + 토큰 헬퍼

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.ldap.core.LdapTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.nio.file.Paths
import java.time.Duration

/**
 * Keycloak Testcontainers 공통 기반.
 * Container 정의, @DynamicPropertySource, obtainTokenFromKeycloak 헬퍼를 제공한다.
 * 상속 클래스는 @SpringBootTest + @Testcontainers를 추가해야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
abstract class KeycloakIntegrationBase {
    companion object {
        // MountableFile.forHostPath() — Testcontainers가 Docker 데몬에 절대 경로를 안정적으로 전달.
        // user.dir은 Gradle 실행 위치에 따라 다르므로 여러 경로를 후보로 탐색.
        private val realmJsonPath: String =
            run {
                val gradleDir = System.getProperty("user.dir")
                val candidates =
                    listOf(
                        Paths.get(gradleDir, "infra/keycloak/realm-bts.json"),
                        Paths.get(gradleDir, "../infra/keycloak/realm-bts.json"),
                        Paths.get(gradleDir, "../../infra/keycloak/realm-bts.json"),
                        Paths.get(gradleDir, "../../../infra/keycloak/realm-bts.json"),
                    )
                candidates.firstOrNull { it.toFile().exists() }?.toAbsolutePath()?.toString()
                    ?: error("realm-bts.json 파일을 찾을 수 없음. candidates: $candidates")
            }

        @Container
        @JvmStatic
        val keycloak: GenericContainer<*> =
            GenericContainer("quay.io/keycloak/keycloak:25.0")
                .withCommand("start-dev", "--import-realm")
                .withCopyFileToContainer(
                    MountableFile.forHostPath(realmJsonPath),
                    "/opt/keycloak/data/import/realm-bts.json",
                )
                .withExposedPorts(8080)
                .withEnv("KEYCLOAK_ADMIN", "admin")
                .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
                .waitingFor(
                    Wait.forHttp("/realms/bts/.well-known/openid-configuration")
                        .forPort(8080)
                        .forStatusCode(200),
                )
                .withStartupTimeout(Duration.ofMinutes(2))

        @DynamicPropertySource
        @JvmStatic
        fun keycloakProps(r: DynamicPropertyRegistry) {
            r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") {
                "http://${keycloak.host}:${keycloak.firstMappedPort}/realms/bts"
            }
            r.add("spring.security.oauth2.client.provider.keycloak.issuer-uri") {
                "http://${keycloak.host}:${keycloak.firstMappedPort}/realms/bts"
            }
            // DataSource/Flyway/LDAP 비활성화 — Keycloak 통합 테스트는 DB/LDAP 불필요 (LDAP Bean은 @MockBean)
            r.add("spring.autoconfigure.exclude") {
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.data.ldap.LdapDataAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration"
            }
        }
    }

    // LDAP Bean들 — Keycloak 통합 테스트에서 불필요하므로 Mock으로 대체 (DataSource 없음)
    @MockBean
    lateinit var ldapProvider: LdapProvider

    @MockBean
    lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean
    lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean
    lateinit var ldapTemplate: LdapTemplate

    @LocalServerPort
    var serverPort: Int = 0

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    // Keycloak Resource Owner Password Grant (directAccessGrantsEnabled: true 필요)
    protected fun obtainTokenFromKeycloak(
        username: String,
        password: String,
    ): String {
        val tokenUrl =
            "http://${keycloak.host}:${keycloak.firstMappedPort}" +
                "/realms/bts/protocol/openid-connect/token"
        val body =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "password")
                add("client_id", "bts-web")
                add("username", username)
                add("password", password)
            }
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
        val resp = RestTemplate().postForEntity(tokenUrl, HttpEntity(body, headers), Map::class.java)
        // 진단 메시지 보강 (CONCERN-NEW-1 — 토큰 자체는 절대 로그 출력 금지, 키 집합만)
        return resp.body?.get("access_token") as? String
            ?: error(
                "Keycloak token endpoint returned no access_token: " +
                    "status=${resp.statusCode} bodyKeys=${resp.body?.keys}",
            )
    }
}
