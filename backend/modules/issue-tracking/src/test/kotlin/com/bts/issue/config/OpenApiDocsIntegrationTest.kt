// springdoc OpenAPI 3.1 통합 검증 — /v3/api-docs 200·openapi 3.1.x·bearerAuth 스킴·/swagger-ui 200

package com.bts.issue.config

import com.bts.issue.IssueTrackingApplication
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowTransitionPort
import com.bts.workflow.scheme.application.port.IssueTypeUsagePort
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * springdoc OpenAPI 3.1 통합 검증 (FR-API-01 Task 6).
 *
 * ## 검증 시나리오
 * - D1. GET /v3/api-docs → 200 + openapi 필드가 "3.1.x"
 * - D2. securitySchemes.bearerAuth 정의 존재 (http/bearer/JWT)
 * - D3. GET /swagger-ui/index.html → 200
 *
 * ## CONCERN-1 (가짜그린 방지)
 * Spring Security 가 classpath 에 있으면 /v3/api-docs, /swagger-ui 에 인증을 요구해
 * 401 또는 302 가 반환될 수 있다. 이 테스트가 실제 200 을 실증하므로 보안 설정 누락 시 RED 가 잡아낸다.
 *
 * ## 왜 RANDOM_PORT 인가
 * springdoc 이 서빙하는 /swagger-ui/index.html 은 내장 Tomcat 이 처리하는 실제 정적 리소스이므로,
 * 실 포트에서 HTTP 를 검증해야 한다.
 *
 * ## MockBean 이유
 * 다른 BC (project-workflow, identity-access) 의 port 구현체가 issue-tracking 단독 부팅 시 부재.
 * [IssueTrackingApplicationContextTest] 와 동일한 stub 패턴 사용.
 */
@SpringBootTest(
    classes = [IssueTrackingApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test")
class OpenApiDocsIntegrationTest {

    @MockBean
    lateinit var workflowTransitionPort: WorkflowTransitionPort

    @MockBean
    lateinit var workflowKeyResolver: WorkflowKeyResolver

    @MockBean
    lateinit var workflowStateCatalog: WorkflowStateCatalog

    @MockBean
    lateinit var userLookupPort: UserLookupPort

    @MockBean
    lateinit var issueTypeUsagePort: IssueTypeUsagePort

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    companion object {
        private val objectMapper = ObjectMapper().registerKotlinModule()

        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_openapi_test")
                .withUsername("bts")
                .withPassword("bts_openapi_test")
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    /**
     * D1. GET /v3/api-docs → 200 + openapi 필드가 "3.1.x" 임을 실증.
     *
     * springdoc 미통합 또는 보안으로 인해 401 이 반환되면 이 테스트가 RED 로 잡아낸다.
     */
    @Test
    fun `D1 GET v3 api-docs 는 200 을 반환하고 openapi 3 1 x 버전임을 나타낸다`() {
        val response = restTemplate.getForEntity("/v3/api-docs", String::class.java)

        assertThat(response.statusCode)
            .withFailMessage("GET /v3/api-docs 가 ${response.statusCode} 를 반환했습니다. 200 이어야 합니다. " +
                "springdoc 미통합 또는 Security 가 401/302 로 차단 중일 수 있습니다.")
            .isEqualTo(HttpStatus.OK)

        val body = requireNotNull(response.body) { "응답 본문이 null 입니다." }
        val tree = objectMapper.readTree(body)

        val openapiVersion = tree.get("openapi")?.asText()
        assertThat(openapiVersion)
            .withFailMessage("openapi 필드가 없거나 3.1.x 가 아닙니다: $openapiVersion")
            .isNotNull()
            .startsWith("3.1")
    }

    /**
     * D2. securitySchemes.bearerAuth 가 http/bearer/JWT 로 정의되어 있음을 실증.
     *
     * OpenApiConfig 의 @SecurityScheme(name="bearerAuth", ...) 이 누락되면 RED.
     */
    @Test
    fun `D2 securitySchemes 에 bearerAuth 가 http bearer JWT 로 정의된다`() {
        val response = restTemplate.getForEntity("/v3/api-docs", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val body = requireNotNull(response.body) { "응답 본문이 null 입니다." }
        val tree = objectMapper.readTree(body)

        val bearerAuth = tree
            .path("components")
            .path("securitySchemes")
            .path("bearerAuth")

        assertThat(bearerAuth.isMissingNode)
            .withFailMessage("securitySchemes.bearerAuth 가 OpenAPI 스펙에 없습니다.")
            .isFalse()

        assertThat(bearerAuth.get("type")?.asText())
            .withFailMessage("bearerAuth.type 이 'http' 가 아닙니다.")
            .isEqualToIgnoringCase("http")

        assertThat(bearerAuth.get("scheme")?.asText())
            .withFailMessage("bearerAuth.scheme 이 'bearer' 가 아닙니다.")
            .isEqualToIgnoringCase("bearer")

        assertThat(bearerAuth.get("bearerFormat")?.asText())
            .withFailMessage("bearerAuth.bearerFormat 이 'JWT' 가 아닙니다.")
            .isEqualToIgnoringCase("JWT")
    }

    /**
     * D3. GET /swagger-ui/index.html → 200 을 실증.
     *
     * springdoc-webmvc-ui 미포함 또는 Security 로 차단 시 RED.
     */
    @Test
    fun `D3 GET swagger-ui index html 은 200 을 반환한다`() {
        val response = restTemplate.getForEntity("/swagger-ui/index.html", String::class.java)

        assertThat(response.statusCode)
            .withFailMessage("GET /swagger-ui/index.html 이 ${response.statusCode} 를 반환했습니다. 200 이어야 합니다. " +
                "springdoc-webmvc-ui 미포함 또는 Security 가 401/302 로 차단 중일 수 있습니다.")
            .isEqualTo(HttpStatus.OK)
    }
}
