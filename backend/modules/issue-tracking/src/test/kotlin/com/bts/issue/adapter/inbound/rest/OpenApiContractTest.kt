// OpenAPI 3.1 스펙 ↔ 실제 응답 계약 검증 + cursor end-to-end 순회 통합 테스트 (FR-API-01 Task 8)

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.CrossBcPortTestConfig
import com.bts.issue.IssueTrackingApplication
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * OpenAPI 3.1 스펙 ↔ 실제 응답 계약 검증 + cursor end-to-end 순회 (FR-API-01 Task 8).
 *
 * ## 검증 시나리오
 * - C1. cursor 응답 구조가 OpenAPI 스펙 PageCursor 스키마와 일치한다 (data/meta.page.next/limit).
 * - C2. 이슈 단건 응답이 data 래퍼를 포함하고 스펙과 일치한다.
 * - C3. 위변조 cursor 에러 응답이 RFC 7807 ProblemDetail 스키마와 일치하고 errorCode=ISSUE_INVALID_CURSOR.
 * - T4. 이슈 7건 seed → limit=3 cursor 순회 → 전체 7건 정확히 1번씩 등장, 마지막 페이지 next=null.
 * - T5. created_at 동률 이슈 포함 4건 → limit=1 cursor 순회 → tie-break 안정성 실증.
 * - T6. cursor 모드와 offset 모드가 같은 이슈 집합을 반환한다 (교차 검증).
 *
 * ## 부팅 패턴
 * [com.bts.issue.config.OpenApiDocsIntegrationTest] 와 동일한 MOCK + MockMvc 패턴.
 * 다른 BC port 구현체 부재를 [CrossBcPortTestConfig] 로 처리. AlwaysAllow* stub 이 @Profile("!prod") 로 자동 활성.
 *
 * ## TDD CONCERN — cursor 타임존 일관성
 * T4/T5 는 CursorCodec (Instant→OffsetDateTime UTC 인코딩) ↔ repo seek (OffsetDateTime keyset)
 * 간의 타임존 round-trip 을 실 DB 에서 end-to-end 검증한다.
 * 단위 테스트가 잡지 못하는 production 버그(누락·중복·순환)가 드러나면 BLOCKED 보고.
 */
@SpringBootTest(
    classes = [IssueTrackingApplication::class, CrossBcPortTestConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("TooManyFunctions")
class OpenApiContractTest {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var dataSource: DataSource

    private lateinit var mockMvc: MockMvc

    companion object {
        private const val PROJECT_KEY = "CTRCT"
        private val ACTOR_UUID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule()

        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_contract_test")
                .withUsername("bts")
                .withPassword("bts_contract_test")
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @BeforeAll
    fun setUpAll() {
        seedProject()
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
                .build()
        cleanIssues()
    }

    // ── C1. cursor 응답 스키마 계약 검증 ─────────────────────────────────────

    /**
     * C1. cursor 응답 JSON 구조가 OpenAPI 스펙 PageCursor 스키마와 일치한다.
     *
     * 실제 GET /api/v1/issues?cursor= 응답에서 data/meta.page.next/meta.page.limit 필드 확인.
     * /v3/api-docs 스펙에서 PageCursor.properties.next / .limit 존재 확인.
     * 두 검증이 모두 통과해야 스펙↔응답 계약이 성립한다.
     */
    @Test
    fun `C1 cursor 응답 구조가 OpenAPI 스펙 PageCursor 스키마와 일치한다`() {
        insertIssue("C1 계약 검증 이슈")

        val listResult =
            mockMvc
                .perform(
                    get("/api/v1/issues")
                        .param("cursor", "")
                        .param("projectKey", PROJECT_KEY)
                        .param("limit", "10")
                        .with(user(ACTOR_UUID.toString()).roles("USER")),
                ).andExpect(status().isOk)
                .andReturn()

        val responseTree = objectMapper.readTree(listResult.response.contentAsString)
        val preview = listResult.response.contentAsString.take(300)

        assertThat(responseTree.has("data"))
            .withFailMessage("cursor 응답에 'data' 배열이 없습니다. 응답 앞 300자: $preview")
            .isTrue()
        assertThat(responseTree.path("data").isArray)
            .withFailMessage("cursor 응답의 'data' 가 배열이 아닙니다. 응답 앞 300자: $preview")
            .isTrue()
        assertThat(responseTree.path("meta").path("page").has("next"))
            .withFailMessage("cursor 응답에 'meta.page.next' 가 없습니다. 응답 앞 300자: $preview")
            .isTrue()
        assertThat(responseTree.path("meta").path("page").has("limit"))
            .withFailMessage("cursor 응답에 'meta.page.limit' 가 없습니다. 응답 앞 300자: $preview")
            .isTrue()

        // OpenAPI 스펙 스키마 검증
        val specResult =
            mockMvc
                .perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk)
                .andReturn()
        val specTree = objectMapper.readTree(specResult.response.contentAsString)
        val pageCursorProps = specTree.path("components").path("schemas").path("PageCursor").path("properties")

        assertThat(pageCursorProps.has("next"))
            .withFailMessage("OpenAPI 스펙 components.schemas.PageCursor.properties 에 'next' 가 없습니다.")
            .isTrue()
        assertThat(pageCursorProps.has("limit"))
            .withFailMessage("OpenAPI 스펙 components.schemas.PageCursor.properties 에 'limit' 가 없습니다.")
            .isTrue()
    }

    // ── C1b(FR-UX-09 B1). 생성 요청 스키마 3필드 추가 · 응답 스키마 무변경 ──────

    /**
     * FR-UX-09 B1 — `CreateIssueRequest` 에 `assigneeId`·`priority`·`labels` 가
     * **전부 optional** 로 추가됐는지, 그리고 `IssueResponse` 는 **변경되지 않았는지** 검증한다.
     *
     * 응답 무변경이 계약의 핵심이다 — 이 PR 은 요청만 넓히고 응답은 건드리지 않는다(C3).
     * `isLabelsValid` 는 검증 전용 파생 속성이라 `@JsonIgnore` 로 스키마에서 빠져야 한다.
     */
    @Test
    fun `C1b 생성 요청 스키마에 3필드가 optional 로 추가되고 응답 스키마는 무변경이다`() {
        val specResult =
            mockMvc
                .perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk)
                .andReturn()
        val specTree = objectMapper.readTree(specResult.response.contentAsString)
        val schemas = specTree.path("components").path("schemas")

        val createReq = schemas.path("CreateIssueRequest")
        val createProps = createReq.path("properties")

        listOf("assigneeId", "priority", "labels").forEach { field ->
            assertThat(createProps.has(field))
                .withFailMessage("CreateIssueRequest.properties 에 '$field' 가 없습니다.")
                .isTrue()
        }

        // 전부 optional — required 목록에 들어가면 기존 클라이언트가 깨진다.
        val required = createReq.path("required").map { it.asText() }
        listOf("assigneeId", "priority", "labels").forEach { field ->
            assertThat(required)
                .withFailMessage("'$field' 는 optional 이어야 하지만 required=$required 에 포함됐습니다.")
                .doesNotContain(field)
        }

        // @JsonIgnore 파생 속성은 스키마에 노출되지 않아야 한다.
        assertThat(createProps.has("labelsValid"))
            .withFailMessage("검증 전용 파생 속성이 요청 스키마에 노출됐습니다.")
            .isFalse()

        // ★응답 스키마 무변경 — 3필드는 원래부터 IssueResponse 에 있었다(추가가 아니라 기존 보유).
        val responseProps = schemas.path("IssueResponse").path("properties")
        listOf("assigneeId", "priority", "priorityName", "labels").forEach { field ->
            assertThat(responseProps.has(field))
                .withFailMessage("IssueResponse.properties 에 '$field' 가 없습니다. 응답 계약이 바뀌었습니다.")
                .isTrue()
        }
    }

    // ── C1c. 수정 요청 스키마 대칭 + componentIds required 오표기 ─────────

    /**
     * C1c. `UpdateIssueRequest` 의 검증 전용 파생 속성이 스키마에 새지 않고,
     * 기본값이 있는 `componentIds` 가 required 로 오표기되지 않는다.
     *
     * ★두 축을 한 테스트에 둔 이유 — 둘 다 **「스키마가 실제 계약보다 더 많이 요구한다」**는
     * 같은 결함 양식이다.
     *
     * ### 축 1 — `labelsValid` 누출 (봉합의 짝)
     * TODOS 「도메인 require 실패가 500 으로 나간다」 봉합이 `UpdateIssueRequest` 에
     * `@get:AssertTrue` 파생 속성을 넣었다. `@get:JsonIgnore` 를 빠뜨리면 springdoc 이
     * `labelsValid: boolean` 을 요청 스키마에 추가한다. **C1b 는 create 쪽만 봉인해서
     * 아무도 못 잡는다** — 이 단언이 없으면 그 봉합 자체가 새 결함을 심는다.
     *
     * ### 축 2 — `componentIds` required 오표기
     * `List<UUID> = emptyList()` 는 기본값이 있는데도 springdoc 이 **Kotlin non-null 타입**이라
     * required 로 판정한다. `assigneeId`(`JsonNullable<UUID>`)에서 같은 함정이 재현돼
     * C1b 가 잡았고, 그때 `componentIds` 는 범위 밖으로 남겼다(TODOS 등재).
     *
     * ★같은 함정이 issue-tracking REST DTO **11 클래스 · 27 프로퍼티**에 걸려 있다(2026-08-09 전수 측정).
     * 여기서는 TODOS 가 지목한 `componentIds` 1건만 좁게 닫는다(2026-08-09 Maxi 확정).
     * **손 열거는 반드시 샌다** — 1차 측정이 10건, 전수 재측정이 27건이었다. 잔여 26건과
     * 차집합 판별식은 별도 TODOS 항목이며, 그 판별식은 FQCN 으로 고정해야 한다
     * (`com.bts.issue.application.{Create,Update,Clone}IssueRequest` 가 REST DTO 와 **동명**이라
     * simple-name 매칭으로 짜면 엉뚱한 DTO 를 검사하고 초록인 채 아무것도 안 지킨다).
     */
    @Test
    fun `C1c 수정 요청 스키마에 파생 속성이 안 새고 componentIds 가 optional 이다`() {
        val specResult =
            mockMvc
                .perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk)
                .andReturn()
        val specTree = objectMapper.readTree(specResult.response.contentAsString)
        val schemas = specTree.path("components").path("schemas")

        // 비-공허 짝 — 스키마를 실제로 읽었는지 먼저 못박는다.
        // 이름이 틀리면 빈 노드가 돌아와 아래 단언이 전부 조용히 통과한다.
        val updateReq = schemas.path("UpdateIssueRequest")
        val updateProps = updateReq.path("properties")
        assertThat(updateProps.has("labels"))
            .withFailMessage("UpdateIssueRequest 스키마를 못 읽었다 — 스키마 이름이 바뀌었는지 확인하라.")
            .isTrue()

        // 축 1 — 검증 전용 파생 속성이 요청 스키마에 노출되면 안 된다.
        assertThat(updateProps.has("labelsValid"))
            .withFailMessage(
                "검증 전용 파생 속성 'labelsValid' 가 UpdateIssueRequest 스키마에 노출됐습니다. " +
                    "@get:JsonIgnore 가 빠졌는지 확인하라.",
            )
            .isFalse()

        // 축 2 — 기본값이 있는 componentIds 는 optional 이어야 한다.
        val createReq = schemas.path("CreateIssueRequest")
        val createRequired = createReq.path("required").map { it.asText() }
        assertThat(createReq.path("properties").has("componentIds"))
            .withFailMessage("CreateIssueRequest 스키마를 못 읽었다 (비-공허 짝).")
            .isTrue()
        assertThat(createRequired)
            .withFailMessage(
                "'componentIds' 는 기본값(emptyList)이 있어 optional 이어야 하지만 " +
                    "required=$createRequired 에 포함됐습니다.",
            )
            .doesNotContain("componentIds")

        // ★대조군 — 진짜 필수인 필드는 계속 required 여야 한다.
        // 이게 없으면 「required 배열을 통째로 비우는」 변경으로도 위 단언이 통과한다.
        assertThat(createRequired)
            .withFailMessage("projectKey/summary 는 실제로 필수다. required=$createRequired")
            .contains("projectKey", "summary")
    }

    // ── C2. 이슈 단건 응답 data 래퍼 검증 ───────────────────────────────────

    /**
     * C2. 이슈 단건 GET 응답이 data 래퍼를 포함하고, data.key 가 요청한 이슈 키와 일치한다.
     *
     * DataResponse<IssueResponse> 형태가 regression 없이 유지되고 있음을 실 응답으로 실증.
     */
    @Test
    fun `C2 이슈 단건 응답이 data 래퍼를 포함하고 스펙과 일치한다`() {
        val issueKey = insertIssue("C2 단건 계약 이슈")

        val result =
            mockMvc
                .perform(
                    get("/api/v1/issues/$issueKey")
                        .with(user(ACTOR_UUID.toString()).roles("USER")),
                ).andExpect(status().isOk)
                .andReturn()

        val tree = objectMapper.readTree(result.response.contentAsString)
        val preview = result.response.contentAsString.take(300)

        assertThat(tree.has("data"))
            .withFailMessage("단건 응답에 'data' 래퍼가 없습니다. 응답 앞 300자: $preview")
            .isTrue()
        assertThat(tree.path("data").isObject)
            .withFailMessage("단건 응답 'data' 가 object 가 아닙니다.")
            .isTrue()
        assertThat(tree.path("data").path("key").asText())
            .withFailMessage("단건 응답 'data.key' 가 시드 키 '$issueKey' 와 다릅니다.")
            .isEqualTo(issueKey)
    }

    // ── C3. 위변조 cursor 에러 응답 ProblemDetail 검증 ───────────────────────

    /**
     * C3. 위변조 cursor 전달 시 400 + RFC 7807 ProblemDetail 형식 + errorCode=ISSUE_INVALID_CURSOR.
     *
     * ISSUE_INVALID_CURSOR 에러 코드가 스펙(IssueErrorCodes)과 실제 응답 모두에서 일치함을 실증.
     * 내부 토큰 값이 에러 응답에 노출되지 않음도 검증한다 (보안 회귀 가드).
     */
    @Test
    fun `C3 위변조 cursor 에러 응답이 RFC 7807 ProblemDetail 스키마와 일치하고 errorCode=ISSUE_INVALID_CURSOR 이다`() {
        val result =
            mockMvc
                .perform(
                    get("/api/v1/issues")
                        .param("cursor", "GARBAGE_INVALID_TOKEN")
                        .param("projectKey", PROJECT_KEY)
                        .with(user(ACTOR_UUID.toString()).roles("USER")),
                ).andExpect(status().isBadRequest)
                .andReturn()

        val tree = objectMapper.readTree(result.response.contentAsString)
        val preview = result.response.contentAsString.take(400)

        assertThat(tree.has("type"))
            .withFailMessage("ProblemDetail 에 'type' 필드가 없습니다. 응답 앞 400자: $preview")
            .isTrue()
        assertThat(tree.has("title"))
            .withFailMessage("ProblemDetail 에 'title' 필드가 없습니다. 응답 앞 400자: $preview")
            .isTrue()
        assertThat(tree.has("status"))
            .withFailMessage("ProblemDetail 에 'status' 필드가 없습니다. 응답 앞 400자: $preview")
            .isTrue()
        assertThat(tree.path("status").asInt())
            .withFailMessage("ProblemDetail.status 가 400 이 아닙니다.")
            .isEqualTo(400)
        assertThat(tree.has("errorCode"))
            .withFailMessage("ProblemDetail 에 'errorCode' 커스텀 필드가 없습니다.")
            .isTrue()
        assertThat(tree.path("errorCode").asText())
            .withFailMessage("errorCode 가 'ISSUE_INVALID_CURSOR' 가 아닙니다.")
            .isEqualTo("ISSUE_INVALID_CURSOR")

        // 보안: 내부 cursor 토큰 원문이 에러 응답에 노출되지 않아야 한다
        assertThat(result.response.contentAsString)
            .withFailMessage("에러 응답에 내부 cursor 토큰 원문이 노출되었습니다.")
            .doesNotContain("GARBAGE_INVALID_TOKEN")
    }

    // ── T4. cursor end-to-end 순회 — 중복 0 · 누락 0 ───────────────────────

    /**
     * T4. 이슈 7건 seed → limit=3 cursor 순회 → 전체 7건 정확히 1번씩 등장.
     *
     * ## 핵심 검증
     * - 중복 없음: 순회 중 같은 키가 두 번 등장하면 즉시 fail.
     * - 누락 없음: 최종 수집 집합 == 시드 집합.
     * - 마지막 페이지: [traverseCursor] 내부에서 next=null 을 만나 루프 종료.
     *
     * ## CursorCodec 타임존 round-trip 실증
     * CursorCodec.encode(Instant/OffsetDateTime UTC) → 토큰 →
     * repo keyset seek (OffsetDateTime AT TIME ZONE 'UTC') 간 타임존 불일치가 있으면
     * 중복 또는 누락이 발생해 이 테스트가 RED 로 잡는다.
     */
    @Test
    fun `T4 cursor end-to-end 순회 이슈 7건 limit=3 전체 순회 중복0 누락0 마지막페이지 next=null`() {
        val seededKeys = (1..7).map { i -> insertIssue("T4 순회 이슈 $i") }.toSet()

        val collectedKeys = traverseCursor(limit = 3)

        assertThat(collectedKeys)
            .withFailMessage("수집된 이슈 수(${collectedKeys.size})가 시드 수(7)와 다릅니다.")
            .hasSize(7)
        assertThat(collectedKeys)
            .withFailMessage("수집된 키 집합이 시드 키 집합과 다릅니다. 수집: $collectedKeys, 시드: $seededKeys")
            .containsExactlyInAnyOrderElementsOf(seededKeys)
    }

    // ── T5. created_at 동률 tie-break 안정성 ─────────────────────────────────

    /**
     * T5. created_at 동률 이슈 포함 cursor 순회 — tie-break 안정성 실증.
     *
     * ## 시나리오
     * 2건을 같은 트랜잭션에 INSERT → NOW() = 트랜잭션 시작 시각으로 두 이슈의 created_at 동일.
     * 나머지 2건은 별도 트랜잭션 → 서로 다른 created_at.
     * limit=1 으로 단건씩 순회 → tie-break 경계(동률 두 이슈)를 반드시 통과.
     *
     * ## keyset seek tie-break 규칙
     * ORDER BY created_at DESC, id DESC 에서 동률 시 id DESC 로 결정.
     * WHERE (created_at, id) < (:c, :id) 가 두 이슈를 각각 정확히 1번씩 반환해야 한다.
     */
    @Test
    fun `T5 created_at 동률 이슈 포함 limit=1 cursor 순회로 tie-break 안정성을 실증한다`() {
        // 동일 트랜잭션에 2건 삽입 → 동일 created_at (NOW() = 트랜잭션 시작 시각)
        val (tieKey1, tieKey2) = insertTwoIssuesSameTimestamp("T5 동률 이슈 A", "T5 동률 이슈 B")
        val extra1 = insertIssue("T5 별도 이슈 1")
        val extra2 = insertIssue("T5 별도 이슈 2")

        val seededKeys = setOf(tieKey1, tieKey2, extra1, extra2)

        val collectedKeys = traverseCursor(limit = 1)

        assertThat(collectedKeys)
            .withFailMessage(
                "created_at 동률 포함 4건 순회 실패. 수집: $collectedKeys, 기대: $seededKeys",
            ).containsExactlyInAnyOrderElementsOf(seededKeys)
    }

    // ── T6. cursor 모드 vs offset 모드 교차 검증 ────────────────────────────

    /**
     * T6. cursor 모드 순회 결과 집합과 offset 모드 결과 집합이 동일하다.
     *
     * cursor 모드 도입 후 offset 모드 응답에 regression 이 없음을 교차 검증한다.
     * 순서는 다를 수 있으나(cursor=DESC, offset=DESC) 집합은 동일해야 한다.
     */
    @Test
    fun `T6 cursor 모드와 offset 모드가 같은 이슈 집합을 반환한다`() {
        val count = 5
        (1..count).forEach { i -> insertIssue("T6 교차검증 이슈 $i") }

        val cursorKeys = traverseCursor(limit = 2)

        // offset 모드 단일 페이지
        val offsetResult =
            mockMvc
                .perform(
                    get("/api/v1/issues")
                        .param("projectKey", PROJECT_KEY)
                        .param("page", "0")
                        .param("size", count.toString())
                        .with(user(ACTOR_UUID.toString()).roles("USER")),
                ).andExpect(status().isOk)
                .andReturn()

        val offsetTree = objectMapper.readTree(offsetResult.response.contentAsString)
        val offsetKeys = mutableSetOf<String>()
        offsetTree.path("content").forEach { item -> offsetKeys.add(item.path("key").asText()) }

        assertThat(cursorKeys)
            .withFailMessage(
                "cursor 모드와 offset 모드의 이슈 집합이 다릅니다. " +
                    "cursor=${cursorKeys.size}건: $cursorKeys, " +
                    "offset=${offsetKeys.size}건: $offsetKeys",
            ).containsExactlyInAnyOrderElementsOf(offsetKeys)
    }

    // ── cursor 순회 헬퍼 ───────────────────────────────────────────────────

    /**
     * cursor 모드로 전체 페이지를 끝까지 순회하고 수집된 이슈 키 집합을 반환한다.
     *
     * 순회 중 중복 이슈 키가 발견되면 즉시 [IllegalArgumentException] 을 던진다.
     * `meta.page.next=null` 인 마지막 페이지에서 순회를 종료한다.
     *
     * @param limit cursor 모드 limit 파라미터 (페이지당 최대 건수).
     * @return 순회에서 수집된 이슈 키 집합. 중복 포함 시 예외로 이미 실패한다.
     */
    private fun traverseCursor(limit: Int): Set<String> {
        val keys = mutableSetOf<String>()
        var cursor: String? = ""
        while (cursor != null) {
            val result =
                mockMvc
                    .perform(
                        get("/api/v1/issues")
                            .param("cursor", cursor)
                            .param("projectKey", PROJECT_KEY)
                            .param("limit", limit.toString())
                            .with(user(ACTOR_UUID.toString()).roles("USER")),
                    ).andExpect(status().isOk)
                    .andReturn()

            val tree = objectMapper.readTree(result.response.contentAsString)
            tree.path("data").forEach { item ->
                val key = item.path("key").asText()
                require(keys.add(key)) { "cursor 순회 중 중복 이슈 키 발견: '$key'. 현재 수집: $keys" }
            }
            val nextNode = tree.path("meta").path("page").path("next")
            cursor = if (nextNode.isNull || nextNode.isMissingNode) null else nextNode.asText()
        }
        return keys
    }

    // ── DB 헬퍼 ────────────────────────────────────────────────────────────

    private fun seedProject() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Contract Test Project")
                stmt.executeUpdate()
            }
        }
    }

    private fun cleanIssues() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                // 이 테스트는 raw INSERT 만 사용하므로 changelog/rank 관련 행이 없어 issues 직접 삭제 가능
                val deleteIssuesSql =
                    "DELETE FROM issues WHERE project_id = " +
                        "(SELECT id FROM projects WHERE key = '$PROJECT_KEY')"
                stmt.execute(deleteIssuesSql)
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    /**
     * 이슈 1건을 독립 트랜잭션으로 삽입하고 이슈 키를 반환한다.
     *
     * 별도 트랜잭션이므로 created_at 은 이 호출 시점의 NOW() 를 받는다.
     * 연속 호출 시 각 이슈는 서로 다른 created_at 을 가진다 (PostgreSQL 마이크로초 정밀도).
     */
    private fun insertIssue(summary: String): String =
        conn().use { c ->
            c.autoCommit = false
            val seq = incrementProjectSequence(c)
            val issueKey = "$PROJECT_KEY-$seq"
            val projectId = fetchProjectId(c)
            val taskTypeId = fetchTaskTypeId(c)

            c.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                    "VALUES (?, ?, ?, ?, 'open', 1, ?)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, summary)
                stmt.setObject(4, ACTOR_UUID)
                stmt.setLong(5, taskTypeId)
                stmt.executeUpdate()
            }
            c.commit()
            issueKey
        }

    /**
     * 이슈 2건을 같은 트랜잭션으로 삽입해 동일한 created_at 을 보장한다.
     *
     * PostgreSQL 의 NOW() 는 트랜잭션 시작 시각이므로 같은 트랜잭션 내 삽입은 identical created_at.
     * 이를 이용해 tie-break(id DESC) 검증 환경을 조성한다.
     *
     * @return (key1, key2) — 삽입 순서대로의 이슈 키 쌍.
     */
    private fun insertTwoIssuesSameTimestamp(
        summary1: String,
        summary2: String,
    ): Pair<String, String> =
        conn().use { c ->
            c.autoCommit = false
            val projectId = fetchProjectId(c)
            val taskTypeId = fetchTaskTypeId(c)

            val seq1 = incrementProjectSequence(c)
            val key1 = "$PROJECT_KEY-$seq1"
            val seq2 = incrementProjectSequence(c)
            val key2 = "$PROJECT_KEY-$seq2"

            listOf(key1 to summary1, key2 to summary2).forEach { (key, summary) ->
                c.prepareStatement(
                    "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                        "VALUES (?, ?, ?, ?, 'open', 1, ?)",
                ).use { stmt ->
                    stmt.setString(1, key)
                    stmt.setObject(2, projectId)
                    stmt.setString(3, summary)
                    stmt.setObject(4, ACTOR_UUID)
                    stmt.setLong(5, taskTypeId)
                    stmt.executeUpdate()
                }
            }
            c.commit()
            Pair(key1, key2)
        }

    private fun incrementProjectSequence(c: Connection): Long =
        c.prepareStatement(
            "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
        ).use { stmt ->
            stmt.setString(1, PROJECT_KEY)
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "key_sequence 증가 실패: 프로젝트 $PROJECT_KEY 가 없습니다." }
                rs.getLong(1)
            }
        }

    private fun fetchProjectId(c: Connection): UUID =
        c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
            stmt.setString(1, PROJECT_KEY)
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "프로젝트 $PROJECT_KEY 가 없습니다 — seedProject() 를 먼저 호출하세요." }
                UUID.fromString(rs.getString(1))
            }
        }

    private fun fetchTaskTypeId(c: Connection): Long =
        c.prepareStatement(
            "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "task 타입 없음 — V003 마이그레이션 확인 필요." }
                rs.getLong(1)
            }
        }

    private fun conn(): Connection = dataSource.connection
}
