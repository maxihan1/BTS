// 보드 조회 응답이 설정 4탭을 함께 싣는지 재는 실 DB 통합 테스트 (부채 177 Task 31 · N1 · R6)
@file:Suppress("DEPRECATION") // JooqExceptionTranslator: Spring Boot 3.3 deprecated, package-private 후계 미공개

package com.bts.agileplanning.web

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardType
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.repository.BoardSettingsRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.jooq.SQLDialect
import org.jooq.impl.DataSourceConnectionProvider
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.DefaultDSLContext
import org.jooq.impl.DefaultExecuteListenerProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.jooq.JooqExceptionTranslator
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Collections
import java.util.UUID
import javax.sql.DataSource

/**
 * `GET /api/v1/boards/{id}` 가 설정 4탭을 **함께** 싣는지 재는 실 DB end-to-end 테스트 (부채 177 Task 31).
 *
 * ## 무엇을 막는 테스트인가 (스펙 N1)
 * 신규 탭 넷 중 GET 을 가진 것은 상세 보기 필드(T13) 하나뿐이다. 보드 조회가 설정을 안 실으면
 * 네 탭이 전부 「**저장은 되는데 새로고침하면 비어 있다**」가 되고, 카드가 구성을 읽을 원천도 없다.
 * 그래서 이 클래스는 **저장 → 조회 왕복**을 잰다 — 「필드가 있다」만 재면 항상 빈 값을 내는 구현이
 * 통과한다.
 *
 * ## ★null 이 두 층이라 뭉개면 안 된다 (스펙 R6)
 * `workingDays.standardDays` 의 `null` 은 **미설정 = 달력일 전부(현행 유지)** 다. 빈 배열로 뭉개면
 * 프론트가 「미설정」과 「0개 설정」을 못 가르고, 그 구분이 무너지면 기존 모든 스프린트의 번다운이
 * 배포 순간 바뀐다. 그래서 미설정 대조군은 **키의 존재 + 값이 null** 을 함께 단언한다
 * (`jsonPath(...).value(null)` 은 키가 없어도 통과할 수 있어 쓰지 않는다 —
 * [BoardControllerIntegrationTest] 의 NULL-1 이 같은 함정을 이미 이름 붙였다).
 *
 * ## 각 축이 무엇과 무엇을 가르나
 *
 * | 축 | 무엇 | 느슨한 구현 ↔ 올바른 구현 |
 * |---|---|---|
 * | ① 왕복 | 네 탭에 **서로 다른** 값 저장 후 GET 1회 | 빈 값·요청 echo ↔ 저장된 값을 읽음 |
 * | ② 미설정 대조군 | 아무것도 저장 안 한 보드 | 기본값(월~금)을 채움 · null→[] 뭉갬 ↔ 미설정 유지 (R6) |
 * | ③ 보드 격리 | 옆 보드 설정이 안 샌다 | boardId 를 잃은 조회 ↔ 보드별 조회 |
 * | ④ 계약 일치 | 탭 응답 payload == 조회 응답 필드 | 모양이 갈려 프론트가 두 벌 파싱 ↔ 한 벌 |
 * | ⑤ 쿼리 수 | 설정 양이 늘어도 SQL 문 수가 그대로 | 뷰별·그룹별·날짜별 조회 ↔ 축당 1회 (N1) |
 * | ⑥ 칸반 pass-through | 칸반도 저장된 `timeTracking` 그대로 | 칸반이면 NONE 으로 갈음 ↔ 그대로 (E6) |
 *
 * ★**①②는 짝으로만 산다.** ① 만 두면 「전부 기본값을 채우는」 구현이 통과하고, ② 만 두면
 * 「무엇이든 빈 값을 내는」 구현이 통과한다.
 *
 * ## ★⑤ 의 쿼리 수 가드가 **실제로 세는 것** — 읽고 나서 믿어라
 * [QueryCountListener] 는 jOOQ `executeStart` 이벤트를 센다. 즉 **애플리케이션 [DSLContext] 를 통해
 * 실행된 SQL 문**만 세어진다. 다음은 세지 **않는다**.
 * - jOOQ 를 거치지 않은 JDBC (이 BC 에는 없다)
 * - cross-BC 포트 stub 호출([com.bts.shared.board.BoardIssueLookupPort] 등은 SQL 을 실행하지 않는다)
 * - 권한 resolver stub 판정
 *
 * 이 구분이 중요한 이유는 「쿼리 카운트 가드가 N+1 을 잡는다」는 전제가 이 PR 에서 한 번
 * **틀린 것으로 판명**됐기 때문이다(리스너가 SQL 문만 세는데 문제의 호출이 SQL 을 안 탔다).
 * 그래서 이 클래스는 문 **수**만 세지 않고, 잡은 SQL 안에 설정 테이블 이름이 **실제로 있는지**를
 * 함께 단언한다 — 가드가 자기가 지킨다고 주장하는 코드 경로를 정말 보고 있음을 그 자리에서 보인다.
 * 설정을 아예 안 읽는 구현에서는 그 단언이 0 을 보고 red 가 된다(RED 단계 실측).
 *
 * `time_tracking` · `working_days` 는 `boards` 의 칸이라 `selectFrom(BOARDS)` 를 하는
 * [BoardRepository.findById] 의 SQL 에도 이름이 나온다 — 그래서 그 둘은 문 수를 못 고정한다.
 * 데이터 양에 따라 늘 수 있는 축은 자식 테이블 셋(`board_card_layout_fields` ·
 * `board_detail_view_fields` · `board_non_working_dates`)뿐이고, 그 셋에 「행이 몇 개든 문은 1개」를
 * 건다. 나머지는 **보드 3개(설정 많음 / 적음 / 없음)의 총 문 수가 같다**로 덮는다.
 */
@SpringBootTest(
    classes = [AgilePlanningTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(AgilePlanningTestcontainersConfig::class, BoardSettingsReadApiTest.CountingDslConfig::class)
@ActiveProfiles("test")
// detekt VarCouldBeVal 오탐 — 필드 주입은 `lateinit var` 뿐이고 `lateinit` 은 val 에 못 쓴다.
@Suppress("VarCouldBeVal")
class BoardSettingsReadApiTest {
    /**
     * 실행된 SQL 문을 기록하는 [ExecuteListener].
     *
     * 수만 세지 않고 **문장 자체**를 모은다 — 어느 테이블을 몇 번 읽었는지까지 봐야
     * 「가드가 무엇을 세는지」를 테스트 안에서 증명할 수 있다.
     */
    class QueryCountListener : ExecuteListener {
        private val statements: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun executeStart(ctx: ExecuteContext) {
            statements.add(ctx.sql().orEmpty())
        }

        /** 다음 계측을 위해 기록을 비운다. */
        fun reset() = statements.clear()

        /** 지금까지 기록된 SQL 문 목록의 스냅샷. */
        fun captured(): List<String> = statements.toList()
    }

    /**
     * 계측 리스너를 단 [DSLContext] 를 `@Primary` 로 얹는다.
     *
     * [AgilePlanningTestcontainersConfig.dslContext] 와 **같은 방식**으로 만든다
     * (`TransactionAwareDataSourceProxy` + [JooqExceptionTranslator]) — 계측 때문에 트랜잭션 참여나
     * 예외 변환이 달라지면 세는 대상이 프로덕션 경로가 아니게 된다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    class CountingDslConfig {
        @Bean
        fun queryCountListener(): QueryCountListener = QueryCountListener()

        @Bean
        @Primary
        fun countingDslContext(
            dataSource: DataSource,
            listener: QueryCountListener,
        ): DSLContext {
            val configuration =
                DefaultConfiguration()
                    .set(DataSourceConnectionProvider(TransactionAwareDataSourceProxy(dataSource)))
                    .set(SQLDialect.POSTGRES)
                    .set(
                        DefaultExecuteListenerProvider(JooqExceptionTranslator()),
                        DefaultExecuteListenerProvider(listener),
                    )
            return DefaultDSLContext(configuration)
        }
    }

    @Autowired
    private lateinit var wac: WebApplicationContext

    @Autowired
    private lateinit var boardRepository: BoardRepository

    @Autowired
    private lateinit var settingsRepository: BoardSettingsRepository

    @Autowired
    private lateinit var queryCountListener: QueryCountListener

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper()
    private val actorId: UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    // ── ① 저장 → 조회 왕복 ────────────────────────────────────────────────────

    @Test
    fun `보드 조회 한 번이 네 탭에 저장한 서로 다른 값을 모두 돌려준다`() {
        // 네 축에 **서로 다른** 값을 넣는다 — 같은 값을 넣으면 축을 뒤바꾼 구현도 통과한다.
        // BOARD 는 알파벳 역순이라 키를 정렬하는 구현이 여기서 갈린다.
        val board = insertBoard(BoardType.SCRUM)
        saveCardLayout(board.id, """{"cardLayout":{"BOARD":["PRIORITY","EPIC"],"BACKLOG":["ESTIMATE"]}}""")
        saveEstimation(board.id, """{"timeTracking":"REMAINING_AND_SPENT"}""")
        saveWorkingDays(
            board.id,
            """{"standardDays":["WED","MON"],"nonWorkingDates":["2026-10-05","2026-10-03"],"timezone":"Asia/Seoul"}""",
        )
        saveDetailViewFields(board.id, """{"groups":{"PEOPLE":["assignee","reporter"],"DATE":["dueDate"]}}""")

        val data = getBoard(board.id)

        assertThat(SETTINGS_FIELDS.filterNot(data::has))
            .describedAs("N1 — 보드 조회 응답이 설정 4종을 실어야 추가 왕복이 생기지 않는다")
            .isEmpty()
        assertThat(texts(data, "cardLayout", "BOARD"))
            .describedAs("저장한 순서 그대로여야 한다 — position 이 곧 카드에서의 자리다")
            .containsExactly("PRIORITY", "EPIC")
        assertThat(texts(data, "cardLayout", "BACKLOG"))
            .describedAs("뷰마다 다른 구성이다(R3) — 한 목록을 공유하는 구현이 여기서 죽는다")
            .containsExactly("ESTIMATE")
        assertThat(data.path("timeTracking").asText()).isEqualTo("REMAINING_AND_SPENT")
        assertThat(texts(data, "workingDays", "standardDays")).containsExactly("MON", "WED")
        assertThat(texts(data, "workingDays", "nonWorkingDates")).containsExactly("2026-10-03", "2026-10-05")
        assertThat(data.path("workingDays").path("timezone").asText()).isEqualTo("Asia/Seoul")
        assertThat(texts(data, "detailViewFields", "PEOPLE")).containsExactly("assignee", "reporter")
        assertThat(texts(data, "detailViewFields", "DATE")).containsExactly("dueDate")
    }

    // ── ② 미설정 대조군 (R6) ──────────────────────────────────────────────────

    @Test
    fun `설정을 한 번도 저장하지 않은 보드는 미설정을 미설정대로 돌려준다`() {
        // ① 의 짝이다. ① 만 두면 「전부 기본값(월~금)을 채우는」 구현이 통과한다.
        val board = insertBoard(BoardType.SCRUM)

        val data = getBoard(board.id)
        val workingDays = data.path("workingDays")

        assertThat(workingDays.has("standardDays"))
            .describedAs("키 자체가 사라지면 프론트가 파싱 단계에서 깨진다(NULL-1 과 같은 계약)")
            .isTrue()
        assertThat(workingDays.path("standardDays").isNull)
            .describedAs("R6 — 미설정은 null 이다. 빈 배열로 뭉개면 「달력일 전부」와 「0개 설정」이 갈리지 않는다")
            .isTrue()
        assertThat(workingDays.path("nonWorkingDates").size()).isZero()
        assertThat(workingDays.path("timezone").isNull).isTrue()
        assertThat(data.path("timeTracking").asText())
            .describedAs("time_tracking 은 NOT NULL DEFAULT 'NONE' 이다")
            .isEqualTo("NONE")
        assertThat(data.path("cardLayout").size())
            .describedAs("빈 구성은 「현행 카드를 그린다」는 뜻이다 — 기본 필드를 채우면 안 된다")
            .isZero()
        assertThat(data.path("detailViewFields").fieldNames().asSequence().toList())
            .describedAs("구성이 없는 그룹도 빈 목록으로 항상 실린다(R7c · T13 과 같은 모양)")
            .containsExactly("GENERAL", "DATE", "PEOPLE", "LINKS")
        assertThat(data.path("detailViewFields").path("GENERAL").size()).isZero()
    }

    // ── ③ 보드 격리 ───────────────────────────────────────────────────────────

    @Test
    fun `보드마다 자기 설정을 돌려준다`() {
        val mine = insertBoard(BoardType.SCRUM)
        val other = insertBoard(BoardType.SCRUM)
        saveCardLayout(mine.id, """{"cardLayout":{"BOARD":["EPIC"]}}""")
        saveCardLayout(other.id, """{"cardLayout":{"BOARD":["LABELS"]}}""")
        saveEstimation(other.id, """{"timeTracking":"REMAINING_AND_SPENT"}""")

        val mineData = getBoard(mine.id)
        val otherData = getBoard(other.id)

        assertThat(texts(mineData, "cardLayout", "BOARD")).containsExactly("EPIC")
        assertThat(mineData.path("timeTracking").asText())
            .describedAs("옆 보드의 설정이 새어 들어오면 안 된다")
            .isEqualTo("NONE")
        assertThat(texts(otherData, "cardLayout", "BOARD")).containsExactly("LABELS")
        assertThat(otherData.path("timeTracking").asText()).isEqualTo("REMAINING_AND_SPENT")
    }

    // ── ④ 탭 응답과 조회 응답의 계약 일치 ─────────────────────────────────────

    @Test
    fun `탭이 돌려주는 설정과 보드 조회가 돌려주는 설정이 JSON 으로 같다`() {
        // 모양이 갈리면 프론트가 같은 값에 파서를 두 벌 둬야 한다. 특히 상세 보기 필드는 탭이
        // 그룹 4종을 **항상** 채워 주므로(R7c) 조회도 그래야 한다 — 원본 맵을 그대로 흘리면 갈린다.
        val board = insertBoard(BoardType.SCRUM)
        val cardLayoutTab =
            saveCardLayout(board.id, """{"cardLayout":{"BOARD":["PRIORITY","EPIC"],"BACKLOG":["ESTIMATE"]}}""")
        val estimationTab = saveEstimation(board.id, """{"timeTracking":"REMAINING_AND_SPENT"}""")
        val workingDaysTab =
            saveWorkingDays(
                board.id,
                """{"standardDays":["FRI","MON"],"nonWorkingDates":["2026-12-25"],"timezone":"UTC"}""",
            )
        val detailViewTab = saveDetailViewFields(board.id, """{"groups":{"LINKS":["issueLinks"]}}""")

        val data = getBoard(board.id)

        assertThat(data.path("cardLayout")).isEqualTo(cardLayoutTab.path("cardLayout"))
        assertThat(data.path("timeTracking")).isEqualTo(estimationTab.path("timeTracking"))
        assertThat(data.path("workingDays")).isEqualTo(workingDaysTab)
        assertThat(data.path("detailViewFields")).isEqualTo(detailViewTab.path("groups"))
    }

    // ── ⑤ 쿼리 수 가드 (N1) ───────────────────────────────────────────────────

    @Test
    fun `설정이 늘어도 보드 조회의 SQL 문 수는 늘지 않는다`() {
        val configured = insertBoard(BoardType.SCRUM)
        saveCardLayout(configured.id, """{"cardLayout":{"BOARD":["PRIORITY","EPIC"],"BACKLOG":["ESTIMATE"]}}""")
        saveDetailViewFields(
            configured.id,
            """{"groups":{"GENERAL":["status"],"DATE":["dueDate"],"PEOPLE":["assignee"],"LINKS":["issueLinks"]}}""",
        )
        saveWorkingDays(
            configured.id,
            """{"standardDays":["MON","TUE"],"nonWorkingDates":["2026-10-03","2026-10-05","2026-10-09"]}""",
        )
        val minimal = insertBoard(BoardType.SCRUM)
        saveCardLayout(minimal.id, """{"cardLayout":{"BOARD":["EPIC"]}}""")
        saveDetailViewFields(minimal.id, """{"groups":{"GENERAL":["status"]}}""")
        val untouched = insertBoard(BoardType.SCRUM)

        val configuredSql = captureBoardGetSql(configured.id)
        val minimalSql = captureBoardGetSql(minimal.id)
        val untouchedSql = captureBoardGetSql(untouched.id)

        // 가드가 자기가 지킨다고 주장하는 경로를 정말 보고 있는지 — 뷰 2개·그룹 4개·날짜 3개인데도 각 1문.
        assertThat(configuredSql.count { it.contains("board_card_layout_fields") })
            .describedAs("뷰가 둘이어도 카드 레이아웃 조회는 1문이다 — 뷰별로 나누면 여기서 는다")
            .isEqualTo(1)
        assertThat(configuredSql.count { it.contains("board_detail_view_fields") })
            .describedAs("그룹이 넷이어도 상세 보기 필드 조회는 1문이다 — 그룹별로 나누면 4문이 된다")
            .isEqualTo(1)
        assertThat(configuredSql.count { it.contains("board_non_working_dates") })
            .describedAs("비근무일이 셋이어도 1문이다 — 날짜별로 나누면 3문이 된다")
            .isEqualTo(1)

        assertThat(configuredSql.size)
            .describedAs("설정 양에 따라 문 수가 늘면 안 된다(N1) — 설정 많음 vs 없음\n%s", configuredSql)
            .isEqualTo(untouchedSql.size)
        assertThat(minimalSql.size)
            .describedAs("설정 적음 vs 없음")
            .isEqualTo(untouchedSql.size)
    }

    // ── ⑥ 칸반 pass-through (E6) ──────────────────────────────────────────────

    @Test
    fun `칸반 보드도 저장된 시간 추적 값을 그대로 돌려준다`() {
        // 스크럼이었다가 칸반이 된 보드다(E6) — 값을 지우지 않고, 무시할지는 읽는 쪽이 board_type 으로
        // 정한다. 조회가 여기서 NONE 으로 갈음하면 되돌렸을 때 값이 살아나지 않는다.
        val board = insertBoard(BoardType.KANBAN)
        settingsRepository.updateTimeTracking(board.id, "REMAINING_AND_SPENT")

        val data = getBoard(board.id)

        assertThat(data.path("timeTracking").asText()).isEqualTo("REMAINING_AND_SPENT")
        assertThat(data.path("boardType").asText()).isEqualTo("KANBAN")
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────

    private fun saveCardLayout(
        boardId: UUID,
        body: String,
    ): JsonNode = okData(patch("/api/v1/boards/$boardId/card-layout").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun saveEstimation(
        boardId: UUID,
        body: String,
    ): JsonNode = okData(patch("/api/v1/boards/$boardId/estimation").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun saveWorkingDays(
        boardId: UUID,
        body: String,
    ): JsonNode = okData(put("/api/v1/boards/$boardId/working-days").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun saveDetailViewFields(
        boardId: UUID,
        body: String,
    ): JsonNode =
        okData(
            patch("/api/v1/boards/$boardId/detail-view-fields")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    private fun getBoard(boardId: UUID): JsonNode = okData(get("/api/v1/boards/$boardId"))

    /** 200 을 확인하고 `{ data: ... }` 봉투를 벗긴 [JsonNode] 를 준다. */
    private fun okData(request: org.springframework.test.web.servlet.RequestBuilder): JsonNode {
        val response = mockMvc.perform(request).andExpect(status().isOk).andReturn().response
        return mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("data")
    }

    /** 리스너를 비우고 보드 조회 1회를 수행한 뒤, 그동안 실행된 SQL 문 목록을 준다. */
    private fun captureBoardGetSql(boardId: UUID): List<String> {
        queryCountListener.reset()
        getBoard(boardId)
        return queryCountListener.captured()
    }

    /** `data.<field>.<key>` 배열을 문자열 목록으로 좁힌다. */
    private fun texts(
        data: JsonNode,
        field: String,
        key: String,
    ): List<String> = data.path(field).path(key).map { it.asText() }

    private fun insertBoard(boardType: BoardType): Board =
        boardRepository.insert(
            Board(
                id = UUID.randomUUID(),
                projectKey = "BS${UUID.randomUUID().toString().take(4).uppercase()}",
                name = "설정 조회 테스트 보드",
                columns = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                boardType = boardType,
            ),
        )

    private companion object {
        /** 스펙 §API 가 정한 설정 4종의 응답 키. */
        val SETTINGS_FIELDS = listOf("cardLayout", "timeTracking", "workingDays", "detailViewFields")
    }
}
