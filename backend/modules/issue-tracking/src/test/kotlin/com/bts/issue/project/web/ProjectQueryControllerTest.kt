// ProjectQueryController MockMvc 슬라이스 테스트 — GET /projects, /projects/{id} 200/401/403/404 (FR-PJ PR-3 Task 3/4)

package com.bts.issue.project.web

import com.bts.issue.project.domain.Project
import com.bts.issue.project.query.ProjectBrowseForbiddenException
import com.bts.issue.project.query.ProjectQueryNotFoundException
import com.bts.issue.project.query.ProjectQueryService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

/**
 * ProjectQueryController MockMvc 슬라이스 테스트 (FR-PJ PR-3 Task 3/4).
 *
 * `@SpringBootApplication` 없이 `@ContextConfiguration` 으로 최소 컨텍스트를 직접 구성한다
 * ([ProjectCreateControllerTest] 선례). Spring Security 필터 체인은 로드하지 않으므로 인증은
 * [SecurityContextHolder] 직접 주입으로 시뮬레이션한다. 401 은
 * [com.bts.issue.adapter.inbound.rest.CurrentActor] 가 낸다(필터 체인 관통 검증은 조립부팅
 * 통합 테스트가 담당).
 *
 * ### 테스트 케이스
 * - S1. 인증 actor → 접근가능 목록 200 + `$.data[0].key`
 * - S2. 미인증 → 401 이고 서비스 미도달 (음성 테스트 비-vacuous 판별자 — `verify(exactly = 0)`)
 * - S3. projectKeysOf 가 빈 Set 인 상황(서비스가 빈 목록 반환) → `$.data` 빈 배열(존재 누설 0)
 * - S4. 단건 조회 — BROWSE 통과 → 200 + `$.data.key` (Task 4)
 * - S5. 단건 조회 — 프로젝트 미존재 → 404 + `$.errorCode` (Task 4)
 * - S6. 단건 조회 — 실재 프로젝트에 BROWSE 거부 → 403 + 서비스 도달 확인(비-vacuous, Task 4)
 * - S7. `?archived` 쿼리 파라미터 생략 → 서비스에 `archived=false` 로 위임(Task 6, PJ2-2)
 * - S8. `?archived=true` → 서비스에 `archived=true` 로 위임(Task 6, PJ2-2)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ProjectQueryControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class ProjectQueryControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [ProjectQueryController], [ProjectQueryExceptionHandler] 와 MockK stub 협력자를 등록한다.
     * 서비스를 MockK 로 대체하므로 [com.bts.issue.project.repository.ProjectQueryRepository] /
     * [com.bts.shared.membership.ProjectMembershipPort] 빈은 필요하지 않다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun projectQueryService(): ProjectQueryService = mockk(relaxed = true)

        @Bean
        open fun projectQueryController(service: ProjectQueryService): ProjectQueryController {
            return ProjectQueryController(service)
        }

        @Bean
        open fun projectQueryExceptionHandler(): ProjectQueryExceptionHandler = ProjectQueryExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var projectQueryService: ProjectQueryService

    lateinit var mockMvc: MockMvc

    /** 접근 가능한 프로젝트가 있는 actor */
    private val memberActorId: UUID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

    private val visibleProjectId: UUID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
    private val visibleProjectKey = "VISIBLE"
    private val visibleProjectName = "Visible Project"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // 슬라이스 mock 은 컨텍스트 캐시로 메서드 간 공유돼 호출기록/stub 이 누적된다.
        // clearMocks 로 매 테스트 격리한다(선례 ProjectCreateControllerTest).
        clearMocks(projectQueryService)
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── S1. 인증 actor → 접근가능 목록 200 ────────────────────────────────────────

    @Test
    fun `GET projects — 인증 actor 이면 200 + 접근가능 프로젝트 목록 반환`() {
        authenticateAs(memberActorId)
        every {
            projectQueryService.listAccessible(memberActorId)
        } returns listOf(Project(id = visibleProjectId, key = visibleProjectKey, name = visibleProjectName))

        mockMvc.perform(get("/api/v1/projects"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].id").value(visibleProjectId.toString()))
            .andExpect(jsonPath("$.data[0].key").value(visibleProjectKey))
            .andExpect(jsonPath("$.data[0].name").value(visibleProjectName))

        // 추출된 actorId 가 서비스로 전달됨(CurrentActor → 서비스 결선) 검증
        verify(exactly = 1) { projectQueryService.listAccessible(memberActorId) }
    }

    // ── S2. 미인증 → 401. 서비스 미도달 ───────────────────────────────────────────

    @Test
    fun `GET projects — 미인증이면 401 이고 서비스에 도달하지 않는다`() {
        // 인증 미설정(@BeforeEach 에서 clearContext) → CurrentActor 가 401 을 던진다.
        mockMvc.perform(get("/api/v1/projects"))
            .andExpect(status().isUnauthorized)

        // 미인증자는 목록 조회(서비스)에 도달하지 못한다 — vacuous 401 회피 판별자.
        verify(exactly = 0) { projectQueryService.listAccessible(any()) }
    }

    // ── S3. 접근 가능 프로젝트 없음(fail-closed) → 빈 배열 ─────────────────────────

    @Test
    fun `GET projects — 접근가능 프로젝트가 없으면 data 는 빈 배열이다`() {
        authenticateAs(memberActorId)
        every { projectQueryService.listAccessible(memberActorId) } returns emptyList()

        mockMvc.perform(get("/api/v1/projects"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    // ── S4. 단건 조회 — BROWSE 통과 → 200 (Task 4) ────────────────────────────────

    @Test
    fun `GET projects id — BROWSE 통과하면 200 + 단건 반환`() {
        authenticateAs(memberActorId)
        every {
            projectQueryService.getOne(memberActorId, visibleProjectKey)
        } returns Project(id = visibleProjectId, key = visibleProjectKey, name = visibleProjectName)

        mockMvc.perform(get("/api/v1/projects/{projectIdOrKey}", visibleProjectKey))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(visibleProjectId.toString()))
            .andExpect(jsonPath("$.data.key").value(visibleProjectKey))
            .andExpect(jsonPath("$.data.name").value(visibleProjectName))

        // 추출된 actorId 가 서비스로 전달됨(CurrentActor → 서비스 결선) 검증
        verify(exactly = 1) { projectQueryService.getOne(memberActorId, visibleProjectKey) }
    }

    // ── S5. 단건 조회 — 프로젝트 미존재 → 404 (Task 4) ─────────────────────────────

    @Test
    fun `GET projects id — 프로젝트가 존재하지 않으면 404`() {
        authenticateAs(memberActorId)
        every {
            projectQueryService.getOne(memberActorId, "MISSING")
        } throws ProjectQueryNotFoundException("MISSING")

        mockMvc.perform(get("/api/v1/projects/{projectIdOrKey}", "MISSING"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_PROJECT_NOT_FOUND"))
    }

    // ── S6. 단건 조회 — 실재 프로젝트에 BROWSE 거부 → 403 (Task 4) ─────────────────

    @Test
    fun `GET projects id — BROWSE 권한이 없으면 403 이고 서비스에는 도달한다`() {
        authenticateAs(memberActorId)
        every {
            projectQueryService.getOne(memberActorId, visibleProjectKey)
        } throws ProjectBrowseForbiddenException(memberActorId, visibleProjectKey)

        mockMvc.perform(get("/api/v1/projects/{projectIdOrKey}", visibleProjectKey))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_PROJECT_FORBIDDEN"))

        // vacuous 401 이 아님을 증명 — actor 추출 이후 서비스에 실제로 도달했다.
        verify(exactly = 1) { projectQueryService.getOne(memberActorId, visibleProjectKey) }
    }

    // ── S7. archived 생략 → archived=false 위임 (Task 6, PJ2-2) ────────────────────

    @Test
    fun `GET projects — archived 쿼리 파라미터를 생략하면 서비스에 archived=false 로 위임한다`() {
        authenticateAs(memberActorId)
        every { projectQueryService.listAccessible(memberActorId, false) } returns emptyList()

        mockMvc.perform(get("/api/v1/projects"))
            .andExpect(status().isOk)

        verify(exactly = 1) { projectQueryService.listAccessible(memberActorId, false) }
    }

    // ── S8. archived=true → archived=true 위임 (Task 6, PJ2-2) ─────────────────────

    @Test
    fun `GET projects — archived=true 를 전달하면 서비스에 archived=true 로 위임한다`() {
        authenticateAs(memberActorId)
        every {
            projectQueryService.listAccessible(memberActorId, true)
        } returns listOf(Project(id = visibleProjectId, key = visibleProjectKey, name = visibleProjectName))

        mockMvc.perform(get("/api/v1/projects").param("archived", "true"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].key").value(visibleProjectKey))

        verify(exactly = 1) { projectQueryService.listAccessible(memberActorId, true) }
        // archived=true 위임 시 archived=false 오버로드로는 도달하지 않는다(비-vacuous 판별자).
        verify(exactly = 0) { projectQueryService.listAccessible(memberActorId, false) }
    }

    // ── archived 파라미터 검증 — Boolean 바인딩 실패 시 400 (Task 6 REFACTOR) ──────────

    @Test
    fun `GET projects — archived 값이 boolean 이 아니면 400 이고 서비스에 도달하지 않는다`() {
        authenticateAs(memberActorId)

        mockMvc.perform(get("/api/v1/projects").param("archived", "maybe"))
            .andExpect(status().isBadRequest)

        // true/false 외 값을 조용히 기본값(false)으로 흡수하지 않는다(fail-closed 판별자).
        verify(exactly = 0) { projectQueryService.listAccessible(any(), any()) }
    }

    // ── private helpers ─────────────────────────────────────────────────────────

    private fun authenticateAs(actorId: UUID) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }
}
