// 상세 보기 필드 구성 HTTP 슬라이스 테스트 — 그룹 4종 · 순서 · 미지원 그룹 400 · 권한 게이트 (부채 177 Task 13)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.DetailViewSettingsService
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.repository.BoardSettingsRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/** 컨트롤러 경로. path variable 은 URI 템플릿으로 넘긴다. */
private const val PATH = "/api/v1/boards/{boardId}/detail-view-fields"

/**
 * ★**사전순의 역순**으로 고른 필드 키들이다. 순서 축의 판별력이 여기서 나온다.
 *
 * `listOf("a","b","c")` 처럼 이미 정렬된 입력을 쓰면 `sorted()` 를 끼워 넣은 느슨한 구현도 초록이다.
 * 아래 세 목록은 모두 사전순과 **다른** 순서라, 정렬이 개입하는 순간 red 가 된다.
 */
private val GENERAL_FIELDS = listOf("summary", "status", "cf_severity")
private val DATE_FIELDS = listOf("dueDate", "createdAt")
private val PEOPLE_FIELDS = listOf("reporter", "assignee")
private val LINKS_FIELDS = listOf("issueLinks")

/**
 * [BoardDetailViewController] + [DetailViewSettingsService] 의 HTTP 계약 슬라이스 테스트 (부채 177 Task 13).
 *
 * ## 왜 Testcontainers 가 아니라 in-memory fake 인가 — ★순서 축을 실행 계획에서 떼어내려고
 *
 * Task 7 구현자가 실측한 함정이 있다. `BoardSettingsRepository.findDetailViewFields` 의
 * `ORDER BY position` 을 **통째로 지워도** 리포지터리 테스트가 전부 초록이었다 —
 * `WHERE board_id = ?` 가 PK 인덱스 `(board_id, field_group, position)` 를 타고 그 인덱스 순서가
 * 곧 `position` 순이라, 정렬 없이도 정렬된 결과가 나왔기 때문이다. 즉 그 단언은 **구현이 아니라
 * 실행 계획**을 재고 있었다. 그래서 그쪽은 `BoardSettingsRepositoryTest.readWithSeqScan` 으로
 * 인덱스 스캔을 꺼서 함정을 닫았다.
 *
 * 이 파일의 순서 축은 **그 함정 자체가 성립하지 않도록** 만들어져 있다. [FakeSettingsStore] 는
 * 인덱스도 `position` 컬럼도 없이 **컨트롤러가 넘긴 리스트를 그 순서 그대로** 보관하고 그대로 돌려준다.
 * 따라서 응답의 순서를 만드는 주체는 오직 **이 task 가 작성한 코드**(컨트롤러 → 서비스 → DTO → Jackson)다.
 * DB 의 `ORDER BY` 는 Task 7 의 책임이고 이미 그쪽 테스트가 진다 — 여기서 겹쳐 재면 두 층 중
 * 어느 쪽이 순서를 지켰는지 구분할 수 없게 된다.
 *
 * ## 축 (공허 방지 — 각 축이 무엇과 무엇을 가르는지)
 *
 * | 축 | 무엇을 재나 | 어떤 구현을 가르나 |
 * |---|---|---|
 * | A 키 존재 | 구성 없는 보드도 그룹 4종 키가 있다 | 구성 없는 그룹의 키를 빠뜨리는 구현 |
 * | B 그룹 축 | 4종에 **서로 다른** 구성이 각자 앉는다 | 그룹을 무시하고 한 칸에 몰아 넣는 구현 |
 * | **C 순서** | 같은 필드를 **순서만** 바꿔 저장하면 새 순서가 나온다 | `sorted()`/집합 합집합/선착순 유지 구현 |
 * | D 부분 갱신 | 요청에 **없는** 그룹은 살아남는다 | 매 PATCH 마다 4종을 전부 덮는 구현 |
 * | E 비우기 | 빈 목록이 그 그룹만 비운다 | 빈 목록을 무시하고 건너뛰는 구현 |
 * | F 미지원 그룹 | 400 이고 **같은 요청의 유효한 그룹도 저장되지 않는다** | 돌면서 쓰다가 도중에 터지는 구현 |
 * | G·H 권한 | 쓰기 SOFT_DELETE · 읽기 BROWSE · 프로젝트 스코프 | 아무 권한이나 물어보는 구현 |
 * | I 미인증 | 401 이고 **보드 조회조차 안 한다** | 존재 probe 를 열어 주는 구현 |
 * | J 검사 순서 | 보드 없음 + 권한 거부 = **404** | 권한을 먼저 봐서 403 을 내는 구현 |
 *
 * ★**대조군을 함께 둔다.** F 의 400 만 재면 「전부 400」인 구현이 통과한다 — B 가 유효한 4종이
 * **200 으로 저장된다**를 같은 파일에서 재는 것이 그 대조군이다.
 *
 * ## 뮤테이션 실측 (2026-09-06 · 9개 전부 사살)
 *
 * 다음 사람이 「이 단언들이 정말 무언가를 가르는가」를 다시 유도하지 않도록 결과를 남긴다.
 * 각 행의 왼쪽이 **느슨한 구현**, 오른쪽이 그것을 잡은 테스트다.
 *
 * | 뮤턴트 | 잡은 테스트 |
 * |---|---|
 * | M1a 읽기에 `sorted()` 를 끼운다 | B · C · D · E · F (5) |
 * | **M1b 저장을 합집합으로**(멤버십만 보고 옛 순서 유지) | **C** · D · E (3) |
 * | M2 그룹 축 제거(전부 `GENERAL` 로) | B · D · E (3) |
 * | M3 매 PATCH 마다 4종을 먼저 비운다 | D · E (2) |
 * | M4 그룹 검증 제거 | F 두 건 (2) |
 * | M5 검증을 루프 안으로(부분 쓰기) | F 의 「유효한 그룹도 저장되지 않는다」 (1) |
 * | M6 쓰기 권한코드를 `BROWSE` 로 느슨화 | G (1) |
 * | M7 검사 순서 뒤집기(권한 먼저) | J (1) |
 * | M8 응답 정규화 제거 | A (1) |
 *
 * ★**M1b 가 이 파일의 존재 이유다.** 「깨면 red 가 당연한」 방향(M1a)만 재면 판별력을 증명한 것이
 * 아니다. M1b 는 멤버십이 같고 **순서만** 다른 저장이라, 집합으로 판단하는 구현과 순서를 지키는
 * 구현을 정확히 가른다 — 그리고 그것을 잡는 유일한 순서 축 단언이 C 다.
 *
 * ★**순서 축이 실행 계획이 아니라 구현을 잰다는 반대편 증거.** Task 7 리포지터리의
 * `ORDER BY position` 을 **지운 채로** 이 파일을 돌려도 **11건 전부 초록**이었다(2026-09-06 실측).
 * 이 테스트의 초록은 DB 인덱스가 빌려준 것이 아니다 — 순서를 만드는 주체가 이 task 의 코드뿐이라
 * M1a·M1b 로만 무너진다. DB 층의 정렬은 `BoardSettingsRepositoryTest` 가 `readWithSeqScan` 으로 진다.
 *
 * ## 편차 X8 — 보드 단위 권한이 없다
 * J49 는 *board admin* 을 요구하지만 BTS 에는 보드 단위 권한 모델이 없다. 프로젝트 권한으로 갈음하며
 * 쓰기는 Task 8 과 **같은 게이트**([IssuePermission.SOFT_DELETE])를 탄다.
 *
 * ## 예외 핸들러 — [BoardExceptionHandler] 는 이 컨트롤러를 덮지 않는다
 * 그 advice 의 `assignableTypes` 는 [BoardController]·[BoardQuickFilterController] 뿐이고 그 파일은
 * T8·T9·T10 과 공유하는 자원이라 이 task 가 건드리지 않는다. 그래서 이 컨트롤러의 오류 경로는
 * 전부 [org.springframework.web.server.ResponseStatusException] 으로 내고, 테스트 컨텍스트에도
 * 그 advice 를 **일부러 등록해** 「덮이지 않아도 상태 코드가 맞다」를 확인한다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [BoardDetailViewApiTest.TestMvcConfig::class])
@WebAppConfiguration
class BoardDetailViewApiTest {
    /**
     * `board_detail_view_fields` 를 대신하는 in-memory 저장소.
     *
     * [BoardSettingsRepository.replaceDetailViewFields] 의 계약(**그룹 단위 교체**)을 그대로 옮긴다 —
     * 술어에 그룹이 걸려 있어 다른 그룹은 건드리지 않고, 빈 목록은 그 그룹을 비운다.
     * 리스트는 **받은 순서 그대로** 보관한다(정렬·집합화 없음).
     */
    class FakeSettingsStore {
        private val rows = mutableMapOf<UUID, LinkedHashMap<String, List<String>>>()

        /** 그룹 단위 교체. */
        fun replace(
            boardId: UUID,
            fieldGroup: String,
            fieldKeys: List<String>,
        ) {
            rows.getOrPut(boardId) { linkedMapOf() }[fieldGroup] = fieldKeys.toList()
        }

        /**
         * 구성이 없는 그룹은 **키 자체가 없다** — 실제 리포지터리와 같은 규약이다.
         *
         * `toMap()` 으로 복사본을 준다. 살아 있는 맵을 그대로 돌려주면 읽은 뒤의 쓰기가 이미 읽어 간
         * 결과까지 바꿔 버려, 「쓰기 전 스냅샷을 응답한다」 같은 결함이 테스트에서 사라진다.
         */
        fun read(boardId: UUID): Map<String, List<String>> = rows[boardId].orEmpty().toMap()

        /** 테스트 간 격리. */
        fun clear() = rows.clear()
    }

    /** 테스트별 allow/deny 토글 + 전달 인자 캡처가 가능한 [IssuePermissionResolver] stub. */
    open class PermissionGate : IssuePermissionResolver {
        /** false 면 모든 권한 판정을 거부한다. */
        var allowAll: Boolean = true

        /** [hasPermission] 호출마다 전달된 (actorId, permission, scope) 를 순서대로 기록한다. */
        val calls: MutableList<Triple<UUID, IssuePermission, IssueScope>> = mutableListOf()

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean {
            calls.add(Triple(actorId, permission, scope))
            return allowAll
        }
    }

    /** 테스트 전용 Spring MVC 최소 컨텍스트. */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun fakeSettingsStore(): FakeSettingsStore = FakeSettingsStore()

        @Bean
        open fun boardSettingsRepository(): BoardSettingsRepository = mockk()

        @Bean
        open fun boardRepository(): BoardRepository = mockk()

        @Bean
        open fun permissionGate(): PermissionGate = PermissionGate()

        @Bean
        open fun detailViewSettingsService(repository: BoardSettingsRepository): DetailViewSettingsService =
            DetailViewSettingsService(repository)

        @Bean
        open fun boardDetailViewController(
            service: DetailViewSettingsService,
            repository: BoardRepository,
            gate: PermissionGate,
        ): BoardDetailViewController = BoardDetailViewController(service, repository, gate)

        // 이 advice 는 assignableTypes 에 BoardDetailViewController 가 없어 적용되지 않는다.
        // 그래도 등록해 두는 이유는 「덮이지 않는 상태에서도 401/403/404/400 이 맞는가」를 재기 위해서다.
        @Bean
        open fun boardExceptionHandler(): BoardExceptionHandler = BoardExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var settingsRepository: BoardSettingsRepository

    @Autowired
    lateinit var boardRepository: BoardRepository

    @Autowired
    lateinit var permissionGate: PermissionGate

    @Autowired
    lateinit var store: FakeSettingsStore

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val actorId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val boardId: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val projectKey = "BTS"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        clearMocks(settingsRepository, boardRepository)
        store.clear()
        permissionGate.allowAll = true
        permissionGate.calls.clear()

        every { boardRepository.findById(any()) } returns sampleBoard()
        every { settingsRepository.findDetailViewFields(any()) } answers { store.read(firstArg()) }
        every { settingsRepository.replaceDetailViewFields(any(), any(), any()) } answers {
            store.replace(firstArg(), secondArg(), thirdArg())
        }

        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    // ── A. 키 존재 ────────────────────────────────────────────────────────────

    @Test
    fun `구성이 없는 보드도 그룹 4종을 빈 목록으로 돌려준다`() {
        // 리포지터리는 구성 없는 그룹의 키를 아예 주지 않는다(orEmpty 규약).
        // 그 구멍을 응답까지 흘리면 프론트가 그룹마다 부재/빈 배열 두 경우를 나눠 다뤄야 한다.
        val result = performGet()

        assertThat(result.response.status).isEqualTo(200)
        val groups = groupsOf(result)
        assertThat(groups.keys).containsExactly("GENERAL", "DATE", "PEOPLE", "LINKS")
        assertThat(groups.values).allSatisfy { assertThat(it).isEmpty() }
    }

    // ── B. 그룹 축 + 400 의 대조군 ────────────────────────────────────────────

    @Test
    fun `그룹 4종에 서로 다른 구성을 저장하면 각 그룹이 제 순서를 지킨다`() {
        // 그룹 축이 없는 구현(전부 한 그룹에 몰아 넣기)도 「저장하고 읽으면 같다」는 통과한다.
        // 그래서 4종에 **서로 다른** 목록을 넣고 각각 되읽는다. 동시에 이것이 F(미지원 그룹 400)의
        // 대조군이다 — 유효한 4종은 200 으로 저장된다.
        val patched = patchGroups(allFourGroups())

        assertThat(patched.response.status).isEqualTo(200)
        val groups = groupsOf(performGet())
        assertThat(groups["GENERAL"]).containsExactly("summary", "status", "cf_severity")
        assertThat(groups["DATE"]).containsExactly("dueDate", "createdAt")
        assertThat(groups["PEOPLE"]).containsExactly("reporter", "assignee")
        assertThat(groups["LINKS"]).containsExactly("issueLinks")
    }

    // ── C. ★순서 축 ──────────────────────────────────────────────────────────

    @Test
    fun `같은 필드를 순서만 바꿔 다시 저장하면 응답과 재조회가 새 순서를 따른다`() {
        // ★이 task 의 핵심 red. 「집합이 같으니 쓸 것이 없다」로 건너뛰는 구현, 저장을 합집합으로
        // 처리하는 구현, 어디선가 sorted() 를 끼운 구현이 여기서 갈린다 — 멤버십은 그대로 두고
        // **순서만** 바꾸기 때문에 집합 기준으로 판단하는 구현은 옛 순서를 그대로 돌려준다.
        patchGroups(mapOf("GENERAL" to GENERAL_FIELDS))

        val reordered = listOf("cf_severity", "summary", "status")
        val patched = patchGroups(mapOf("GENERAL" to reordered))

        // PATCH 응답 자체가 새 순서여야 한다 — 쓰기 전 스냅샷을 돌려주는 구현을 가른다.
        assertThat(groupsOf(patched)["GENERAL"]).containsExactly("cf_severity", "summary", "status")
        assertThat(groupsOf(performGet())["GENERAL"]).containsExactly("cf_severity", "summary", "status")
    }

    // ── D. 부분 갱신 ─────────────────────────────────────────────────────────

    @Test
    fun `한 그룹만 보낸 PATCH 는 요청에 없는 그룹을 지우지 않는다`() {
        patchGroups(allFourGroups())

        patchGroups(mapOf("PEOPLE" to listOf("watchers")))

        val groups = groupsOf(performGet())
        assertThat(groups["PEOPLE"]).containsExactly("watchers")
        assertThat(groups["GENERAL"]).containsExactly("summary", "status", "cf_severity")
        assertThat(groups["DATE"]).containsExactly("dueDate", "createdAt")
        assertThat(groups["LINKS"]).containsExactly("issueLinks")
    }

    // ── E. 비우기 ────────────────────────────────────────────────────────────

    @Test
    fun `빈 목록을 보내면 그 그룹만 비고 다른 그룹은 남는다`() {
        // J48 의 Delete 로 마지막 필드까지 지운 상태다. 빈 목록을 「보낼 것이 없다」로 건너뛰는
        // 구현이면 지운 필드가 되살아난다.
        patchGroups(allFourGroups())

        patchGroups(mapOf("GENERAL" to emptyList()))

        val groups = groupsOf(performGet())
        assertThat(groups["GENERAL"]).isEmpty()
        assertThat(groups["PEOPLE"]).containsExactly("reporter", "assignee")
    }

    // ── F. 미지원 그룹 400 ───────────────────────────────────────────────────

    @Test
    fun `미지원 그룹은 400 이고 같은 요청의 유효한 그룹도 저장되지 않는다`() {
        patchGroups(mapOf("GENERAL" to GENERAL_FIELDS))

        // GENERAL 을 **먼저** 둔다 — 돌면서 쓰다가 BOGUS 에서 터지는 구현이면 GENERAL 이 먼저 덮인다.
        val result =
            patchGroups(
                linkedMapOf(
                    "GENERAL" to listOf("overwritten"),
                    "BOGUS" to listOf("x"),
                ),
            )

        assertThat(result.response.status).isEqualTo(400)
        assertThat(groupsOf(performGet())["GENERAL"]).containsExactly("summary", "status", "cf_severity")
        verify(exactly = 0) { settingsRepository.replaceDetailViewFields(any(), "BOGUS", any()) }
        verify(exactly = 0) { settingsRepository.replaceDetailViewFields(any(), "GENERAL", listOf("overwritten")) }
    }

    @Test
    fun `그룹 이름은 대소문자를 가린다`() {
        // DB CHECK 는 대문자 4종만 받는다. 대소문자를 뭉개는 검증은 통과시켜 놓고 DB 에서 터져 500 이 된다.
        val result = patchGroups(mapOf("general" to GENERAL_FIELDS))

        assertThat(result.response.status).isEqualTo(400)
        verify(exactly = 0) { settingsRepository.replaceDetailViewFields(any(), any(), any()) }
    }

    // ── G·H. 권한 ────────────────────────────────────────────────────────────

    @Test
    fun `PATCH 는 SOFT_DELETE 를 프로젝트 스코프로 판정하고 거부되면 저장하지 않는다`() {
        permissionGate.allowAll = false

        val result = patchGroups(mapOf("GENERAL" to GENERAL_FIELDS))

        assertThat(result.response.status).isEqualTo(403)
        // 권한코드·스코프까지 고정한다 — 「아무 권한이나 물어보는」 구현도 403 만으로는 통과한다.
        assertThat(permissionGate.calls)
            .containsExactly(Triple(actorId, IssuePermission.SOFT_DELETE, IssueScope.Project(projectKey)))
        verify(exactly = 0) { settingsRepository.replaceDetailViewFields(any(), any(), any()) }
    }

    @Test
    fun `GET 은 BROWSE 로 판정하고 거부되면 403 이다`() {
        val allowed = performGet()
        assertThat(allowed.response.status).isEqualTo(200)
        assertThat(permissionGate.calls)
            .containsExactly(Triple(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey)))

        permissionGate.allowAll = false
        assertThat(performGet().response.status).isEqualTo(403)
    }

    // ── I. 미인증 ────────────────────────────────────────────────────────────

    @Test
    fun `미인증 요청은 401 이고 보드 조회조차 하지 않는다`() {
        // actor 추출이 리소스 조회보다 먼저여야 미인증자가 보드 존재를 probe 할 수 없다.
        SecurityContextHolder.clearContext()

        assertThat(performGet().response.status).isEqualTo(401)
        assertThat(patchGroups(mapOf("GENERAL" to GENERAL_FIELDS)).response.status).isEqualTo(401)
        verify(exactly = 0) { boardRepository.findById(any()) }
        verify(exactly = 0) { settingsRepository.replaceDetailViewFields(any(), any(), any()) }
    }

    // ── J. 검사 순서 ─────────────────────────────────────────────────────────

    @Test
    fun `보드가 없으면 권한 거부와 겹쳐도 404 다`() {
        // 403↔404 는 검사 순서로 의미가 뒤집힌다. 기존 경로(loadBoardWithCreate)와 같은 순서
        // — 존재 확인 먼저, 권한 판정 나중 — 를 이 단언이 고정한다.
        every { boardRepository.findById(any()) } returns null
        permissionGate.allowAll = false

        assertThat(patchGroups(mapOf("GENERAL" to GENERAL_FIELDS)).response.status).isEqualTo(404)
        assertThat(performGet().response.status).isEqualTo(404)
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun allFourGroups(): Map<String, List<String>> =
        linkedMapOf(
            "GENERAL" to GENERAL_FIELDS,
            "DATE" to DATE_FIELDS,
            "PEOPLE" to PEOPLE_FIELDS,
            "LINKS" to LINKS_FIELDS,
        )

    private fun performGet(): MvcResult = mockMvc.perform(get(PATH, boardId)).andReturn()

    private fun patchGroups(groups: Map<String, List<String>>): MvcResult =
        mockMvc.perform(
            patch(PATH, boardId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("groups" to groups))),
        ).andReturn()

    /** 응답 봉투(`data.groups`)를 순서가 보존되는 맵으로 꺼낸다. */
    private fun groupsOf(result: MvcResult): Map<String, List<String>> {
        val body = mapper.readTree(result.response.contentAsString)
        return mapper.convertValue(
            body.path("data").path("groups"),
            object : TypeReference<LinkedHashMap<String, List<String>>>() {},
        )
    }

    private fun sampleBoard(): Board =
        Board(
            id = boardId,
            projectKey = projectKey,
            name = "BTS 개발 보드",
            columns = listOf(BoardColumn(UUID.randomUUID(), listOf("open"), "열림", "TODO", 0)),
            createdAt = Instant.parse("2026-06-20T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-20T00:00:00Z"),
        )
}
