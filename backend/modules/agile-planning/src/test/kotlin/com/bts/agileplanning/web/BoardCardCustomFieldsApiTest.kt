// 보드 조회 응답 카드가 이슈 커스텀 필드를 그대로 미러하는지 고정하는 HTTP 슬라이스 테스트 (부채 177 Task 26)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.BoardApplicationService
import com.bts.agileplanning.application.BoardPlacementResult
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.BoardType
import com.bts.agileplanning.domain.PlacedColumn
import com.bts.agileplanning.repository.BoardRepository
import com.bts.shared.board.BoardCardFilter
import com.bts.shared.board.BoardIssueView
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Instant
import java.util.UUID

/**
 * 보드 단건 조회 응답의 카드 `customFields` 미러 계약 테스트 (부채 177 Task 26).
 *
 * ### 무엇을 재나
 *
 * [BoardIssueView.customFields] 로 들어온 값이 `GET /api/v1/boards/{id}` 응답 카드에
 * **그대로** 실리는지를 JSON 수준에서 본다. `BoardCardResponse` 를 직접 만들지 않고 HTTP 응답을
 * 파싱하는 이유는, 필드가 없을 때 컴파일 에러가 아니라 **어서션 실패**로 red 를 내기 위해서다.
 *
 * ### 왜 「그대로」인가 — 다시 거르지 않는다
 *
 * 마스킹 주체는 issue-tracking 의 `BoardIssueLookupAdapter` **하나**다(Task 25). 포트 밖으로는
 * 이미 열람 권한을 통과한 값만 나온다. agile-planning 이 여기서 한 번 더 거르면 마스킹 주체가
 * 둘이 되고 그것이 곧 두 번째 진실이다 — 그래서 이 테스트는 부분집합이 아니라 **맵 전체 동등**으로
 * 단언한다. 방어적 재필터가 들어오면(키 allowlist·민감해 보이는 키 제거 등) 즉시 red 가 된다.
 *
 * ### 공허 방지 — 세 축을 함께 잰다
 *
 * - **대조군**. 커스텀 필드가 **있는** 카드와 **없는** 카드를 같은 응답에 둔다. 「빈 맵이다」만
 *   재면 전부 비우는 구현도 통과한다.
 * - **카드별 대조**. 서로 다른 값을 가진 이슈 3건을 두 컬럼에 나눠 담아 카드마다 제 값이
 *   실렸는지 본다. 한 이슈의 값이 모든 카드에 복사되는 구현을 잡는다.
 * - **키 존재**. 빈 맵인 카드도 `customFields` 키 자체는 응답에 있어야 한다(프론트가 키 부재와
 *   빈 맵을 구분하지 않아도 되도록 — `BoardControllerIntegrationTest` NULL-1 과 같은 축).
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [BoardCardCustomFieldsApiTest.TestMvcConfig::class])
@WebAppConfiguration
class BoardCardCustomFieldsApiTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * 권한 게이트는 allow-all 로 고정한다 — 이 테스트가 재는 것은 권한이 아니라 **미러**이고,
     * 커스텀 필드의 권한 판정은 이 BC 밖(어댑터)에 있다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig : WebMvcConfigurer {
        @Bean
        open fun boardApplicationService(): BoardApplicationService = mockk(relaxed = true)

        @Bean
        open fun boardRepository(): BoardRepository = mockk(relaxed = true)

        @Bean
        open fun permissionResolver(): IssuePermissionResolver =
            object : IssuePermissionResolver {
                override fun hasPermission(
                    actorId: UUID,
                    permission: IssuePermission,
                    scope: IssueScope,
                ): Boolean = true
            }

        @Bean
        open fun boardController(
            service: BoardApplicationService,
            repository: BoardRepository,
            permissionResolver: IssuePermissionResolver,
        ): BoardController = BoardController(service, repository, permissionResolver)

        @Bean
        open fun boardExceptionHandler(): BoardExceptionHandler = BoardExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var boardApplicationService: BoardApplicationService

    @Autowired
    lateinit var boardRepository: BoardRepository

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val actorId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        clearMocks(boardApplicationService, boardRepository)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    // ── CF-1. 대조군 — 있는 카드는 값이, 없는 카드는 빈 맵이 실린다 ──────────────

    @Test
    fun `GET boards id 커스텀 필드가 있는 카드는 값이 실리고 없는 카드는 빈 맵이다`() {
        val columns =
            fetchColumns(
                listOf(
                    listOf(
                        card("BTS-1", mapOf("story_points" to 5, "예산 메모" to "초과")),
                        // 커스텀 필드 인자를 아예 주지 않는다 — 포트 기본값(빈 맵) 그대로가 대조군이다.
                        cardWithoutCustomFields("BTS-2"),
                    ),
                ),
            )

        val withFields = cardNode(columns, columnIndex = 0, cardIndex = 0)
        val withoutFields = cardNode(columns, columnIndex = 0, cardIndex = 1)
        assertThat(withFields.path("issueKey").asText()).isEqualTo("BTS-1")
        assertThat(withoutFields.path("issueKey").asText()).isEqualTo("BTS-2")

        assertThat(customFieldsOf(withFields))
            .describedAs("BTS-1 카드는 포트가 준 커스텀 필드를 그대로 싣는다")
            .isEqualTo(mapOf("story_points" to 5, "예산 메모" to "초과"))
        assertThat(customFieldsOf(withoutFields))
            .describedAs("커스텀 필드가 없는 BTS-2 는 빈 맵이다")
            .isEqualTo(emptyMap<String, Any?>())

        // 빈 맵이어도 키 자체는 응답에 있어야 한다(키 부재와 빈 맵이 갈리지 않도록).
        assertThat(withoutFields.fieldNames().asSequence().toList())
            .describedAs("BTS-2 카드 응답 키 집합")
            .contains("customFields")
    }

    // ── CF-2. 카드별 대조 — 한 이슈의 값이 다른 카드로 새지 않는다 ────────────────

    @Test
    fun `GET boards id 카드마다 제 커스텀 필드가 실리고 다른 카드로 새지 않는다`() {
        val columns =
            fetchColumns(
                listOf(
                    listOf(
                        card("BTS-1", mapOf("story_points" to 5, "팀" to "플랫폼")),
                        card("BTS-2", mapOf("story_points" to 13)),
                    ),
                    // 다른 컬럼의 카드도 제 값을 받는지 함께 본다(컬럼 단위 복사 방지).
                    listOf(card("BTS-3", mapOf("팀" to "코어"))),
                ),
            )

        val first = cardNode(columns, columnIndex = 0, cardIndex = 0)
        val second = cardNode(columns, columnIndex = 0, cardIndex = 1)
        val third = cardNode(columns, columnIndex = 1, cardIndex = 0)
        assertThat(first.path("issueKey").asText()).isEqualTo("BTS-1")
        assertThat(second.path("issueKey").asText()).isEqualTo("BTS-2")
        assertThat(third.path("issueKey").asText()).isEqualTo("BTS-3")

        assertThat(customFieldsOf(first))
            .isEqualTo(mapOf("story_points" to 5, "팀" to "플랫폼"))
        assertThat(customFieldsOf(second))
            .describedAs("BTS-2 는 자기 값만 갖는다 — BTS-1 의 `팀` 이 새어 들어오면 red")
            .isEqualTo(mapOf("story_points" to 13))
        assertThat(customFieldsOf(third))
            .describedAs("다른 컬럼의 BTS-3 도 제 값만 갖는다")
            .isEqualTo(mapOf("팀" to "코어"))
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    private fun sampleBoard(): Board =
        Board(
            id = UUID.randomUUID(),
            projectKey = "BTS",
            name = "BTS 개발 보드",
            boardType = BoardType.KANBAN,
            columns =
                listOf(
                    BoardColumn(UUID.randomUUID(), listOf("open"), "열림", "TODO", 0),
                    BoardColumn(UUID.randomUUID(), listOf("in-progress"), "진행 중", "IN_PROGRESS", 1),
                ),
            createdAt = Instant.parse("2026-09-05T00:00:00Z"),
            updatedAt = Instant.parse("2026-09-05T00:00:00Z"),
        )

    private fun card(
        key: String,
        customFields: Map<String, Any?>,
    ): BoardIssueView = cardWithoutCustomFields(key).copy(customFields = customFields)

    private fun cardWithoutCustomFields(key: String): BoardIssueView =
        BoardIssueView(
            key = key,
            summary = "$key 요약",
            currentStateKey = "open",
            assigneeId = null,
            priority = 1,
            version = 1L,
            typeKey = "task",
        )

    /**
     * 컬럼별 카드를 배치한 보드를 조회해 응답의 `data.columns` 노드를 돌려준다.
     *
     * @param cardsByColumn 컬럼 순서대로의 카드 목록. 원소가 없는 컬럼은 빈 목록으로 채운다.
     */
    private fun fetchColumns(cardsByColumn: List<List<BoardIssueView>>): JsonNode {
        val board = sampleBoard()
        every { boardRepository.findById(board.id) } returns board
        every { boardApplicationService.getBoard(board.id, actorId, BoardCardFilter.EMPTY) } returns
            BoardPlacementResult(
                columns =
                    board.columns.mapIndexed { index, column ->
                        PlacedColumn(column = column, cards = cardsByColumn.getOrElse(index) { emptyList() })
                    },
                truncated = false,
                unplacedCount = 0,
            )

        val body =
            mockMvc.perform(get("/api/v1/boards/${board.id}").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .getContentAsString(Charsets.UTF_8)
        return mapper.readTree(body).path("data").path("columns")
    }

    private fun cardNode(
        columns: JsonNode,
        columnIndex: Int,
        cardIndex: Int,
    ): JsonNode = columns.path(columnIndex).path("cards").path(cardIndex)

    /** 카드 노드의 `customFields` 를 맵으로 읽는다. 키가 없으면 null 을 돌려줘 어서션이 그 사실을 말하게 한다. */
    private fun customFieldsOf(card: JsonNode): Map<String, Any?>? =
        card.get("customFields")?.let { mapper.convertValue(it, object : TypeReference<Map<String, Any?>>() {}) }
}
