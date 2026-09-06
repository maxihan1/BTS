// 추정 탭 PATCH 실 DB HTTP 통합 테스트 — 칸반 409 · 없는 보드 404 · 보드 종류 왕복 생존(E6) (부채 177 · J36·J37)

package com.bts.agileplanning.web

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.agileplanning.application.EstimationSettingsService
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardType
import com.bts.agileplanning.jooq.tables.references.BOARDS
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.repository.BoardSettingsRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
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
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/** 시간 추적 기본값 — `boards.time_tracking` 의 `DEFAULT 'NONE'`(V509). */
private const val NONE = "NONE"

/** 시간 추적 「잔여 추정 + 소요 시간」(J36). */
private const val REMAINING_AND_SPENT = "REMAINING_AND_SPENT"

/**
 * 추정 탭 PATCH 의 실 DB HTTP 통합 테스트 — `PATCH /api/v1/boards/{boardId}/estimation`.
 *
 * ## 이 파일이 지는 판정과 **무엇과 무엇을 가르는지**
 *
 * | 축 | 단언 | 이 단언이 없으면 통과해 버리는 구현 |
 * |---|---|---|
 * | ① 대조군 | 스크럼은 200 이고 **DB 에 값이 앉는다** | 「전부 409」·「200 만 주고 저장은 안 하는」 구현 |
 * | ② 칸반 잠금 | 칸반은 **409**(J37 · E5) | 칸반에도 저장하는 구현(잠금 자체가 없다) |
 * | ③ **409 ↔ 404** | 없는 보드는 **404**(409 아님) | 「보드를 안 찾고 무조건 409」인 구현. ②만 두면 통과한다 |
 * | ④ soft-delete | 삭제된 스크럼 보드도 404 | `deleted_at` 을 안 보는 조회를 쓰는 구현 |
 * | ⑤ **E6 왕복** | 종류를 스크럼→칸반→스크럼으로 돌려도 값이 **DB 에 살아 있다** | 칸반 전환·409 경로에서 값을 지우거나 덮는 구현 |
 * | ⑥ 읽기 경로 | 칸반인 동안에도 [BoardSettingsRepository.findTimeTracking] 이 값을 준다 | 읽기를 `board_type` 으로 거르는 구현(E6 가 무너진다) |
 * | ⑦ 허용값 | 허용값 밖은 **400** | 문자열을 그대로 DB 로 보내 CHECK 위반 500 을 내는 구현 |
 * | ⑧ 게이트 순서 | 미인증은 보드 조회 **이전에** 401 · 권한 미충족은 종류 판정 **이전에** 403 | 존재/종류를 미인증자에게 흘리는 구현 |
 * | ⑨ **서비스 층의 404↔409** | 서비스를 직접 불러도 없는 보드는 404 · 칸반은 409 | 서비스가 「무조건 409」인 구현. ③ 은 컨트롤러 게이트가 가려 준다(뮤테이션 B) |
 * | ⑩ **게이트 인자** | 권한 판정이 `CREATE` + `Project` 스코프로 간다 | 다른 권한코드(BROWSE·SOFT_DELETE)로 묻는 구현. ⑧ 은 「거부되면 403」만 재서 못 잡는다 |
 *
 * ★**⑤ 가 이 파일의 진짜 판정이다.** 그리고 API 응답만 보면 「지우고 안 보여주는」 구현도 통과하므로
 * **jOOQ 로 `boards.time_tracking` 을 직접 읽어** 판정한다([rawTimeTracking] — 서비스·리포지터리를
 * 둘 다 우회한다).
 *
 * ★**뮤테이션 방향을 일부러 반대로 잡았다.** ⑤ 의 칸반 PATCH 는 `NONE` 을 보낸다. 같은 값을 보내면
 * 「409 를 던지기 전에 써 버리는」 구현과 올바른 구현이 **구분되지 않는다** — 보드에 이미 앉아 있는
 * 값(`REMAINING_AND_SPENT`)과 **다른 값**을 보내야 그 축이 산다. ② 도 같은 이유로 기본값 `NONE` 인
 * 보드에 `REMAINING_AND_SPENT` 를 보낸다(양방향).
 *
 * ## 뮤테이션 실측 (2026-09-06 — 이 파일이 **실제로** 무엇을 가르는지)
 *
 * | # | 구현을 이렇게 망가뜨리면 | 결과 |
 * |---|---|---|
 * | A | 칸반에서 409 대신 404 를 던진다 | ②⑤ red — **죽는다** |
 * | B | 서비스의 「없는 보드」 404 를 409 로 바꾼다 | 보강 전 **8건 전부 초록 — 살아남았다** / 보강 후 ⑨ red |
 * | C | [BoardSettingsRepository.findTimeTracking] 이 `board_type = 'SCRUM'` 으로 거른다 | ⑤ red — **죽는다** |
 * | D | 저장을 건너뛰고 200 만 준다 | ①⑤ red — **죽는다** |
 * | E | 컨트롤러의 권한코드를 `CREATE` → `SOFT_DELETE` 로 바꾼다 | ⑩ red — **죽는다**(⑩ 도입 전에는 10건 전부 초록) |
 *
 * ★**B 가 살아남은 것이 이 파일의 실제 발견이다.** HTTP 로만 재면 컨트롤러 권한 게이트가 먼저 보드를
 * 조회해 404 를 내주므로 **서비스 안의 404↔409 갈림에는 도달하지 않는다** — 서비스가 「보드를 못 찾으면
 * 409」여도 8건이 전부 초록이었다. 그래서 ⑨ 를 더해 서비스를 **직접** 부른다. 컨트롤러를 거치는
 * 판정만으로는 서비스의 계약을 못 잡는다는 것이 이 축의 교훈이다.
 *
 * ## 이 파일이 재지 **않는** 것
 * - 에러 바디의 `errorCode` — [BoardExceptionHandler] 의 `assignableTypes` 에 이 컨트롤러가 없다.
 *   그 파일은 Task 8·10·13 과 공유하는 자원이라 이 task 가 건드리지 않는다. 예외가
 *   [org.springframework.web.server.ResponseStatusException] 을 상속하므로 **상태 코드는 그대로 전파**되지만
 *   RFC 7807 바디는 붙지 않는다(보고 대상).
 * - 보드 종류 변경 API — 도메인이 변경 경로를 두지 않는다([BoardType] KDoc). ⑤ 는 `board_type` 을
 *   jOOQ 로 직접 뒤집어 「운영 중 종류가 바뀐 보드」를 만든다.
 * - 조회와 쓰기 **사이에** 보드가 소프트 삭제되는 경합 —
 *   [EstimationSettingsService.updateTimeTracking] 의 `updateTimeTracking(...) == false` 가지는
 *   그 경합에서만 도달한다. 훅 없이 재현할 수 없어 이 파일은 그 가지를 재지 않는다.
 *
 * ## 트랜잭션
 * 클래스에 `@Transactional` 을 걸지 **않는다** — MockMvc 요청이 각자 커밋한 결과를 봐야 ⑤ 가 성립한다.
 * 형제 [com.bts.agileplanning.repository.BoardSettingsRepositoryTest] 와 같은 판단이다.
 */
@SpringBootTest(
    classes = [AgilePlanningTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(AgilePlanningTestcontainersConfig::class, BoardEstimationApiTest.StubConfig::class)
@ActiveProfiles("test")
// detekt VarCouldBeVal 오탐 — 필드 주입은 `lateinit var` 뿐이고 `lateinit` 은 val 에 못 쓴다.
@Suppress("VarCouldBeVal")
class BoardEstimationApiTest {
    /** 테스트별 allow/deny 토글이 가능한 권한 stub 을 [Primary] 로 얹는다. */
    @TestConfiguration(proxyBeanMethods = false)
    class StubConfig {
        @Bean
        @Primary
        fun permissionStub(): PermissionStub = PermissionStub()
    }

    /**
     * allow/deny 토글 + **전달 인자 캡처**가 가능한 [IssuePermissionResolver] stub.
     *
     * ★캡처가 없으면 컨트롤러가 어떤 권한코드로 묻든 전부 초록이다 — 게이트가 「돌았다」만 재고
     * 「무엇으로 물었는가」는 아무도 안 본다. 형제 [BoardCardLayoutApiTest.PermissionStub] 과
     * **같은 필드 이름**을 쓴다(Task 29 가 네 탭의 권한코드를 한 번에 고칠 수 있어야 한다).
     * 필드별로 축이 **어디에** 서는지는 정본인 [BoardCardLayoutApiTest.PermissionStub] 의 표에 있다.
     */
    class PermissionStub : IssuePermissionResolver {
        var allowAll: Boolean = true
        var lastPermission: IssuePermission? = null
        var lastScope: IssueScope? = null
        var lastActorId: UUID? = null
        var callCount: Int = 0

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean {
            lastPermission = permission
            lastScope = scope
            lastActorId = actorId
            callCount += 1
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
    private lateinit var dsl: DSLContext

    @Autowired
    private lateinit var permissionStub: PermissionStub

    /** ⑨ 축 전용 — 컨트롤러 게이트를 거치지 않고 서비스의 계약을 직접 잰다. */
    @Autowired
    private lateinit var estimationSettingsService: EstimationSettingsService

    private lateinit var mockMvc: MockMvc

    private val actorId: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()
        authenticate()
        permissionStub.allowAll = true
        // 앞 테스트가 남긴 값으로 ⑩ 이 초록이 되지 않게 매번 비운다.
        permissionStub.lastPermission = null
        permissionStub.lastScope = null
        permissionStub.lastActorId = null
        permissionStub.callCount = 0
    }

    // ── ① 대조군 (J36) ────────────────────────────────────────────────────────

    @Test
    fun `스크럼 보드는 시간 추적을 저장하고 200 을 준다`() {
        // 이 대조군이 없으면 「전부 409」인 구현도 ②③ 을 통과한다.
        val board = insertBoard(BoardType.SCRUM)

        patchEstimation(board.id, REMAINING_AND_SPENT)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.timeTracking").value(REMAINING_AND_SPENT))

        assertThat(rawTimeTracking(board.id)).isEqualTo(REMAINING_AND_SPENT)
    }

    // ── ② 칸반 잠금 (J37 · E5) ────────────────────────────────────────────────

    @Test
    fun `칸반 보드는 409 이고 값이 바뀌지 않는다`() {
        val board = insertBoard(BoardType.KANBAN)

        patchEstimation(board.id, REMAINING_AND_SPENT)
            .andExpect(status().isConflict)

        // 요청값과 다른 기본값이 그대로 남아야 한다 — 「쓰고 나서 409 를 던지는」 구현을 가른다.
        assertThat(rawTimeTracking(board.id)).isEqualTo(NONE)
    }

    // ── ③ 409 ↔ 404 (E5 의 핵심) ──────────────────────────────────────────────

    @Test
    fun `없는 보드는 404 다 — 409 가 아니다`() {
        // ② 만 두면 「보드를 찾지도 않고 무조건 409」인 구현이 통과한다. 그 구현을 여기서 가른다.
        patchEstimation(UUID.randomUUID(), REMAINING_AND_SPENT)
            .andExpect(status().isNotFound)
    }

    // ── ④ soft-delete 도 404 ──────────────────────────────────────────────────

    @Test
    fun `소프트 삭제된 스크럼 보드는 404 다`() {
        val board = insertBoard(BoardType.SCRUM)
        boardRepository.softDelete(board.id)

        patchEstimation(board.id, REMAINING_AND_SPENT)
            .andExpect(status().isNotFound)

        assertThat(rawTimeTracking(board.id)).isEqualTo(NONE)
    }

    // ── ⑤⑥ E6 — 보드 종류 왕복에도 값이 살아 있다 ────────────────────────────

    @Test
    fun `보드 종류를 스크럼 칸반 스크럼으로 왕복시켜도 시간 추적 값이 살아 있다`() {
        val board = insertBoard(BoardType.SCRUM)
        patchEstimation(board.id, REMAINING_AND_SPENT).andExpect(status().isOk)

        // (1) 칸반으로 바뀌어도 저장된 값은 그대로다 — DB 를 직접 본다(응답은 「지우고 안 보여주는」 구현도 통과).
        setBoardType(board.id, BoardType.KANBAN)
        assertThat(rawTimeTracking(board.id)).isEqualTo(REMAINING_AND_SPENT)

        // (2) 읽는 쪽도 board_type 으로 거르지 않는다 — 거르면 왕복에 값이 사라진 것처럼 보인다.
        assertThat(settingsRepository.findTimeTracking(board.id)).isEqualTo(REMAINING_AND_SPENT)

        // (3) 칸반인 동안의 PATCH 는 409 이고, 그 경로가 값을 NONE 으로 덮지 않는다.
        //     ★보내는 값을 일부러 현재 값과 **다른** NONE 으로 잡았다 — 같은 값이면 이 축이 공허해진다.
        patchEstimation(board.id, NONE).andExpect(status().isConflict)
        assertThat(rawTimeTracking(board.id)).isEqualTo(REMAINING_AND_SPENT)

        // (4) 스크럼으로 되돌리면 값이 그대로 살아 있고 다시 편집할 수 있다.
        setBoardType(board.id, BoardType.SCRUM)
        assertThat(rawTimeTracking(board.id)).isEqualTo(REMAINING_AND_SPENT)

        patchEstimation(board.id, NONE)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.timeTracking").value(NONE))
        assertThat(rawTimeTracking(board.id)).isEqualTo(NONE)
    }

    // ── ⑦ 허용값 밖은 400 (500 방지) ──────────────────────────────────────────

    @Test
    fun `허용값 밖의 시간 추적은 400 이다`() {
        // 문자열을 그대로 DB 로 보내면 V509 의 CHECK 가 걸려 500 이 된다. 서비스가 먼저 가른다.
        val board = insertBoard(BoardType.SCRUM)

        patchEstimation(board.id, "STORY_POINTS")
            .andExpect(status().isBadRequest)

        assertThat(rawTimeTracking(board.id)).isEqualTo(NONE)
    }

    // ── ⑧ 게이트 순서 ────────────────────────────────────────────────────────

    @Test
    fun `미인증이면 보드 조회 이전에 401 이다`() {
        // 없는 보드로 부른다 — 404 가 오면 미인증자에게 존재 여부를 흘리는 순서다.
        SecurityContextHolder.clearContext()

        patchEstimation(UUID.randomUUID(), REMAINING_AND_SPENT)
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `권한이 없으면 보드 종류 판정 이전에 403 이다`() {
        // 칸반 보드로 부른다 — 409 가 오면 권한 없는 사람에게 보드 종류를 흘리는 순서다.
        val board = insertBoard(BoardType.KANBAN)
        permissionStub.allowAll = false

        patchEstimation(board.id, REMAINING_AND_SPENT)
            .andExpect(status().isForbidden)
    }

    // ── ⑩ 게이트 인자 — 어떤 권한코드로 물었는가 ─────────────────────────────

    @Test
    fun `권한 게이트는 CREATE 권한과 프로젝트 스코프로 판정한다`() {
        // ⑧ 은 「거부되면 403」만 잰다 — 컨트롤러가 BROWSE 로 묻든 SOFT_DELETE 로 묻든 초록이다.
        // 설정 화면의 다른 쓰기 경로와 프론트
        // (apps/web/src/routes/projects.$projectKey.board.settings.tsx:103)가 CREATE 로 편집 여부를 가르므로
        // 이 탭도 CREATE 여야 한다. 그 선택을 여기서 못박는다.
        val board = insertBoard(BoardType.SCRUM)

        patchEstimation(board.id, REMAINING_AND_SPENT).andExpect(status().isOk)

        assertThat(permissionStub.lastPermission).isEqualTo(IssuePermission.CREATE)
        assertThat(permissionStub.lastScope).isEqualTo(IssueScope.Project(board.projectKey))
    }

    // ── ⑨ 서비스 층의 404 ↔ 409 (뮤테이션 B 가 살아남아 보강한 축) ────────────

    @Test
    fun `서비스는 없는 보드에 404 를 준다 — 409 가 아니다`() {
        // ③ 은 컨트롤러 권한 게이트의 404 를 재고 있었다. 서비스가 「무조건 409」여도 ③ 은 초록이다.
        val thrown =
            assertThrows<ResponseStatusException> {
                estimationSettingsService.updateTimeTracking(UUID.randomUUID(), REMAINING_AND_SPENT)
            }

        assertThat(thrown.statusCode.value()).isEqualTo(HttpStatus.NOT_FOUND.value())
    }

    @Test
    fun `서비스는 칸반 보드에 409 를 준다 — 404 가 아니다`() {
        // 짝을 함께 둔다. 앞엣것만 두면 「무조건 404」인 서비스가 통과한다.
        val board = insertBoard(BoardType.KANBAN)

        val thrown =
            assertThrows<ResponseStatusException> {
                estimationSettingsService.updateTimeTracking(board.id, REMAINING_AND_SPENT)
            }

        assertThat(thrown.statusCode.value()).isEqualTo(HttpStatus.CONFLICT.value())
        assertThat(rawTimeTracking(board.id)).isEqualTo(NONE)
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    private fun patchEstimation(
        boardId: UUID,
        timeTracking: String,
    ): ResultActions =
        mockMvc.perform(
            patch("/api/v1/boards/{boardId}/estimation", boardId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"timeTracking":"$timeTracking"}"""),
        )

    /**
     * `boards.time_tracking` 을 **jOOQ 로 직접** 읽는다 — 서비스도 리포지터리도 우회한다.
     *
     * `deleted_at` 술어를 걸지 않는 것이 의도다. 소프트 삭제된 보드의 값도 봐야
     * 「404 를 주면서 뒤로는 썼다」를 잡을 수 있다.
     */
    private fun rawTimeTracking(boardId: UUID): String? =
        dsl.select(BOARDS.TIME_TRACKING)
            .from(BOARDS)
            .where(BOARDS.ID.eq(boardId))
            .fetchOne(BOARDS.TIME_TRACKING)

    /**
     * 보드 종류를 직접 뒤집는다 (E6 관측점 제작).
     *
     * 도메인은 종류 변경 경로를 두지 않는다([BoardType] KDoc) — 그래서 API 로는 못 만든다.
     * 재현 대상은 「운영 중 종류가 바뀐 보드」이므로 저장 칸을 직접 쓴다.
     */
    private fun setBoardType(
        boardId: UUID,
        boardType: BoardType,
    ) {
        dsl.update(BOARDS)
            .set(BOARDS.BOARD_TYPE, boardType.name)
            .where(BOARDS.ID.eq(boardId))
            .execute()
    }

    private fun insertBoard(boardType: BoardType): Board =
        boardRepository.insert(
            Board(
                id = UUID.randomUUID(),
                projectKey = "EST${UUID.randomUUID().toString().take(4).uppercase()}",
                name = "추정 탭 테스트 보드",
                columns = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                boardType = boardType,
            ),
        )
}
