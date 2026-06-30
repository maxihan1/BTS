// AQL 검색 응답 envelope 계약 테스트 — DTO 직렬화 구조가 클라이언트 API 계약과 일치하는지 검증 (FR-API-02 Task 3)

package com.bts.search.integration

import com.bts.search.config.BEARER_AUTH_SCHEME
import com.bts.search.web.SearchController
import com.bts.search.web.dto.AqlSearchHit
import com.bts.search.web.dto.AqlSearchPageResponse
import com.bts.search.web.dto.AqlSearchRequest
import com.bts.search.web.dto.PageInfo
import com.bts.search.web.dto.PageMeta
import com.bts.search.web.dto.toAqlEnvelope
import com.bts.shared.search.IssueSearchHit
import com.bts.shared.search.IssueSearchPage
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * AQL 검색 응답 envelope 계약 테스트 (FR-API-02 Task 3).
 *
 * ## 목적
 *
 * 클라이언트가 기대하는 응답 계약이 실제 envelope DTO 구조와 일치하는지 검증한다.
 * [AqlSearchPageResponse]가
 * `{ "data": [...], "meta": { "page": { "number", "size", "totalElements", "totalPages" } } }`
 * 형식으로 직렬화되는지 Jackson 직렬화와 Java/Kotlin Reflection으로 확인한다.
 *
 * ## [com.bts.search.config.OpenApiAnnotationTest]와의 구분
 *
 * Task 2 [com.bts.search.config.OpenApiAnnotationTest]는 springdoc 어노테이션 존재 여부와
 * `components/schemas/AqlSearchPageResponse`의 `data`·`meta` 키 등록을 검증한다.
 * 본 테스트는 **DTO 직렬화 구조**(data 배열·meta.page.* 중첩 필드)와
 * **보안 어노테이션 계약**([SecurityRequirement] bearerAuth 선언)에 집중한다.
 *
 * Spring Boot 컨텍스트·Testcontainers 없이 순수 Jackson + Java/Kotlin Reflection으로 동작하여
 * 빠른 피드백을 제공한다.
 *
 * ## 검증 시나리오
 *
 * - C1. [AqlSearchPageResponse] 직렬화 — `data`(배열)·`meta.page.number/size/totalElements/totalPages`
 * - C2. 빈 결과 직렬화 — `data`가 null이 아닌 빈 배열·`totalElements`=0·`totalPages`=0
 * - C3. [PageInfo] 스키마 필드 계약 — `number`·`size`·`totalElements`·`totalPages` 4개 필드만 보유
 * - C4. bearerAuth 보안 어노테이션 — [SearchController].search()에 @SecurityRequirement(name="bearerAuth") 존재
 * - C5. totalPages 계산 계약 — [com.bts.search.web.dto.toAqlEnvelope]가 ceil(total/requestedSize)로 산출
 */
class SearchOpenApiContractTest {
    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    // ── C1 AqlSearchPageResponse 직렬화 구조 ───────────────────────────────────

    /**
     * Given [AqlSearchPageResponse]에 데이터가 있을 때,
     * When Jackson으로 직렬화하면,
     * Then `$.data`는 배열이고 `$.meta.page.{number,size,totalElements,totalPages}`가 올바른 값을 가진다.
     *
     * [com.bts.search.config.OpenApiAnnotationTest] B4가 스키마 등록을 확인하므로
     * 본 케이스는 실제 직렬화 JSON 구조에 집중한다.
     */
    @Test
    fun `C1 AqlSearchPageResponse가 data meta page 중첩 구조로 직렬화된다`() {
        val response =
            AqlSearchPageResponse(
                data = listOf(sampleHit()),
                meta = PageMeta(page = PageInfo(number = 1, size = 50, totalElements = 120L, totalPages = 3)),
            )

        val tree = mapper.readTree(mapper.writeValueAsString(response))

        assertThat(tree.path("data").isArray)
            .withFailMessage("$.data 가 배열이어야 합니다. 현재 타입: ${tree.path("data").nodeType}")
            .isTrue()
        assertThat(tree.path("data").size()).isEqualTo(1)

        val page = tree.path("meta").path("page")
        assertThat(page.isMissingNode)
            .withFailMessage("$.meta.page 노드가 없습니다. 응답에 meta.page 구조가 포함되어야 합니다.")
            .isFalse()
        assertThat(page.path("number").asInt()).isEqualTo(1)
        assertThat(page.path("size").asInt()).isEqualTo(50)
        assertThat(page.path("totalElements").asLong()).isEqualTo(120L)
        assertThat(page.path("totalPages").asInt()).isEqualTo(3)
    }

    // ── C2 빈 결과 직렬화 ──────────────────────────────────────────────────────

    /**
     * Given 검색 결과가 없을 때,
     * When [AqlSearchPageResponse]를 직렬화하면,
     * Then `$.data`는 null이 아닌 빈 배열이고 `totalElements`=0·`totalPages`=0이다.
     *
     * 클라이언트가 `data == null` 여부를 체크하지 않아도 되도록 빈 배열을 보장한다.
     */
    @Test
    fun `C2 결과 없을 때 data는 null이 아닌 빈 배열 totalElements 0 totalPages 0`() {
        val response =
            AqlSearchPageResponse<AqlSearchHit>(
                data = emptyList(),
                meta = PageMeta(page = PageInfo(number = 0, size = 50, totalElements = 0L, totalPages = 0)),
            )

        val tree = mapper.readTree(mapper.writeValueAsString(response))

        val dataNode = tree.path("data")
        assertThat(dataNode.isArray)
            .withFailMessage("$.data 가 배열이어야 합니다.")
            .isTrue()
        assertThat(dataNode.isNull)
            .withFailMessage("$.data 가 null이면 안 됩니다. 빈 배열 [] 이어야 합니다.")
            .isFalse()
        assertThat(dataNode.size()).isEqualTo(0)

        val page = tree.path("meta").path("page")
        assertThat(page.path("totalElements").asLong()).isEqualTo(0L)
        assertThat(page.path("totalPages").asInt()).isEqualTo(0)
    }

    // ── C3 PageInfo 스키마 필드 계약 ─────────────────────────────────────────────

    /**
     * Given [PageInfo] DTO,
     * When Java Reflection으로 선언된 필드를 조회하면,
     * Then `number`·`size`·`totalElements`·`totalPages` 4개 필드만 존재한다.
     *
     * 필드 추가·삭제 시 이 테스트가 실패하므로 클라이언트와의 API 계약 변경이 명시적으로 드러난다.
     * 변경 시 프론트엔드 Zod 스키마도 함께 갱신해야 한다.
     */
    @Test
    fun `C3 PageInfo 스키마에 number size totalElements totalPages 4개 필드만 있다`() {
        val fields = PageInfo::class.java.declaredFields.map { it.name }.toSet()
        assertThat(fields)
            .withFailMessage(
                "PageInfo 필드가 계약과 다릅니다. " +
                    "추가·삭제 시 클라이언트 계약이 깨지므로 프론트 Zod 스키마도 함께 갱신해야 합니다. " +
                    "현재 필드: $fields",
            )
            .containsExactlyInAnyOrder("number", "size", "totalElements", "totalPages")
    }

    // ── C4 bearerAuth 보안 어노테이션 ────────────────────────────────────────────

    /**
     * Given [SearchController.search] 메서드,
     * When Java Reflection으로 어노테이션을 조회하면,
     * Then [SecurityRequirement]가 `bearerAuth`(= [BEARER_AUTH_SCHEME])로 선언되어 있다.
     *
     * 이 어노테이션이 없으면 springdoc이 bearerAuth security requirement를 OpenAPI 스펙에 포함하지 않아
     * API 클라이언트가 JWT Bearer 토큰 필요 여부를 알 수 없다.
     */
    @Test
    fun `C4 SearchController search 메서드에 bearerAuth SecurityRequirement 어노테이션이 있다`() {
        val searchMethod =
            SearchController::class.java.getDeclaredMethod(
                "search",
                AqlSearchRequest::class.java,
            )
        val securityRequirements = searchMethod.getAnnotationsByType(SecurityRequirement::class.java)

        assertThat(securityRequirements)
            .withFailMessage(
                "SearchController.search()에 @SecurityRequirement 어노테이션이 없습니다. " +
                    "@SecurityRequirement(name=\"$BEARER_AUTH_SCHEME\") 를 추가해야 합니다.",
            )
            .isNotEmpty()

        val hasBearerAuth = securityRequirements.any { it.name == BEARER_AUTH_SCHEME }
        assertThat(hasBearerAuth)
            .withFailMessage(
                "@SecurityRequirement(name=\"$BEARER_AUTH_SCHEME\") 가 없습니다. " +
                    "현재 security requirement names: ${securityRequirements.map { it.name }}",
            )
            .isTrue()
    }

    // ── C5 totalPages 계산 계약 ───────────────────────────────────────────────────

    /**
     * Given [IssueSearchPage]의 total=120, size=50이면,
     * When [com.bts.search.web.dto.toAqlEnvelope]를 호출하면,
     * Then `totalPages = ceil(120 / 50) = 3`이어야 한다.
     *
     * 경계값: total이 size의 배수이면 몫과 동일(`ceil(120 / 60) = 2`).
     */
    @Test
    fun `C5 toAqlEnvelope totalPages는 total을 requestedSize로 나눈 올림값이다`() {
        // 120 / 50 = 2.4 → ceil = 3
        val page120By50 =
            IssueSearchPage(
                items = listOf(sampleHitInner()),
                total = 120L,
                page = 0,
                size = 50,
            )
        val envelope50 = page120By50.toAqlEnvelope(requestedSize = 50) { AqlSearchHit.from(it) }
        assertThat(envelope50.meta.page.totalPages)
            .withFailMessage(
                "total=120, requestedSize=50 → totalPages는 ceil(2.4)=3이어야 합니다. " +
                    "현재: ${envelope50.meta.page.totalPages}",
            )
            .isEqualTo(3)

        // 배수일 때는 몫과 동일
        val page120By60 =
            IssueSearchPage(
                items = listOf(sampleHitInner()),
                total = 120L,
                page = 0,
                size = 60,
            )
        val envelope60 = page120By60.toAqlEnvelope(requestedSize = 60) { AqlSearchHit.from(it) }
        assertThat(envelope60.meta.page.totalPages)
            .withFailMessage(
                "total=120, requestedSize=60 → totalPages는 ceil(2.0)=2이어야 합니다. " +
                    "현재: ${envelope60.meta.page.totalPages}",
            )
            .isEqualTo(2)
    }

    // ── private helpers ──────────────────────────────────────────────────────────

    /** HTTP 응답 DTO 직렬화 테스트용 [AqlSearchHit] 샘플. */
    private fun sampleHit(): AqlSearchHit =
        AqlSearchHit(
            key = "TEST-1",
            summary = "Contract test issue",
            typeKey = "task",
            currentStateKey = "open",
            assigneeId = null,
            priority = 3,
            priorityName = "Medium",
            projectKey = "TEST",
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    /** [toAqlEnvelope] 확장함수 테스트용 shared-kernel [IssueSearchHit] 샘플. */
    private fun sampleHitInner(): IssueSearchHit =
        IssueSearchHit(
            key = "TEST-1",
            summary = "Contract test issue",
            typeKey = "task",
            currentStateKey = "open",
            assigneeId = null,
            priority = 3,
            priorityName = "Medium",
            projectKey = "TEST",
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
}
