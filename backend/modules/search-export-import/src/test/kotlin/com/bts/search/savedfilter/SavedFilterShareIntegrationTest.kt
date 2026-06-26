// 저장된 필터 공유 가시성 풀스택 통합테스트 — userId-aware 멤버십 stub + Testcontainers PG 실 DB (FR-SR-03 Task 9)

package com.bts.search.savedfilter

import com.bts.search.jooq.tables.references.SAVED_FILTERS
import com.bts.search.jooq.tables.references.SAVED_FILTER_SHARES
import com.bts.search.savedfilter.application.SavedFilterService
import com.bts.search.savedfilter.persistence.JooqSavedFilterRepository
import com.bts.search.savedfilter.persistence.JooqSavedFilterShareRepository
import com.bts.search.savedfilter.web.SavedFilterController
import com.bts.search.savedfilter.web.SavedFilterExceptionHandler
import com.bts.search.savedfilter.web.SavedFilterSearchController
import com.bts.shared.membership.GroupMembershipPort
import com.bts.shared.membership.ProjectMembershipPort
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * 저장된 필터 공유 가시성 풀스택 통합테스트.
 *
 * 컨트롤러 → 서비스 → jOOQ Repository → 실 PostgreSQL(Testcontainers — 테스트용 DB를
 * 도커로 자동 실행하는 라이브러리) end-to-end. [SavedFilterIntegrationTest] 의 형제 테스트이며
 * PR2 공유 경로를 담당한다.
 *
 * ## 멤버십 stub 분리(C11 방지)
 * [GroupMembershipPort] / [ProjectMembershipPort] 는 [MembershipPortTestConfig] 의
 * userId-aware stub 으로 대체된다. [IntegrationConfig] 는 이 두 포트를 직접 선언하지 않고
 * [MembershipPortTestConfig] 가 컨텍스트에서 제공하는 빈을 주입받는다.
 * userId 무관 동일집합 stub 을 사용하면 음성 케이스(비멤버 → 404)가 가짜통과하므로 금지한다.
 *
 * ## 검증 범위
 * - 가시성 4경로: owner / AUTHENTICATED / PROJECT(멤버 bob) / GROUP(소속 carol)
 * - 음성(vacuous 방지): 비멤버(eve) → 404, 공유 없는 필터 → 404
 * - 403/404 분기: 가시-비소유 PUT/DELETE → 403(detail id 미노출), 비가시 → 404
 * - CASCADE(EC6): 필터 DELETE 후 saved_filter_shares 행 0 DB 직접 확인
 * - GET /shared(EC14/C1): 비소유 공유 필터만, 소유 필터 제외, 페이지네이션
 * - parity(B3): GET /{id} 200 ⟺ GET /shared 결과 포함 양방향
 * - 실행 권한 상승 0(FR-8): 공유받은 actor 로 /{id}/search → viewerUserId=actor 전달 확인
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(
    classes = [SavedFilterShareIntegrationTest.IntegrationConfig::class, MembershipPortTestConfig::class],
)
@WebAppConfiguration
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SavedFilterShareIntegrationTest {
    /**
     * 풀스택 테스트 전용 Spring 컨텍스트.
     *
     * 실 DataSource(Testcontainers) + Flyway V600~ + DSLContext + Repository +
     * Service + 두 컨트롤러 + 예외 핸들러를 조립한다.
     * GroupMembershipPort / ProjectMembershipPort 는 [MembershipPortTestConfig] 에서 주입받는다.
     * IssueSearchPort 는 검색 쿼리를 캡처하는 stub 으로 대체(FR-8 viewerUserId 검증).
     */
    @Configuration
    @EnableWebMvc
    open class IntegrationConfig {
        @Bean
        open fun dslContext(): DSLContext {
            Flyway
                .configure()
                .dataSource(pg.jdbcUrl, pg.username, pg.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/search-export-import")
                .load()
                .migrate()
            val dataSource = DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password)
            return DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @Bean
        open fun repository(dsl: DSLContext): JooqSavedFilterRepository = JooqSavedFilterRepository(dsl)

        @Bean
        open fun shareRepository(dsl: DSLContext): JooqSavedFilterShareRepository = JooqSavedFilterShareRepository(dsl)

        /**
         * 멤버십 포트는 [MembershipPortTestConfig] 에서 주입받는다.
         * userId-aware stub 이 필요하므로 이 config 에서 emptySet stub 을 선언하지 않는다(C11 방지).
         */
        @Bean
        open fun service(
            repository: JooqSavedFilterRepository,
            shareRepository: JooqSavedFilterShareRepository,
            groupMembershipPort: GroupMembershipPort,
            projectMembershipPort: ProjectMembershipPort,
        ): SavedFilterService {
            return SavedFilterService(repository, shareRepository, groupMembershipPort, projectMembershipPort)
        }

        /**
         * IssueSearchPort 쿼리 캡처 stub — FR-8 viewerUserId 검증용.
         * 마지막 수신 쿼리를 [SavedFilterShareIntegrationTest.lastSearchQuery] 에 기록한다.
         */
        @Bean
        open fun stubIssueSearchPort(): IssueSearchPort =
            object : IssueSearchPort {
                override fun search(query: IssueSearchQuery): IssueSearchPage {
                    SavedFilterShareIntegrationTest.lastSearchQuery = query
                    return IssueSearchPage.empty(query.page, query.size)
                }
            }

        @Bean
        open fun controller(service: SavedFilterService): SavedFilterController = SavedFilterController(service)

        @Bean
        open fun searchController(
            service: SavedFilterService,
            port: IssueSearchPort,
        ): SavedFilterSearchController = SavedFilterSearchController(service, port)

        @Bean
        open fun exceptionHandler(): SavedFilterExceptionHandler = SavedFilterExceptionHandler()
    }

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var dsl: DSLContext

    /** 테스트가 멤버십 시드를 조작할 수 있도록 주입받는 stub 설정 빈. */
    @Autowired
    private lateinit var membershipConfig: MembershipPortTestConfig

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    // 테스트용 고정 actor UUID — 역할별 의미 부여로 시나리오 이해를 돕는다
    private val alice = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001") // 소유자
    private val bob = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002") // PROJECT ATL 멤버
    private val carol = UUID.fromString("cccccccc-0000-0000-0000-000000000003") // GROUP g-devs 소속
    private val dave = UUID.fromString("dddddddd-0000-0000-0000-000000000004") // 임의 인증 사용자
    private val eve = UUID.fromString("eeeeeeee-0000-0000-0000-000000000005") // 멤버십 미시드(fail-closed)

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // saved_filters 삭제 → FK ON DELETE CASCADE 로 saved_filter_shares 도 정리됨
        dsl.deleteFrom(SAVED_FILTERS).execute()
        // 멤버십 시드 리셋 후 표준 시드 적용
        membershipConfig.groupMemberships.clear()
        membershipConfig.projectMemberships.clear()
        membershipConfig.projectMemberships[bob] = setOf("ATL")
        membershipConfig.groupMemberships[carol] = setOf("g-devs")
        // 검색 쿼리 캡처 리셋
        lastSearchQuery = null
        authenticate(alice)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── 가시성 4경로 ──────────────────────────────────────────────────────────

    @Test
    fun `가시성 - owner는 공유 없이도 자신의 필터를 조회할 수 있고 isOwner=true이다`() {
        val id = createFilter("내 필터", "null")
        authenticate(alice)
        mockMvc.perform(get("/api/v1/filters/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.isOwner").value(true))
            .andExpect(jsonPath("$.shares.length()").value(0))
    }

    @Test
    fun `가시성 - AUTHENTICATED 공유 필터는 임의 인증 사용자(dave)가 열람할 수 있고 shares가 반환된다`() {
        val id = createFilter("공개 필터", """[{"shareType":"AUTHENTICATED"}]""")
        authenticate(dave)
        mockMvc.perform(get("/api/v1/filters/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.isOwner").value(false))
            .andExpect(jsonPath("$.shares.length()").value(1))
            .andExpect(jsonPath("$.shares[0].shareType").value("AUTHENTICATED"))
    }

    @Test
    fun `가시성 - PROJECT 공유 필터는 해당 프로젝트 멤버(bob)가 열람할 수 있고 shares가 반환된다`() {
        val id = createFilter("ATL 전용", """[{"shareType":"PROJECT","targetId":"ATL"}]""")
        authenticate(bob)
        mockMvc.perform(get("/api/v1/filters/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.isOwner").value(false))
            .andExpect(jsonPath("$.shares.length()").value(1))
            .andExpect(jsonPath("$.shares[0].shareType").value("PROJECT"))
            .andExpect(jsonPath("$.shares[0].targetId").value("ATL"))
    }

    @Test
    fun `가시성 - GROUP 공유 필터는 해당 그룹 소속(carol)이 열람할 수 있고 shares가 반환된다`() {
        val id = createFilter("g-devs 전용", """[{"shareType":"GROUP","targetId":"g-devs"}]""")
        authenticate(carol)
        mockMvc.perform(get("/api/v1/filters/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.isOwner").value(false))
            .andExpect(jsonPath("$.shares.length()").value(1))
            .andExpect(jsonPath("$.shares[0].shareType").value("GROUP"))
            .andExpect(jsonPath("$.shares[0].targetId").value("g-devs"))
    }

    // ── 음성(vacuous 방지) ────────────────────────────────────────────────────

    @Test
    fun `음성 - 비멤버(eve)는 PROJECT 공유 필터를 열람할 수 없다 — 존재 은닉 404`() {
        // eve 는 projectMemberships 미시드 → emptySet(fail-closed) → projectKey 미매칭 → 비가시
        val id = createFilter("ATL 전용", """[{"shareType":"PROJECT","targetId":"ATL"}]""")
        authenticate(eve)
        mockMvc.perform(get("/api/v1/filters/$id"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `음성 - 미소속(eve)은 GROUP 공유 필터를 열람할 수 없다 — 존재 은닉 404`() {
        // eve 는 groupMemberships 미시드 → emptySet(fail-closed) → groupId 미매칭 → 비가시
        val id = createFilter("g-devs 전용", """[{"shareType":"GROUP","targetId":"g-devs"}]""")
        authenticate(eve)
        mockMvc.perform(get("/api/v1/filters/$id"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `음성 - 공유 없는 PRIVATE 필터는 비소유자가 열람할 수 없다 — 존재 은닉 404`() {
        val id = createFilter("PRIVATE 필터", "null")
        authenticate(bob) // bob 은 ATL 멤버지만 공유 자체가 없으므로 비가시
        mockMvc.perform(get("/api/v1/filters/$id"))
            .andExpect(status().isNotFound)
    }

    // ── 403/404 분기 — 수정/삭제 권한 게이트 ───────────────────────────────────

    @Test
    fun `403 - 공유받은 비소유자(bob)가 PUT 시도 시 403, detail에 필터 id 미노출`() {
        val id = createFilter("ATL 공유 필터", """[{"shareType":"PROJECT","targetId":"ATL"}]""")
        authenticate(bob) // bob 은 가시(PROJECT 멤버)지만 소유자가 아님
        mockMvc.perform(
            put("/api/v1/filters/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"탈취","aqlQuery":"status = open","version":0}"""),
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value("이 작업을 수행할 권한이 없습니다."))
    }

    @Test
    fun `403 - 공유받은 비소유자(bob)가 DELETE 시도 시 403, detail에 필터 id 미노출`() {
        val id = createFilter("ATL 공유 필터", """[{"shareType":"PROJECT","targetId":"ATL"}]""")
        authenticate(bob)
        mockMvc.perform(delete("/api/v1/filters/$id"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value("이 작업을 수행할 권한이 없습니다."))
    }

    @Test
    fun `404 - 비가시 사용자(eve)의 PUT 시도 시 404 — 존재 은닉`() {
        val id = createFilter("ATL 전용", """[{"shareType":"PROJECT","targetId":"ATL"}]""")
        authenticate(eve)
        mockMvc.perform(
            put("/api/v1/filters/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"탈취","aqlQuery":"status = open","version":0}"""),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `404 - 비가시 사용자(eve)의 DELETE 시도 시 404 — 존재 은닉`() {
        val id = createFilter("ATL 전용", """[{"shareType":"PROJECT","targetId":"ATL"}]""")
        authenticate(eve)
        mockMvc.perform(delete("/api/v1/filters/$id"))
            .andExpect(status().isNotFound)
    }

    // ── CASCADE(EC6) ─────────────────────────────────────────────────────────

    @Test
    fun `CASCADE(EC6) - 필터 DELETE 시 saved_filter_shares 행이 FK CASCADE로 함께 제거된다`() {
        val id = createFilter("공유 필터", """[{"shareType":"AUTHENTICATED"}]""")
        val filterId = UUID.fromString(id)

        // 삭제 전: shares 1행 존재 확인
        val countBefore = dsl.fetchCount(SAVED_FILTER_SHARES, SAVED_FILTER_SHARES.FILTER_ID.eq(filterId))
        assertThat(countBefore).isEqualTo(1)

        // 소유자(alice)가 필터 삭제
        authenticate(alice)
        mockMvc.perform(delete("/api/v1/filters/$id"))
            .andExpect(status().isNoContent)

        // 삭제 후: shares 0행 — CASCADE 동작 검증
        val countAfter = dsl.fetchCount(SAVED_FILTER_SHARES, SAVED_FILTER_SHARES.FILTER_ID.eq(filterId))
        assertThat(countAfter).isEqualTo(0)
    }

    // ── GET /shared (EC14/C1) ─────────────────────────────────────────────────

    @Test
    fun `shared - 소유자(alice)는 자신의 AUTHENTICATED 공유 필터가 shared 목록에 포함되지 않는다`() {
        createFilter("공개 필터", """[{"shareType":"AUTHENTICATED"}]""")
        authenticate(alice)
        mockMvc.perform(get("/api/v1/filters/shared"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `shared - 공유받은 사용자(dave)는 AUTHENTICATED 필터를 shared 목록에서 볼 수 있다`() {
        val id = createFilter("공개 필터", """[{"shareType":"AUTHENTICATED"}]""")
        authenticate(dave)
        mockMvc.perform(get("/api/v1/filters/shared"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(id))
            .andExpect(jsonPath("$[0].isOwner").value(false))
    }

    @Test
    fun `shared - bob의 shared 목록은 alice 소유 ATL 공유 필터만 포함하며 bob 소유 필터는 제외된다`() {
        // alice 소유 + ATL 공유 → bob 에게 보여야 함
        val sharedId = createFilter("ATL 공유", """[{"shareType":"PROJECT","targetId":"ATL"}]""")
        // bob 이 직접 소유하는 필터 → shared 목록에 포함되면 안 됨
        createFilterAs(bob, "밥의 필터", "null")

        authenticate(bob)
        val json =
            mockMvc.perform(get("/api/v1/filters/shared"))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        val list = mapper.readValue<List<Map<String, Any?>>>(json)
        assertThat(list).hasSize(1)
        assertThat(list[0]["id"].toString()).isEqualTo(sharedId)
        assertThat(list[0]["isOwner"] as Boolean).isFalse()
    }

    @Test
    fun `shared - 페이지네이션 — 3개 공유 필터를 page 0 size 2, page 1 size 2로 분리 조회`() {
        createFilter("f1", """[{"shareType":"AUTHENTICATED"}]""")
        createFilter("f2", """[{"shareType":"AUTHENTICATED"}]""")
        createFilter("f3", """[{"shareType":"AUTHENTICATED"}]""")

        authenticate(dave)
        val page0Json =
            mockMvc.perform(get("/api/v1/filters/shared?page=0&size=2"))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        val page1Json =
            mockMvc.perform(get("/api/v1/filters/shared?page=1&size=2"))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString

        val page0 = mapper.readValue<List<Map<String, Any?>>>(page0Json)
        val page1 = mapper.readValue<List<Map<String, Any?>>>(page1Json)
        assertThat(page0).hasSize(2)
        assertThat(page1).hasSize(1)
        // 두 페이지 합집합 = 전체 3개, 중복 없음
        val allIds = (page0 + page1).map { it["id"].toString() }.toSet()
        assertThat(allIds).hasSize(3)
    }

    // ── C2 매칭 공유만 노출(정보 노출 차단) ────────────────────────────────────

    /** [PROJECT:ATL, GROUP:g-devs, AUTHENTICATED] 3종 공유 JSON 리터럴. */
    private val multiSharesJson =
        "[" +
            """{"shareType":"PROJECT","targetId":"ATL"},""" +
            """{"shareType":"GROUP","targetId":"g-devs"},""" +
            """{"shareType":"AUTHENTICATED"}""" +
            "]"

    @Test
    fun `C2 - 소유자(alice)는 다중 공유 전체 대상을 응답에서 받는다`() {
        val id = createFilter("멀티 공유", multiSharesJson)
        authenticate(alice)
        val shares = sharePairs(getOk("/api/v1/filters/$id"))
        assertThat(shares)
            .containsExactlyInAnyOrder("PROJECT:ATL", "GROUP:g-devs", "AUTHENTICATED:null")
    }

    @Test
    fun `C2 - 비소유 PROJECT 멤버(bob)는 매칭 공유만 받고 GROUP 대상은 응답에서 제외된다`() {
        val id = createFilter("멀티 공유", multiSharesJson)
        authenticate(bob) // ATL 멤버, g-devs 미소속
        val shares = sharePairs(getOk("/api/v1/filters/$id"))
        assertThat(shares).containsExactlyInAnyOrder("PROJECT:ATL", "AUTHENTICATED:null")
        assertThat(shares).doesNotContain("GROUP:g-devs")
    }

    @Test
    fun `C2 - 비소유 GROUP 소속(carol)은 매칭 공유만 받고 PROJECT 대상은 응답에서 제외된다`() {
        val id = createFilter("멀티 공유", multiSharesJson)
        authenticate(carol) // g-devs 소속, ATL 미멤버
        val shares = sharePairs(getOk("/api/v1/filters/$id"))
        assertThat(shares).containsExactlyInAnyOrder("GROUP:g-devs", "AUTHENTICATED:null")
        assertThat(shares).doesNotContain("PROJECT:ATL")
    }

    @Test
    fun `C2 - GET shared 비소유 viewer(bob) 항목은 매칭 공유 subset만 노출한다`() {
        createFilter("멀티 공유", multiSharesJson)
        authenticate(bob)
        val json = getOk("/api/v1/filters/shared")
        val list = mapper.readValue<List<Map<String, Any?>>>(json)
        assertThat(list).hasSize(1)
        assertThat(sharePairsOf(list[0]))
            .containsExactlyInAnyOrder("PROJECT:ATL", "AUTHENTICATED:null")
    }

    // ── parity(B3) ────────────────────────────────────────────────────────────

    @Test
    fun `parity(B3) - GET {id} 200 비소유자 bob이면 GET shared 결과에도 포함된다 (양방향 일치)`() {
        val visibleId = createFilter("ATL 공유", """[{"shareType":"PROJECT","targetId":"ATL"}]""")
        val hiddenId = createFilter("비공유", "null")

        authenticate(bob)

        // visible: GET /{id} → 200
        mockMvc.perform(get("/api/v1/filters/$visibleId"))
            .andExpect(status().isOk)

        // GET /shared → visible 포함
        val sharedJson =
            mockMvc.perform(get("/api/v1/filters/shared"))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        val sharedIds = mapper.readValue<List<Map<String, Any?>>>(sharedJson).map { it["id"].toString() }
        assertThat(sharedIds).contains(visibleId)

        // hidden: GET /{id} → 404, GET /shared → 미포함
        mockMvc.perform(get("/api/v1/filters/$hiddenId"))
            .andExpect(status().isNotFound)
        assertThat(sharedIds).doesNotContain(hiddenId)
    }

    // ── 실행 권한 상승 0(FR-8) ────────────────────────────────────────────────

    @Test
    fun `FR-8 - 공유받은 사용자(bob)가 {id}search 실행 시 viewerUserId=bob으로 IssueSearchPort 호출`() {
        val id = createFilter("ATL 검색 필터", """[{"shareType":"PROJECT","targetId":"ATL"}]""")
        authenticate(bob)

        mockMvc.perform(get("/api/v1/filters/$id/search"))
            .andExpect(status().isOk)

        assertThat(lastSearchQuery).isNotNull()
        assertThat(lastSearchQuery!!.viewerUserId).isEqualTo(bob)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * alice 로 필터를 생성하고 id 문자열을 반환하는 간편 헬퍼.
     *
     * @param name 필터 이름.
     * @param sharesJson JSON 배열 리터럴(`[{...}]`) 또는 null 제거("null").
     */
    private fun createFilter(
        name: String,
        sharesJson: String,
    ): String = createFilterAs(alice, name, sharesJson)

    /**
     * 지정 사용자로 인증 후 필터를 생성하고 id 문자열을 반환한다.
     *
     * @param userId 생성자 UUID.
     * @param name 필터 이름.
     * @param sharesJson JSON 배열 리터럴 또는 "null".
     */
    private fun createFilterAs(
        userId: UUID,
        name: String,
        sharesJson: String,
    ): String {
        authenticate(userId)
        val body = """{"name":"$name","aqlQuery":"status = open","projectKey":"ATL","shares":$sharesJson}"""
        val json =
            mockMvc.perform(
                post("/api/v1/filters")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return mapper.readValue<Map<String, Any?>>(json)["id"].toString()
    }

    /** GET 요청을 200 으로 수행하고 응답 본문 문자열을 반환한다. */
    private fun getOk(path: String): String {
        return mockMvc.perform(get(path)).andExpect(status().isOk).andReturn().response.contentAsString
    }

    /** 단건 응답 JSON 에서 shares 를 "shareType:targetId" 문자열 집합으로 추출한다. */
    private fun sharePairs(responseJson: String): Set<String> {
        return sharePairsOf(mapper.readValue<Map<String, Any?>>(responseJson))
    }

    /** 응답 맵에서 shares 를 "shareType:targetId" 문자열 집합으로 추출한다(targetId 없으면 null). */
    @Suppress("UNCHECKED_CAST")
    private fun sharePairsOf(response: Map<String, Any?>): Set<String> {
        val shares = response["shares"] as List<Map<String, Any?>>
        return shares.map { "${it["shareType"]}:${it["targetId"]}" }.toSet()
    }

    private fun authenticate(userId: UUID) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    companion object {
        /** JVM 단위 singleton PostgreSQL container. [SavedFilterIntegrationTest.pg] 와 DB 이름이 달라 격리된다. */
        @JvmStatic
        val pg: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_savedfilter_share_it")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        /**
         * [IssueSearchPort] stub 이 마지막으로 수신한 검색 쿼리 캡처.
         * 각 테스트 [setUp] 에서 null 로 리셋되며, FR-8 viewerUserId 검증에 사용한다.
         */
        @Volatile
        var lastSearchQuery: IssueSearchQuery? = null
    }
}
