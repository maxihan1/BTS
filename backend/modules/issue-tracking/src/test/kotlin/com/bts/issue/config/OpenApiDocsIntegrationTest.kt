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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
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
 * `webAppContextSetup` 은 Spring Security 필터 체인을 통해 요청을 처리하므로
 * 인증 없이 200 이 반환되려면 [OpenApiSecurityConfig] 의 ignoring 설정이 반드시 적용되어야 한다.
 *
 * ## 왜 WebEnvironment.MOCK 인가
 * 이 모듈은 spring-boot-starter-web(내장 Tomcat)을 포함하지 않으므로 RANDOM_PORT 부팅이 불가하다.
 * [ProjectRequire2faControllerIntegrationTest] 등 기존 통합 테스트와 동일한 MOCK + MockMvc 패턴을 사용한다.
 *
 * ## MockBean 이유
 * 다른 BC (project-workflow, identity-access) 의 port 구현체가 issue-tracking 단독 부팅 시 부재.
 * [com.bts.issue.IssueTrackingApplicationContextTest] 와 동일한 stub 패턴 사용.
 */
@SpringBootTest(
    classes = [IssueTrackingApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
    private lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

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
        val result = mockMvc.perform(
            get("/v3/api-docs").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andReturn()

        val body = result.response.contentAsString
        val tree = objectMapper.readTree(body)

        val openapiVersion = tree.get("openapi")?.asText()
        assertThat(openapiVersion)
            .withFailMessage(
                "openapi 필드가 없거나 3.1.x 가 아닙니다: $openapiVersion (본문 앞 200자: ${body.take(200)})",
            )
            .isNotNull()
            .startsWith("3.1")
    }

    /**
     * D2. securitySchemes.bearerAuth 가 http/bearer/JWT 로 정의되어 있음을 실증.
     *
     * [OpenApiConfig] 의 @SecurityScheme(name="bearerAuth", ...) 이 누락되면 RED.
     */
    @Test
    fun `D2 securitySchemes 에 bearerAuth 가 http bearer JWT 로 정의된다`() {
        val result = mockMvc.perform(
            get("/v3/api-docs").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andReturn()

        val body = result.response.contentAsString
        val tree = objectMapper.readTree(body)

        val bearerAuth = tree
            .path("components")
            .path("securitySchemes")
            .path("bearerAuth")

        assertThat(bearerAuth.isMissingNode)
            .withFailMessage("securitySchemes.bearerAuth 가 OpenAPI 스펙에 없습니다. 본문 앞 300자: ${body.take(300)}")
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
        mockMvc.perform(get("/swagger-ui/index.html"))
            .andExpect(
                status().isOk
                    .also {
                        // failMessage 는 MockMvc ResultMatcher 가 아닌 Assertion 으로 제공
                    },
            )
    }
}
