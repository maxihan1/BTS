// 상세 보기 필드 PATCH 의 실 DB end-to-end — 잘못된 필드 키가 500 이 아니라 400 으로 나가는지 잰다 (리뷰 C3·C7)

package com.bts.agileplanning.integration

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.repository.BoardRepository
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.util.UUID

/** 컨트롤러 경로. */
private const val PATH = "/api/v1/boards/{boardId}/detail-view-fields"

/** `board_detail_view_fields.field_key` 의 선언 길이(V509 ④). 이 경계를 넘기면 SQLSTATE 22001 이다. */
private const val FIELD_KEY_MAX = 128

/**
 * 상세 보기 필드 PATCH 를 **실 PostgreSQL 위에서** 끝까지 태우는 통합 테스트 (리뷰 CONCERN C3 · C7).
 *
 * ## 왜 슬라이스로는 못 잡는가 — 이 파일의 존재 이유
 * [com.bts.agileplanning.web.BoardDetailViewApiTest] 는 in-memory `FakeSettingsStore` 를 쓴다.
 * 그 가짜 저장소는 **어떤 문자열도 받아들이므로** `["EPIC", null]` 이나 129자 키를 넣어도
 * 조용히 200 이 된다 — 실제로 죽는 곳은 `field_key` 의 `NOT NULL` 과 `VARCHAR(128)` 이다.
 * 즉 슬라이스 단독으로는 「사용자가 500 을 받는다」를 **재현할 수 없고**, 그래서 이 결함이 살아남았다.
 *
 * 이 파일은 컨트롤러 → 서비스 → 리포지터리 → **실 DB** 를 그대로 태워 그 간극을 닫는다.
 * red 단계 실측(2026-09-06) — 두 요청 모두 `500` + `AGILE_INTERNAL_ERROR` 였다.
 *
 * ## 축
 *
 * | 축 | 무엇 | 느슨한 구현 ↔ 올바른 구현 |
 * |---|---|---|
 * | ① 원소 null | `["summary", null]` 이 **400** | DB NOT NULL 위반 500 ↔ 사전 400 |
 * | ② 길이 초과 | 129자 키가 **400** | SQLSTATE 22001 → 500 ↔ 사전 400 |
 * | ③ 사유 도달 | 400 본문의 `detail` 이 **왜 틀렸는지**를 말한다 | 일반 문구 ↔ 사유 |
 * | ④ 대조군 | 카탈로그에 **없는** 키는 실제로 저장된다 | 카탈로그로 좁혀 막는 구현 ↔ 원문 보존 |
 *
 * ★**④ 가 ①②③ 의 짝이다.** 원소 위생을 「아는 키만 허용」으로 넓히면 ④ 가 red 가 된다 —
 * 모르는 키를 살려 두는 것이 의도된 계약이기 때문이다(`DetailViewPanel.tsx:66`
 * *「모르는 키는 숨기지 않고 원문 그대로 그린다」*).
 *
 * ## 권한·인증
 * [AgilePlanningTestcontainersConfig] 의 `IssuePermissionResolver` 는 allow-all 이다 —
 * 이 파일은 권한 축을 재지 않는다(그쪽은 슬라이스 테스트 넷과
 * [com.bts.agileplanning.web.BoardSettingsTabErrorEnvelopeTest] 가 진다).
 */
@SpringBootTest(
    classes = [AgilePlanningTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(AgilePlanningTestcontainersConfig::class)
@ActiveProfiles("test")
// detekt VarCouldBeVal 오탐 — 필드 주입은 `lateinit var` 뿐이고 `lateinit` 은 val 에 못 쓴다.
@Suppress("VarCouldBeVal")
class BoardDetailViewWriteIntegrationTest {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var boardRepository: BoardRepository

    private lateinit var mockMvc: MockMvc
    private lateinit var boardId: UUID

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                UUID.randomUUID().toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        boardId = insertBoard().id
    }

    // ── ① 원소 null ───────────────────────────────────────────────────────────

    @Test
    fun `배열 원소의 null 은 400 이다 — DB NOT NULL 위반이 500 으로 새지 않는다`() {
        val result = patchRaw("""{"groups":{"GENERAL":["summary",null]}}""")

        assertThat(result.response.status)
            .describedAs("응답 본문: %s", bodyOf(result))
            .isEqualTo(400)
        // 사전 검증이 막았다면 저장 계층에 아무것도 가지 않았다 — 그룹은 여전히 비어 있다.
        assertThat(groupsOf(performGet())["GENERAL"]).isEmpty()
    }

    // ── ② 길이 초과 ───────────────────────────────────────────────────────────

    @Test
    fun `128자를 넘는 필드 키는 400 이다 — SQLSTATE 22001 이 500 으로 새지 않는다`() {
        val result = patchRaw("""{"groups":{"GENERAL":["${"k".repeat(FIELD_KEY_MAX + 1)}"]}}""")

        assertThat(result.response.status)
            .describedAs("응답 본문: %s", bodyOf(result))
            .isEqualTo(400)
        assertThat(groupsOf(performGet())["GENERAL"]).isEmpty()
    }

    // ── ③ 사유 도달 ───────────────────────────────────────────────────────────

    @Test
    fun `400 본문은 무엇이 잘못됐는지를 사용자에게 알린다`() {
        val result = patchRaw("""{"groups":{"GENERAL":["summary","   "]}}""")

        assertThat(result.response.status).isEqualTo(400)
        val detail = mapper.readTree(bodyOf(result)).path("detail").asText()
        assertThat(detail)
            .describedAs("사전 검증의 존재 이유가 「사용자에게 400 의 이유를 주는 것」이다")
            .isNotEqualTo("요청 값이 올바르지 않습니다.")
        assertThat(detail).contains("필드 키")
    }

    // ── ④ 대조군 — 모르는 키는 살린다 ─────────────────────────────────────────

    @Test
    fun `카탈로그에 없는 키도 실제 DB 에 저장된다`() {
        val result = patchRaw("""{"groups":{"GENERAL":["cf_story_points","아직-모르는-키"]}}""")

        assertThat(result.response.status)
            .describedAs("응답 본문: %s", bodyOf(result))
            .isEqualTo(200)
        assertThat(groupsOf(performGet())["GENERAL"])
            .containsExactly("cf_story_points", "아직-모르는-키")
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun patchRaw(body: String): MvcResult =
        mockMvc.perform(
            patch(PATH, boardId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andReturn()

    private fun performGet(): MvcResult = mockMvc.perform(get(PATH, boardId)).andReturn()

    /**
     * 응답 바이트를 UTF-8 로 직접 읽는다.
     *
     * `application/problem+json` 에는 charset 파라미터가 없어 `contentAsString` 이 ISO-8859-1 로
     * 읽어 한글이 깨진다(형제 `BoardSettingsTabErrorEnvelopeTest` 가 같은 자리를 적었다).
     */
    private fun bodyOf(result: MvcResult): String = String(result.response.contentAsByteArray, Charsets.UTF_8)

    private fun groupsOf(result: MvcResult): Map<String, List<String>> =
        mapper.convertValue(
            mapper.readTree(bodyOf(result)).path("data").path("groups"),
            object : TypeReference<LinkedHashMap<String, List<String>>>() {},
        )

    private fun insertBoard(): Board =
        boardRepository.insert(
            Board(
                id = UUID.randomUUID(),
                projectKey = "DVW${UUID.randomUUID().toString().take(5).uppercase()}",
                name = "상세 보기 PATCH 통합 테스트 보드",
                columns = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )
}
