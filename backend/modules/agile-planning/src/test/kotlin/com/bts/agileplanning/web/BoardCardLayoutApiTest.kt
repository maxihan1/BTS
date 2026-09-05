// 카드 레이아웃 PATCH API 실 DB 통합 테스트 — 뷰별 저장·400 판정·권한 게이트 (부채 177 Task 8 · J17·J18)

package com.bts.agileplanning.web

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardType
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.repository.BoardSettingsRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.util.UUID

/**
 * `PATCH /api/v1/boards/{boardId}/card-layout` 실 DB end-to-end 테스트 (부채 177 Task 8).
 *
 * [AgilePlanningTestBootApplication] 을 기동해 Testcontainers PostgreSQL 위에서
 * [BoardCardLayoutController] → [com.bts.agileplanning.application.CardLayoutSettingsService] →
 * [BoardSettingsRepository] 전 스택을 잰다. MockK 슬라이스로 재면 「뷰 축」과 「뷰 생존」이
 * **stub 의 약속**을 재는 것이 되어 저장이 실제로 갈렸는지 못 본다.
 *
 * ## 이 클래스가 지는 판정 — 각 축이 무엇과 무엇을 가르나
 *
 * | 축 | 무엇 | 느슨한 구현 ↔ 올바른 구현 |
 * |---|---|---|
 * | ① 뷰 축 | `BOARD`·`BACKLOG` 에 **다른** 구성 | 구성을 한 목록으로 공유 ↔ 뷰별 저장 (J18) |
 * | ② 순서 | 보낸 순서 그대로 되읽힌다 | 키를 정렬/집합화 ↔ 요청 순서 = position |
 * | ③ 뷰 생존 | 한 뷰만 보내면 다른 뷰가 산다 | 미전송 뷰를 빈 목록으로 덮음 ↔ 전송된 뷰만 교체 |
 * | ④ 보드 격리 | 옆 보드가 안 바뀐다 | boardId 를 술어에서 뺌 ↔ 보드별 저장 |
 * | ⑤ 상한 | 4개 → 400, **이전 구성 생존** | 검증 없음(500) · 쓰고 나서 검증 ↔ 쓰기 전 검증 (E3) |
 * | ⑥ 필드 키 | `SUMMARY` 400 ↔ `cf_*` 200 | 아무거나 통과 · 커스텀 전면 거부 ↔ 카탈로그 + `cf_` |
 * | ⑦ 뷰 스코프 | `SPRINT` → 400 | 스코프를 그대로 DB 로 넘김(500) ↔ 서비스가 400 |
 * | ⑧ 칸반 | 칸반 `BACKLOG` 400 ↔ 칸반 `BOARD` 200 | `BACKLOG` 전면 거부 · 칸반 전면 거부 ↔ 조합 판정 |
 * | ⑨ 권한 | 403 · 401 · 404 순서 | 권한을 존재보다 먼저 봄(403 누설) ↔ 존재 → 권한 |
 * | ⑩ 게이트 인자 | `SOFT_DELETE` + `Project` | 아무 권한코드나 통과 ↔ 계획이 지정한 게이트 |
 *
 * ★**①⑥⑧ 은 짝으로만 산다.** 「저장하고 읽으면 같다」·「무엇이든 400」은 틀린 구현도 통과시킨다 —
 * 그래서 ⑥ 은 거부(`SUMMARY`)와 허용(`cf_story_points`)을 한 쌍으로, ⑧ 은 칸반 거부와 칸반 허용을
 * 한 쌍으로 둔다. 어느 한쪽만 두면 「전부 거부」 구현이 초록이다.
 *
 * ★**순서 판정은 여기서 `ORDER BY` 를 재지 않는다.** `BoardSettingsRepositoryTest.readWithSeqScan`
 * KDoc 이 밝힌 대로 PK 인덱스 `(board_id, view_scope, position)` 순서가 곧 position 순이라 리포지터리
 * 층의 `ORDER BY` 유무는 실행 계획에 가린다. 이 파일의 ② 는 그 축이 아니라 **요청 순서가 position
 * 으로 그대로 앉는가**(정렬/집합화하지 않는가)를 잰다 — 그래서 알파벳 역순 `PRIORITY, EPIC` 을 보낸다.
 *
 * ## 응답만으로 재지 않는다
 * 모든 저장 축은 HTTP 응답 **그리고** [BoardSettingsRepository] 로 다시 읽은 DB 값을 함께 단언한다.
 * 응답만 재면 요청을 그대로 되돌려주고(echo) 저장은 안 하는 구현이 통과한다.
 */
@SpringBootTest(
    classes = [AgilePlanningTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(AgilePlanningTestcontainersConfig::class, BoardCardLayoutApiTest.StubConfig::class)
@ActiveProfiles("test")
// detekt VarCouldBeVal 오탐 — 필드 주입은 `lateinit var` 뿐이고 `lateinit` 은 val 에 못 쓴다.
@Suppress("VarCouldBeVal")
class BoardCardLayoutApiTest {
    /** [AgilePlanningTestcontainersConfig] 의 allow-all 권한 stub 을 토글 가능한 것으로 교체한다. */
    @TestConfiguration(proxyBeanMethods = false)
    class StubConfig {
        @Bean
        @Primary
        fun permissionStub(): PermissionStub = PermissionStub()
    }

    /** allow/deny 토글 + 전달 인자 캡처가 가능한 [IssuePermissionResolver] stub. */
    class PermissionStub : IssuePermissionResolver {
        var allowAll: Boolean = true
        var lastPermission: IssuePermission? = null
        var lastScope: IssueScope? = null

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean {
            lastPermission = permission
            lastScope = scope
            return allowAll
        }
    }

    @Autowired
    private lateinit var wac: WebApplicationContext

    @Autowired
    private lateinit var boardRepository: BoardRepository

    @Autowired
    private lateinit var settingsRepository: BoardSettingsRepository

    @Autowired
    private lateinit var permissionStub: PermissionStub

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val actorId: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        permissionStub.allowAll = true
        permissionStub.lastPermission = null
        permissionStub.lastScope = null
    }

    // ── ①② 뷰 축 + 순서 (R3 · J18 · J17) ──────────────────────────────────────

    @Test
    fun `스크럼 보드는 보드 뷰와 백로그 뷰에 서로 다른 구성을 저장한다`() {
        // 일부러 **다른** 구성을 넣는다 — 같은 값을 넣으면 구성을 공유하는 구현도 통과한다(완료 기준 3).
        // BOARD 는 알파벳 역순이라 키를 정렬하는 구현이 여기서 갈린다.
        val board = insertBoard(BoardType.SCRUM)

        patchCardLayout(board.id, mapOf("BOARD" to listOf("PRIORITY", "EPIC"), "BACKLOG" to listOf("ESTIMATE")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.cardLayout.BOARD[0]").value("PRIORITY"))
            .andExpect(jsonPath("$.data.cardLayout.BOARD[1]").value("EPIC"))
            .andExpect(jsonPath("$.data.cardLayout.BACKLOG[0]").value("ESTIMATE"))
            .andExpect(jsonPath("$.data.cardLayout.BACKLOG.length()").value(1))

        val stored = settingsRepository.findCardLayout(board.id)
        assertThat(stored["BOARD"]).containsExactly("PRIORITY", "EPIC")
        assertThat(stored["BACKLOG"]).containsExactly("ESTIMATE")
    }

    // ── ③ 뷰 생존 (R3) ────────────────────────────────────────────────────────

    @Test
    fun `한 뷰만 보내면 보내지 않은 뷰의 구성이 살아남는다`() {
        // 미전송 뷰까지 「빈 목록으로 교체」하는 구현이 여기서 죽는다.
        val board = insertBoard(BoardType.SCRUM)
        patchCardLayout(board.id, mapOf("BOARD" to listOf("EPIC"))).andExpect(status().isOk)

        patchCardLayout(board.id, mapOf("BACKLOG" to listOf("ESTIMATE")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.cardLayout.BOARD[0]").value("EPIC"))

        val stored = settingsRepository.findCardLayout(board.id)
        assertThat(stored["BOARD"]).containsExactly("EPIC")
        assertThat(stored["BACKLOG"]).containsExactly("ESTIMATE")
    }

    // ── ④ 보드 격리 ───────────────────────────────────────────────────────────

    @Test
    fun `한 보드의 저장이 다른 보드의 구성을 건드리지 않는다`() {
        val mine = insertBoard(BoardType.SCRUM)
        val other = insertBoard(BoardType.SCRUM)
        patchCardLayout(other.id, mapOf("BOARD" to listOf("LABELS"))).andExpect(status().isOk)

        patchCardLayout(mine.id, mapOf("BOARD" to listOf("EPIC", "PRIORITY")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.cardLayout.BOARD.length()").value(2))

        assertThat(settingsRepository.findCardLayout(mine.id)["BOARD"]).containsExactly("EPIC", "PRIORITY")
        assertThat(settingsRepository.findCardLayout(other.id)["BOARD"]).containsExactly("LABELS")
    }

    // ── ⑤ 상한 (E3 · J17) ─────────────────────────────────────────────────────

    @Test
    fun `한 뷰에 4개를 보내면 400 이고 이전 구성이 그대로 살아남는다`() {
        // 「쓰고 나서 검증」·「DB CHECK 에 맡겨 500」 구현이 둘 다 여기서 갈린다.
        val board = insertBoard(BoardType.SCRUM)
        patchCardLayout(board.id, mapOf("BOARD" to listOf("EPIC"))).andExpect(status().isOk)

        patchCardLayout(board.id, mapOf("BOARD" to listOf("EPIC", "PRIORITY", "LABELS", "ESTIMATE")))
            .andExpect(status().isBadRequest)

        assertThat(settingsRepository.findCardLayout(board.id)["BOARD"]).containsExactly("EPIC")
    }

    @Test
    fun `한 뷰에 3개까지는 저장된다`() {
        // 상한 짝 — 3개를 막는 구현(off-by-one)이 여기서 죽는다.
        val board = insertBoard(BoardType.SCRUM)

        patchCardLayout(board.id, mapOf("BOARD" to listOf("EPIC", "PRIORITY", "LABELS")))
            .andExpect(status().isOk)

        assertThat(settingsRepository.findCardLayout(board.id)["BOARD"])
            .containsExactly("EPIC", "PRIORITY", "LABELS")
    }

    // ── ⑥ 필드 키 카탈로그 (R2) ───────────────────────────────────────────────

    @Test
    fun `카드에 그릴 수 없는 필드 키는 400 이고 아무것도 저장되지 않는다`() {
        // summary 는 J19 의 1층이라 **토글 대상이 아니다**(R2) — 표준 필드처럼 생겼지만 거부해야 한다.
        val board = insertBoard(BoardType.SCRUM)

        patchCardLayout(board.id, mapOf("BOARD" to listOf("SUMMARY"))).andExpect(status().isBadRequest)
        patchCardLayout(board.id, mapOf("BOARD" to listOf("BOGUS_FIELD"))).andExpect(status().isBadRequest)

        assertThat(settingsRepository.findCardLayout(board.id)).doesNotContainKey("BOARD")
    }

    @Test
    fun `커스텀 필드 키는 저장된다`() {
        // ⑥ 의 짝 — 「카탈로그에 없으면 전부 거부」 구현이 여기서 죽는다. 커스텀 필드가 후보의 절반이다(R2).
        val board = insertBoard(BoardType.SCRUM)

        patchCardLayout(board.id, mapOf("BOARD" to listOf("cf_story_points", "EPIC")))
            .andExpect(status().isOk)

        assertThat(settingsRepository.findCardLayout(board.id)["BOARD"])
            .containsExactly("cf_story_points", "EPIC")
    }

    // ── ⑦ 뷰 스코프 ───────────────────────────────────────────────────────────

    @Test
    fun `허용되지 않은 뷰 스코프는 400 이다`() {
        // 서비스가 스코프를 그대로 DB 로 넘기면 CHECK 위반이 500 으로 나간다.
        val board = insertBoard(BoardType.SCRUM)

        patchCardLayout(board.id, mapOf("SPRINT" to listOf("EPIC"))).andExpect(status().isBadRequest)

        assertThat(settingsRepository.findCardLayout(board.id)).isEmpty()
    }

    @Test
    fun `뷰를 하나도 담지 않은 요청은 400 이다`() {
        val board = insertBoard(BoardType.SCRUM)

        patchCardLayout(board.id, emptyMap()).andExpect(status().isBadRequest)
    }

    // ── ⑧ 칸반 조합 판정 ──────────────────────────────────────────────────────

    @Test
    fun `칸반 보드에 백로그 뷰를 보내면 400 이고 같은 요청의 보드 뷰도 저장되지 않는다`() {
        // 칸반에는 백로그 뷰가 없다(R3). 요청 단위로 거부해야 반쪽 저장이 안 남는다.
        val board = insertBoard(BoardType.KANBAN)

        patchCardLayout(board.id, mapOf("BOARD" to listOf("EPIC"), "BACKLOG" to listOf("ESTIMATE")))
            .andExpect(status().isBadRequest)

        assertThat(settingsRepository.findCardLayout(board.id)).isEmpty()
    }

    @Test
    fun `칸반 보드도 보드 뷰는 저장한다`() {
        // ⑧ 의 짝 — 「칸반이면 전부 거부」 구현이 여기서 죽는다.
        val board = insertBoard(BoardType.KANBAN)

        patchCardLayout(board.id, mapOf("BOARD" to listOf("EPIC")))
            .andExpect(status().isOk)

        assertThat(settingsRepository.findCardLayout(board.id)["BOARD"]).containsExactly("EPIC")
    }

    // ── ⑨ 권한·존재·인증 순서 ─────────────────────────────────────────────────

    @Test
    fun `권한이 없으면 403 이고 아무것도 저장되지 않는다`() {
        val board = insertBoard(BoardType.SCRUM)
        permissionStub.allowAll = false

        patchCardLayout(board.id, mapOf("BOARD" to listOf("EPIC"))).andExpect(status().isForbidden)

        assertThat(settingsRepository.findCardLayout(board.id)).isEmpty()
    }

    @Test
    fun `권한이 없어도 없는 보드는 404 다`() {
        // 존재 확인이 권한 판정보다 **먼저**여야 성립한다. 순서가 뒤집히면 403 이 나오고
        // 그 403 은 「그 보드는 있다」를 누설한다 — 로컬은 항상 권한을 가져 눈에 안 보인다.
        permissionStub.allowAll = false

        patchCardLayout(UUID.randomUUID(), mapOf("BOARD" to listOf("EPIC")))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `미인증이면 존재하는 보드라도 401 이다`() {
        val board = insertBoard(BoardType.SCRUM)
        SecurityContextHolder.clearContext()

        patchCardLayout(board.id, mapOf("BOARD" to listOf("EPIC"))).andExpect(status().isUnauthorized)

        assertThat(settingsRepository.findCardLayout(board.id)).isEmpty()
    }

    // ── ⑩ 게이트 인자 ─────────────────────────────────────────────────────────

    @Test
    fun `권한 게이트는 SOFT_DELETE 권한과 프로젝트 스코프로 판정한다`() {
        val board = insertBoard(BoardType.SCRUM)

        patchCardLayout(board.id, mapOf("BOARD" to listOf("EPIC"))).andExpect(status().isOk)

        assertThat(permissionStub.lastPermission).isEqualTo(IssuePermission.SOFT_DELETE)
        assertThat(permissionStub.lastScope).isEqualTo(IssueScope.Project(board.projectKey))
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────

    private fun patchCardLayout(
        boardId: UUID,
        cardLayout: Map<String, List<String>>,
    ): ResultActions =
        mockMvc.perform(
            patch("/api/v1/boards/$boardId/card-layout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("cardLayout" to cardLayout))),
        )

    private fun insertBoard(boardType: BoardType): Board =
        boardRepository.insert(
            Board(
                id = UUID.randomUUID(),
                projectKey = "CL${UUID.randomUUID().toString().take(4).uppercase()}",
                name = "카드 레이아웃 테스트 보드",
                columns = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                boardType = boardType,
            ),
        )
}
