// OpenAPI annotation 존재 검증 — search 엔드포인트 tags·operationId·security·응답 스키마 (FR-API-02 Task 2)

package com.bts.search.config

import com.bts.shared.http.OutboundUrlValidator
import com.bts.shared.issue.IssueImportPort
import com.bts.shared.issue.IssueTypeCatalog
import com.bts.shared.membership.GroupMembershipPort
import com.bts.shared.membership.ProjectMembershipPort
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.SystemPermissionResolver
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.WorkflowStateCatalog
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.client.RestClient
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * OpenAPI annotation 통합 검증 (FR-API-02 Task 2).
 *
 * ## 검증 시나리오
 * - B1. POST /api/v1/search/aql 오퍼레이션 tags 에 "Search" 가 포함된다
 * - B2. operationId 와 summary 가 설정된다
 * - B3. bearerAuth security requirement 가 설정된다
 * - B4. 200 응답 스키마에 AqlSearchPageResponse 가 components/schemas 에 등록된다
 *
 * ## 부팅 패턴
 * [SearchTestApplication] 으로 com.bts.search 패키지를 스캔한다.
 * @EnableWebMvc 가 붙은 테스트 전용 @Configuration 클래스(TestMvcConfig 계열)는
 * ComponentScan excludeFilter 로 제외해 Spring Boot MVC 자동 구성을 보호한다.
 * cross-BC 포트([IssueSearchPort]/[GroupMembershipPort]/[ProjectMembershipPort])는 @MockBean.
 * MinIO 는 가짜 endpoint(bts.minio.endpoint=http://localhost:9001) 설정 후 best-effort warn 통과.
 * PostgreSQL 은 Testcontainers(quay.io/tembo/pg16-pgmq) 로 제공 — Flyway V600~ 자동 적용.
 */
@SpringBootTest(
    classes = [OpenApiAnnotationTest.SearchTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "bts.minio.endpoint=http://localhost:9001",
        // MinioClient.credentials() 는 빈 문자열을 거부하므로 더미값 제공.
        // ensureBucket()이 모든 예외를 MinioExportStorageException으로 감싸 warn만 기록하므로
        // 실제 MinIO 연결 없이 앱이 기동된다.
        "bts.minio.access-key=minioadmin",
        "bts.minio.secret-key=minioadmin",
    ],
)
class OpenApiAnnotationTest {
    /**
     * 테스트 전용 Spring Boot 진입점.
     *
     * [@SpringBootApplication][org.springframework.boot.autoconfigure.SpringBootApplication] 을
     * 명시적으로 분해([SpringBootConfiguration] + [EnableAutoConfiguration] + [ComponentScan])해
     * @EnableWebMvc 붙은 테스트 @Configuration 클래스를 ComponentScan 에서 제외한다.
     * 이 제외 없이는 test 소스의 TestMvcConfig 들이 WebMvcConfigurationSupport 를 중복 등록해
     * Spring Boot MVC 자동 구성을 덮어쓰므로 springdoc MVC 연동이 깨진다.
     */
    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan(
        basePackages = ["com.bts.search"],
        excludeFilters = [
            ComponentScan.Filter(
                type = FilterType.ANNOTATION,
                classes = [EnableWebMvc::class],
            ),
            ComponentScan.Filter(
                type = FilterType.CUSTOM,
                classes = [TypeExcludeFilter::class],
            ),
            ComponentScan.Filter(
                type = FilterType.CUSTOM,
                classes = [AutoConfigurationExcludeFilter::class],
            ),
        ],
    )
    open class SearchTestApplication

    /** AQL 검색 실행 포트 — cross-BC 포트, stub 처리. */
    @MockBean
    lateinit var issueSearchPort: IssueSearchPort

    /** 이슈 Import 쓰기 포트 — cross-BC(issue-tracking), [com.bts.search.imports.job.application.ImportJobProcessor] 의존성. */
    @MockBean
    lateinit var issueImportPort: IssueImportPort

    /**
     * 사용자 조회 포트 — cross-BC(identity-access). 사용자매핑 자동해석·실재확인
     * ([com.bts.search.imports.mapping.ImportMappingService]) + 프로세서 의존성(FR-IM-02 PR-B).
     */
    @MockBean
    lateinit var userLookupPort: UserLookupPort

    /**
     * 전역 이슈타입 전체 목록 조회 포트 — cross-BC(issue-tracking). 값매핑 TYPE 자동추천/검증
     * ([com.bts.search.imports.mapping.ImportMappingService]) 의존성(FR-IM-02 PR-C).
     */
    @MockBean
    lateinit var issueTypeCatalog: IssueTypeCatalog

    /**
     * 프로젝트 워크플로우 상태 전체 목록 조회 포트 — cross-BC(project-workflow). 값매핑 STATUS
     * 자동추천([com.bts.search.imports.mapping.ImportMappingService]) 의존성(FR-IM-02 PR-C).
     */
    @MockBean
    lateinit var workflowStateCatalog: WorkflowStateCatalog

    /** 이슈 권한 판정 포트 — cross-BC(issue-tracking), ImportJobService 접수 권한 fail-fast 의존성. */
    @MockBean
    lateinit var issuePermissionResolver: IssuePermissionResolver

    /** 그룹 멤버십 포트 — cross-BC, [com.bts.search.savedfilter.application.SavedFilterService] fail-closed 의존성. */
    @MockBean
    lateinit var groupMembershipPort: GroupMembershipPort

    /** 프로젝트 멤버십 포트 — cross-BC, [com.bts.search.savedfilter.application.SavedFilterService] fail-closed 의존성. */
    @MockBean
    lateinit var projectMembershipPort: ProjectMembershipPort

    /** 전역 admin 판정 포트 — cross-BC(identity-access), [com.bts.search.webhook.application.OutboundWebhookService] 의존성. */
    @MockBean
    lateinit var systemPermissionResolver: SystemPermissionResolver

    /** 아웃바운드 URL SSRF 검증기 — shared-kernel, [com.bts.search.webhook.application.OutboundWebhookService] 의존성. */
    @MockBean
    lateinit var outboundUrlValidator: OutboundUrlValidator

    /**
     * SSRF 재검증 + HTTP 발송용 shared RestClient — `com.bts.shared.http` 패키지는 스캔 대상이
     * 아니라 [com.bts.search.webhook.dispatch.SearchWebhookDispatcher](FR-API-03 PR3 Task 7) 생성자
     * 의존성이 해소되지 않는다. @MockBean 으로 채워 full-boot 부팅만 통과시킨다(OpenAPI 검증 목적과 무관).
     */
    @MockBean
    lateinit var restClient: RestClient

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    companion object {
        private val objectMapper = ObjectMapper().registerKotlinModule()

        /**
         * JVM 단위 singleton PostgreSQL container.
         *
         * search-export-import V602 마이그레이션이 `CREATE EXTENSION pgmq` 를 포함하므로
         * pgmq 사전 설치된 Tembo 이미지([com.bts.search.savedfilter.persistence.SearchPersistenceTestBase] 동일)가 필수다.
         * Spring Boot Flyway 자동 구성이 V600~ 마이그레이션을 자동 적용한다.
         */
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
     * B1. POST /api/v1/search/aql 오퍼레이션 tags 에 "Search" 가 포함되어야 한다.
     *
     * @Tag(name="Search") 없으면 springdoc 이 컨트롤러 이름("search-controller")을 기본 태그로 생성 → RED.
     * annotation 추가 후 tags 에 "Search" 포함 → GREEN.
     */
    @Test
    fun `B1 search aql 엔드포인트 tags에 Search가 포함된다`() {
        val postOp = fetchApiDocs().path("paths").path("/api/v1/search/aql").path("post")

        assertThat(postOp.isMissingNode)
            .withFailMessage("POST /api/v1/search/aql 오퍼레이션이 스펙에 없습니다.")
            .isFalse()

        val tags = postOp.path("tags")
        val tagValues = if (tags.isMissingNode) emptyList() else tags.map { it.asText() }
        assertThat(tagValues)
            .withFailMessage(
                "POST /api/v1/search/aql tags 에 'Search' 가 없습니다. " +
                    "현재: $tagValues. @Tag(name=\"Search\") 를 추가하세요.",
            )
            .contains("Search")
    }

    /**
     * B2. operationId 와 summary 가 설정되어야 한다.
     *
     * @Operation(summary=...) 없으면 springdoc 이 summary 를 빈 문자열로 생성 → RED.
     * @Operation(operationId=...) 없으면 메서드명("search")이 자동 사용 — summary 가 더 명확한 guard.
     * annotation 추가 후 GREEN.
     */
    @Test
    fun `B2 operationId와 summary가 설정된다`() {
        val postOp = fetchApiDocs().path("paths").path("/api/v1/search/aql").path("post")

        val operationId = postOp.path("operationId").asText()
        assertThat(operationId)
            .withFailMessage(
                "POST /api/v1/search/aql 에 operationId 가 없습니다. " +
                    "@Operation(operationId=...) 을 추가하세요.",
            )
            .isNotBlank()

        val summary = postOp.path("summary").asText()
        assertThat(summary)
            .withFailMessage(
                "POST /api/v1/search/aql 에 summary 가 없습니다. " +
                    "@Operation(summary=...) 을 추가하세요.",
            )
            .isNotBlank()
    }

    /**
     * B3. bearerAuth security requirement 가 설정되어야 한다.
     *
     * @SecurityRequirement(name="bearerAuth") 없으면 operation 의 security 배열이 누락 → RED.
     * annotation 추가 후 security: [{bearerAuth: []}] 등록 → GREEN.
     */
    @Test
    fun `B3 bearerAuth security requirement가 설정된다`() {
        val postOp = fetchApiDocs().path("paths").path("/api/v1/search/aql").path("post")

        val securityNode = postOp.path("security")
        assertThat(securityNode.isMissingNode)
            .withFailMessage(
                "POST /api/v1/search/aql 에 security requirement 가 없습니다. " +
                    "@SecurityRequirement(name=\"bearerAuth\") 를 추가하세요.",
            )
            .isFalse()

        val hasBearerAuth = securityNode.any { it.has("bearerAuth") }
        assertThat(hasBearerAuth)
            .withFailMessage(
                "POST /api/v1/search/aql security 에 bearerAuth 가 없습니다.",
            )
            .isTrue()
    }

    /**
     * B4. 200 응답 스키마에 AqlSearchPageResponse 가 components/schemas 에 등록되어야 한다.
     *
     * 제네릭 erasure 방어(EC5): @ApiResponse(schema=AqlSearchPageResponse::class) 없으면
     * springdoc 이 ResponseEntity<AqlSearchPageResponse<AqlSearchHit>> 에서 스키마를 추론 불가 → RED.
     * annotation 추가 후 AqlSearchPageResponse(data·meta) 스키마 등록 → GREEN.
     */
    @Test
    fun `B4 200 응답 스키마에 AqlSearchPageResponse가 등록된다`() {
        val tree = fetchApiDocs()
        val schemas = tree.path("components").path("schemas")

        assertThat(schemas.has("AqlSearchPageResponse"))
            .withFailMessage(
                "components.schemas.AqlSearchPageResponse 가 없습니다. " +
                    "@ApiResponse(content=@Content(schema=@Schema(implementation=AqlSearchPageResponse::class))) " +
                    "를 추가하세요.",
            )
            .isTrue()

        val props = schemas.path("AqlSearchPageResponse").path("properties")
        assertThat(props.has("data"))
            .withFailMessage("AqlSearchPageResponse.properties.data 가 없습니다.")
            .isTrue()
        assertThat(props.has("meta"))
            .withFailMessage("AqlSearchPageResponse.properties.meta 가 없습니다.")
            .isTrue()
    }
}
