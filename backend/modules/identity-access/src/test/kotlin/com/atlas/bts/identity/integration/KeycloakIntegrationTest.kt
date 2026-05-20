// Keycloak Testcontainers 통합 테스트 — JWT 발급/검증 + CSRF 면역 실제 동작 확인

package com.atlas.bts.identity.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class KeycloakIntegrationTest {
    companion object {
        // MountableFile.forHostPath() — Testcontainers가 Docker 데몬에 절대 경로를 안정적으로 전달.
        // user.dir은 Gradle 실행 시 모듈 디렉토리가 아닌 루트를 가리킬 수 있으므로,
        // 이 클래스 파일의 위치에서 프로젝트 루트를 역추적하는 대신 known-good 절대 경로 직접 지정.
        private val realmJsonPath: String =
            run {
                // worktree 루트를 기준으로 계산. user.dir은 Gradle 실행 디렉토리.
                val gradleDir = System.getProperty("user.dir")
                // modules/identity-access 아래에서 실행되는 경우 / backend 아래 / backend 루트 모두 커버
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
        fun props(r: DynamicPropertyRegistry) {
            r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") {
                "http://${keycloak.host}:${keycloak.firstMappedPort}/realms/bts"
            }
            // OAuth2 client provider도 Testcontainers 포트로 오버라이드 (issuer-uri 기반 auto-discovery 충돌 방지)
            r.add("spring.security.oauth2.client.provider.keycloak.issuer-uri") {
                "http://${keycloak.host}:${keycloak.firstMappedPort}/realms/bts"
            }
        }
    }

    @LocalServerPort
    var serverPort: Int = 0

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    // T1. spec F4 — 무인증 GET → 401
    @Test
    fun `GET whoami returns 401 without token`() {
        val resp = restTemplate.getForEntity("/api/v1/users/me/whoami", String::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // T2. spec F4 — Keycloak에서 JWT 발급 + GET 200 (실제 JWT 검증 확인)
    @Test
    fun `GET whoami returns 200 with valid JWT from Keycloak`() {
        val token = obtainTokenFromKeycloak("alice", "Test1234!")
        val headers = HttpHeaders().apply { setBearerAuth(token) }
        val resp =
            restTemplate.exchange(
                "/api/v1/users/me/whoami",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                Map::class.java,
            )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body?.get("username")).isEqualTo("alice")
    }

    // T3. T5 CONCERN 보강 — 미인증 POST → CSRF 필터(or 인증 필터)가 먼저 차단
    // Bearer Token 방식은 stateless라 CSRF 면역이지만,
    // 미인증 요청은 인증 필터(401) 또는 CSRF 필터(403) 중 하나가 차단.
    // 실 브라우저에서는 세션 쿠키 없는 POST이므로 403이 기대값.
    // TestRestTemplate은 실제 HTTP 스택이므로 Mock과 달리 실제 필터 체인 순서가 적용.
    @Test
    fun `POST preferences returns 401 or 403 without auth`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val resp =
            restTemplate.postForEntity(
                "/api/v1/users/me/preferences",
                HttpEntity("{}", headers),
                String::class.java,
            )
        // 인증 필터가 CSRF 필터보다 먼저 평가되면 401, 아니면 403.
        // 어느 쪽이든 "접근 거부"가 핵심 — 둘 다 허용.
        assertThat(resp.statusCode).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
    }

    // T4. JWT + POST → 200 (CSRF 면역 확인 — Bearer Token stateless 설계)
    @Test
    fun `POST preferences returns 200 with valid JWT`() {
        val token = obtainTokenFromKeycloak("alice", "Test1234!")
        val headers =
            HttpHeaders().apply {
                setBearerAuth(token)
                contentType = MediaType.APPLICATION_JSON
            }
        val resp =
            restTemplate.exchange(
                "/api/v1/users/me/preferences",
                HttpMethod.POST,
                HttpEntity("{}", headers),
                Map::class.java,
            )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
    }

    // 헬퍼 — Keycloak Resource Owner Password Grant (directAccessGrantsEnabled: true 필요)
    private fun obtainTokenFromKeycloak(
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
        return resp.body?.get("access_token") as String
    }
}
