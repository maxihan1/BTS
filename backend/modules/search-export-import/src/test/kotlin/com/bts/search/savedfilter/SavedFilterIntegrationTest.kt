// 저장된 필터 풀스택 통합테스트 — 컨트롤러→서비스→jOOQ→실 DB end-to-end (FR-SR-03)

package com.bts.search.savedfilter

import com.bts.search.jooq.tables.references.SAVED_FILTERS
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
 * 저장된 필터 풀스택 통합테스트.
 *
 * 컨트롤러 → 서비스 → jOOQ Repository → **실 PostgreSQL(Testcontainers)** end-to-end.
 * [IssueSearchPort]만 stub 빈으로 대체(실행 엔드포인트용, 다른 BC 어댑터 부재).
 *
 * T3(repo)·T5(controller slice)·T6(run slice)가 검증하지 못하는 HTTP→실DB 정합을 커버한다.
 * 특히 이름중복 409가 **실 UNIQUE 위반 → 서비스 native(23505) dual-catch** 경로로 동작함을 단언한다
 * (테스트 수동 DSLContext는 JooqExceptionTranslator가 없어 Spring DuplicateKeyException이 안 뜨므로).
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [SavedFilterIntegrationTest.IntegrationConfig::class])
@WebAppConfiguration
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SavedFilterIntegrationTest {
    /**
     * 풀스택 테스트 전용 Spring 컨텍스트.
     *
     * 실 DataSource(Testcontainers) + Flyway V600 + DSLContext + Repository + Service +
     * 두 컨트롤러 + 예외 핸들러를 조립하고, [IssueSearchPort]만 빈 페이지 stub으로 대체한다.
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

        /** 멤버십 stub — emptySet 반환(fail-closed). 통합테스트는 owner/AUTHENTICATED 경로만 검증한다. */
        @Bean
        open fun groupMembershipPort(): GroupMembershipPort =
            object : GroupMembershipPort {
                override fun groupIdsOf(userId: UUID): Set<String> = emptySet()
            }

        /** 멤버십 stub — emptySet 반환(fail-closed). */
        @Bean
        open fun projectMembershipPort(): ProjectMembershipPort =
            object : ProjectMembershipPort {
                override fun projectKeysOf(userId: UUID): Set<String> = emptySet()
            }

        @Bean
        open fun service(
            repository: JooqSavedFilterRepository,
            shareRepository: JooqSavedFilterShareRepository,
            groupMembershipPort: GroupMembershipPort,
            projectMembershipPort: ProjectMembershipPort,
        ): SavedFilterService {
            return SavedFilterService(repository, shareRepository, groupMembershipPort, projectMembershipPort)
        }

        @Bean
        open fun stubIssueSearchPort(): IssueSearchPort =
            object : IssueSearchPort {
                override fun search(query: IssueSearchQuery): IssueSearchPage {
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

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val alice = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val bob = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        dsl.deleteFrom(SAVED_FILTERS).execute()
        authenticate(alice)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `CRUD 라이프사이클 end-to-end`() {
        // create
        val createdJson =
            mockMvc
                .perform(
                    post("/api/v1/filters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"내 필터","aqlQuery":"status = open","projectKey":"ATL"}"""),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value("내 필터"))
                .andExpect(jsonPath("$.isOwner").value(true))
                .andReturn()
                .response.contentAsString
        val id: String = mapper.readValue<Map<String, Any?>>(createdJson)["id"].toString()

        // get
        mockMvc.perform(get("/api/v1/filters/$id")).andExpect(status().isOk).andExpect(jsonPath("$.id").value(id))

        // list
        mockMvc.perform(get("/api/v1/filters")).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(1))

        // update (version 0 → 1)
        mockMvc
            .perform(
                put("/api/v1/filters/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"수정됨","aqlQuery":"status = closed","version":0}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("수정됨"))
            .andExpect(jsonPath("$.version").value(1))

        // delete
        mockMvc.perform(delete("/api/v1/filters/$id")).andExpect(status().isNoContent)
        mockMvc.perform(get("/api/v1/filters/$id")).andExpect(status().isNotFound)
    }

    @Test
    fun `이름 중복 409 — 실 UNIQUE 위반 native dual-catch`() {
        val body = """{"name":"중복","aqlQuery":"status = open","projectKey":"ATL"}"""
        mockMvc
            .perform(post("/api/v1/filters").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated)
        mockMvc
            .perform(post("/api/v1/filters").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict)
    }

    @Test
    fun `잘못된 AQL 400`() {
        mockMvc
            .perform(
                post("/api/v1/filters")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"x","aqlQuery":"status = = open","projectKey":"ATL"}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `OCC 충돌 409 — stale version`() {
        val id = createFilter("OCC테스트", "status = open")
        mockMvc
            .perform(
                put("/api/v1/filters/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"x","aqlQuery":"status = open","version":99}"""),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `비소유자 수정 404 (PR1 비가시 존재은닉)`() {
        val id = createFilter("앨리스필터", "status = open")
        authenticate(bob)
        mockMvc
            .perform(
                put("/api/v1/filters/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"탈취","aqlQuery":"status = open","version":0}"""),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `비가시 단건 404`() {
        val id = createFilter("앨리스만", "status = open")
        authenticate(bob)
        mockMvc.perform(get("/api/v1/filters/$id")).andExpect(status().isNotFound)
    }

    @Test
    fun `미인증 401`() {
        SecurityContextHolder.clearContext()
        mockMvc.perform(get("/api/v1/filters")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `실행 엔드포인트 200`() {
        val id = createFilter("실행필터", "status = open")
        mockMvc.perform(get("/api/v1/filters/$id/search")).andExpect(status().isOk)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun createFilter(
        name: String,
        aql: String,
    ): String {
        val json =
            mockMvc
                .perform(
                    post("/api/v1/filters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"$name","aqlQuery":"$aql","projectKey":"ATL"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return mapper.readValue<Map<String, Any?>>(json)["id"].toString()
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
        /**
         * JVM 단위 singleton PostgreSQL container — 통합테스트 격리(T3와 별도 DB).
         * 이미지 = quay.io/tembo/pg16-pgmq:latest — FR-EX-02 V602(pgmq 확장 + pgmq.create) 가 마이그레이션 체인에
         * 포함되어 postgres:16-alpine 으로는 적용 실패하므로 tembo 이미지 사용 (ADR 2026-05-22-pgmq-postgres-image).
         */
        @JvmStatic
        val pg: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_savedfilter_it")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }
    }
}
