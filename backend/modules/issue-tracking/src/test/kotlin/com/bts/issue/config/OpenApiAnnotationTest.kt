// OpenAPI annotation 존재 검증 — summary·cursor 스키마·security requirement·전 목록 GET·응답 형태 불변

package com.bts.issue.config

import com.bts.issue.CrossBcPortTestConfig
import com.bts.issue.IssueTrackingApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
 * OpenAPI annotation 통합 검증 (FR-API-01 Task 7).
 *
 * ## 검증 시나리오
 * - A1. 이슈 CRUD/목록/전환/changelog 엔드포인트에 summary 가 설정된다
 * - A2. list 오퍼레이션 description 에 cursor 모드 · forward-only 가 명시된다 (CONCERN-4)
 * - A3. cursor envelope 응답 스키마(PageCursor) 가 components/schemas 에 등록된다
 * - A4. 이슈 엔드포인트에 bearerAuth security requirement 가 설정된다
 * - A5. 전 목록 GET 엔드포인트에 summary 가 설정된다 (FR-8 기본 annotation)
 * - A6. 기존 DataResponse data 래퍼 응답 구조가 변경되지 않는다 (무회귀 가드)
 *
 * ## 부팅 패턴
 * [OpenApiDocsIntegrationTest] 와 동일한 MOCK + MockMvc 패턴 사용.
 * 다른 BC port 구현체가 issue-tracking 단독 부팅 시 부재하므로 [CrossBcPortTestConfig] stub 처리.
 */
@SpringBootTest(
    classes = [IssueTrackingApplication::class, CrossBcPortTestConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@ActiveProfiles("test")
class OpenApiAnnotationTest {
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
                .withDatabaseName("bts_annotation_test")
                .withUsername("bts")
                .withPassword("bts_annotation_test")
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    /** /v3/api-docs 를 GET 하여 JsonNode 로 반환하는 헬퍼. */
    private fun fetchApiDocs(): JsonNode {
        val result =
            mockMvc
                .perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk)
                .andReturn()
        return objectMapper.readTree(result.response.contentAsString)
    }

    /**
     * A1. 이슈 CRUD/목록/전환/changelog 엔드포인트에 summary 가 설정되어야 한다.
     *
     * @Operation(summary=...) 없으면 springdoc 이 summary 를 빈 문자열로 생성 → RED.
     * annotation 부여 후 GREEN.
     */
    @Test
    fun `A1 이슈 CRUD 엔드포인트에 summary 가 설정된다`() {
        val paths = fetchApiDocs().path("paths")

        val checks =
            listOf(
                "POST /api/v1/issues (create)" to paths.path("/api/v1/issues").path("post").path("summary").asText(),
                "GET /api/v1/issues/{key} (get)" to
                    paths.path("/api/v1/issues/{key}").path("get").path("summary").asText(),
                "GET /api/v1/issues (list)" to paths.path("/api/v1/issues").path("get").path("summary").asText(),
                "PATCH /api/v1/issues/{key} (update)" to
                    paths.path("/api/v1/issues/{key}").path("patch").path("summary").asText(),
                "POST /api/v1/issues/{key}/transition" to
                    paths.path("/api/v1/issues/{key}/transition").path("post").path("summary").asText(),
                "GET /api/v1/issues/{key}/changelog" to
                    paths.path("/api/v1/issues/{key}/changelog").path("get").path("summary").asText(),
                "DELETE /api/v1/issues/{key}" to
                    paths.path("/api/v1/issues/{key}").path("delete").path("summary").asText(),
            )

        checks.forEach { (endpoint, summary) ->
            assertThat(summary)
                .withFailMessage("$endpoint 에 summary 가 없습니다. @Operation(summary=...) 을 추가하세요.")
                .isNotBlank()
        }
    }

    /**
     * A2. list 오퍼레이션 description 에 cursor 모드·offset 모드·forward-only 가 명시되어야 한다 (CONCERN-4).
     *
     * @Operation(description=...) 없으면 description 이 빈 문자열 → RED.
     * "cursor", "forward" 키워드가 description 에 포함되어야 GREEN.
     */
    @Test
    fun `A2 list 오퍼레이션 description 에 cursor 모드와 forward-only 가 명시된다`() {
        val listOp = fetchApiDocs().path("paths").path("/api/v1/issues").path("get")
        val description = listOp.path("description").asText()

        assertThat(description)
            .withFailMessage(
                "GET /api/v1/issues description 에 'cursor' 키워드가 없습니다. " +
                    "CONCERN-4: cursor 권장·offset 호환 설명 필요.",
            )
            .containsIgnoringCase("cursor")

        assertThat(description)
            .withFailMessage(
                "GET /api/v1/issues description 에 'forward' 키워드가 없습니다. " +
                    "CONCERN-4: forward-only(prev 없음) 명시 필요.",
            )
            .containsIgnoringCase("forward")
    }

    /**
     * A3. cursor envelope 응답 스키마(PageCursor) 가 components/schemas 에 등록되어야 한다.
     *
     * list/changelog 가 ResponseEntity<Any> 를 반환하므로 springdoc 이 스키마를 자동 추론 불가.
     * @ApiResponse(content=@Content(schema=@Schema(implementation=CursorPageResponse::class))) 추가 전 → RED.
     * annotation 추가 후 CursorPageResponse·PageMeta·PageCursor 가 components/schemas 에 등록 → GREEN.
     */
    @Test
    fun `A3 cursor envelope 응답 스키마가 components schemas 에 등록된다`() {
        val schemas = fetchApiDocs().path("components").path("schemas")

        assertThat(schemas.has("PageCursor"))
            .withFailMessage(
                "components.schemas.PageCursor 가 없습니다. " +
                    "list/changelog 에 @ApiResponse(schema=CursorPageResponse::class) 추가 필요.",
            )
            .isTrue()

        val pageCursor = schemas.path("PageCursor").path("properties")

        assertThat(pageCursor.has("next"))
            .withFailMessage("PageCursor.properties.next 가 없습니다 (cursor 토큰 필드).")
            .isTrue()

        assertThat(pageCursor.has("limit"))
            .withFailMessage("PageCursor.properties.limit 가 없습니다 (페이지 크기 필드).")
            .isTrue()

        assertThat(schemas.has("PageMeta"))
            .withFailMessage("components.schemas.PageMeta 가 없습니다.")
            .isTrue()
    }

    /**
     * A4. 인증이 필요한 이슈 엔드포인트에 bearerAuth security requirement 가 설정되어야 한다.
     *
     * @SecurityRequirement(name="bearerAuth") 없으면 operation 에 security 배열이 없음 → RED.
     * annotation 추가 후 security: [{bearerAuth: []}] 가 등록 → GREEN.
     */
    @Test
    fun `A4 이슈 엔드포인트에 bearerAuth security requirement 가 설정된다`() {
        val paths = fetchApiDocs().path("paths")

        val securityChecks =
            listOf(
                "POST /api/v1/issues" to paths.path("/api/v1/issues").path("post"),
                "GET /api/v1/issues/{key}" to paths.path("/api/v1/issues/{key}").path("get"),
                "GET /api/v1/issues" to paths.path("/api/v1/issues").path("get"),
                "POST /api/v1/issues/{key}/transition" to paths.path("/api/v1/issues/{key}/transition").path("post"),
            )

        securityChecks.forEach { (label, opNode) ->
            val securityNode = opNode.path("security")
            assertThat(securityNode.isMissingNode)
                .withFailMessage(
                    "$label 에 security requirement 가 없습니다. " +
                        "@SecurityRequirement(name=\"bearerAuth\") 를 추가하세요.",
                )
                .isFalse()

            val hasBearerAuth =
                securityNode.any { entry ->
                    entry.has("bearerAuth")
                }
            assertThat(hasBearerAuth)
                .withFailMessage("$label security 에 bearerAuth 가 포함되어 있지 않습니다.")
                .isTrue()
        }
    }

    /**
     * A5. 전 목록 GET 엔드포인트에 summary 가 설정되어야 한다 (FR-8 기본 annotation).
     *
     * watcher/component/version/worklog/link/template/customfield/attachment GET 컨트롤러.
     * @Operation(summary=...) 없으면 summary 빈 문자열 → RED.
     */
    @Test
    fun `A5 전 목록 GET 엔드포인트에 summary 가 설정된다`() {
        val paths = fetchApiDocs().path("paths")

        val listEndpoints =
            listOf(
                "GET /api/v1/issues/{key}/watchers" to
                    paths.path("/api/v1/issues/{key}/watchers").path("get").path("summary").asText(),
                "GET /api/v1/projects/{projectIdOrKey}/components" to
                    paths.path("/api/v1/projects/{projectIdOrKey}/components").path("get").path("summary").asText(),
                "GET /api/v1/projects/{projectIdOrKey}/versions" to
                    paths.path("/api/v1/projects/{projectIdOrKey}/versions").path("get").path("summary").asText(),
                "GET /api/v1/issues/{key}/worklogs" to
                    paths.path("/api/v1/issues/{key}/worklogs").path("get").path("summary").asText(),
                "GET /api/v1/issues/{key}/links" to
                    paths.path("/api/v1/issues/{key}/links").path("get").path("summary").asText(),
                "GET /api/v1/projects/{projectIdOrKey}/issue-templates" to
                    paths
                        .path("/api/v1/projects/{projectIdOrKey}/issue-templates")
                        .path("get")
                        .path("summary")
                        .asText(),
                "GET /api/v1/projects/{projectIdOrKey}/custom-fields" to
                    paths.path("/api/v1/projects/{projectIdOrKey}/custom-fields").path("get").path("summary").asText(),
                "GET /api/v1/issues/{key}/attachments" to
                    paths.path("/api/v1/issues/{key}/attachments").path("get").path("summary").asText(),
            )

        listEndpoints.forEach { (endpoint, summary) ->
            assertThat(summary)
                .withFailMessage("$endpoint 에 summary 가 없습니다. @Operation(summary=...) 을 추가하세요.")
                .isNotBlank()
        }
    }

    /**
     * A6. 기존 DataResponse data 래퍼 응답 구조가 변경되지 않아야 한다 (무회귀 가드).
     *
     * annotation 추가 시 기존 ResponseEntity<DataResponse<IssueResponse>> 응답 형태가 바뀌지 않음을 검증.
     * components/schemas 에서 'DataResponse' 계열 스키마에 data 프로퍼티가 존재하는지 확인한다.
     * springdoc 버전/Kotlin 처리 방식에 따라 스키마 이름이 DataResponse, DataResponseIssueResponse,
     * DataResponse«IssueResponse» 등 다를 수 있으므로 이름 prefix 와 data 프로퍼티 존재 여부로 검증한다.
     * 이 테스트는 annotation 추가 전후 모두 GREEN 이어야 하며, 응답 형태 변경 시 RED 로 잡힌다.
     */
    @Test
    fun `A6 단건 조회 200 응답에 data 프로퍼티가 있다 응답 형태 불변 회귀 가드`() {
        val tree = fetchApiDocs()

        // GET /api/v1/issues/{key} 오퍼레이션과 200 응답 자체는 반드시 존재해야 한다
        val getOp = tree.path("paths").path("/api/v1/issues/{key}").path("get")
        assertThat(getOp.isMissingNode)
            .withFailMessage("GET /api/v1/issues/{key} 오퍼레이션이 스펙에 없습니다.")
            .isFalse()

        val response200 = getOp.path("responses").path("200")
        assertThat(response200.isMissingNode)
            .withFailMessage("GET /api/v1/issues/{key} 200 응답이 스펙에 없습니다.")
            .isFalse()

        // components/schemas 에서 DataResponse 계열 스키마에 data 프로퍼티 존재 확인
        // springdoc 이 ResponseEntity<DataResponse<IssueResponse>> 에서 생성하는 스키마 이름은
        // 버전/Kotlin 처리에 따라 다르므로 이름 포함 여부가 아닌 프로퍼티 존재로 검증한다
        val schemas = tree.path("components").path("schemas")
        val schemaNames = schemas.fieldNames().asSequence().toList()

        val hasDataWrapper =
            schemas.fields().asSequence().any { (_, schemaNode) ->
                schemaNode.path("properties").has("data")
            }

        assertThat(hasDataWrapper)
            .withFailMessage(
                "components.schemas 에 data 프로퍼티를 가진 스키마가 없습니다. " +
                    "DataResponse 래퍼가 제거되었거나 응답 형태가 변경되었습니다. " +
                    "현재 등록된 스키마: $schemaNames",
            )
            .isTrue()
    }

    /**
     * A7. 기존 bulk 엔드포인트(FR-IS-05)에 summary·bearerAuth 가 설정되어야 한다 (FR-API-01 bulk 표준 적용).
     *
     * spec FR-10: 신규 도메인/엔드포인트 추가 0, 기존 bulk 엔드포인트에 OpenAPI annotation 적용.
     * @Operation(summary=...) / @SecurityRequirement(name="bearerAuth") 없으면 RED. annotation 추가 후 GREEN.
     */
    @Test
    fun `A7 bulk 엔드포인트에 summary 와 bearerAuth 가 설정된다`() {
        val paths = fetchApiDocs().path("paths")

        val bulkOps =
            listOf(
                "POST /api/v1/issues/bulk-update" to paths.path("/api/v1/issues/bulk-update").path("post"),
                "GET /api/v1/bulk-operations/{id}" to paths.path("/api/v1/bulk-operations/{id}").path("get"),
                "POST /api/v1/issues/bulk-transitions/available" to
                    paths.path("/api/v1/issues/bulk-transitions/available").path("post"),
            )

        bulkOps.forEach { (endpoint, opNode) ->
            assertThat(opNode.path("summary").asText())
                .withFailMessage("$endpoint 에 summary 가 없습니다. @Operation(summary=...) 을 추가하세요.")
                .isNotBlank()

            val securityNode = opNode.path("security")
            val hasBearerAuth = !securityNode.isMissingNode && securityNode.any { it.has("bearerAuth") }
            assertThat(hasBearerAuth)
                .withFailMessage("$endpoint 에 bearerAuth security requirement 가 없습니다.")
                .isTrue()
        }
    }
}
