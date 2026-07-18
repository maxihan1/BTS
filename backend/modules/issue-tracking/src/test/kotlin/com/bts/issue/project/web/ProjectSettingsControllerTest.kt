// ProjectSettingsController MockMvc 슬라이스 테스트 — PATCH /projects/{idOrKey} 204/401/403/404/400 (FR-PJ PR-3 Task 5)

package com.bts.issue.project.web

import com.bts.issue.project.settings.ProjectNotFoundException
import com.bts.issue.project.settings.ProjectSettingsForbiddenException
import com.bts.issue.project.settings.ProjectSettingsService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

/**
 * ProjectSettingsController MockMvc 슬라이스 테스트 (FR-PJ PR-3 Task 5).
 *
 * `@SpringBootApplication` 없이 `@ContextConfiguration` 으로 최소 컨텍스트를 직접 구성한다
 * ([ProjectCreateControllerTest] 선례). Spring Security 필터 체인은 로드하지 않으므로 인증은
 * [SecurityContextHolder] 직접 주입으로 시뮬레이션한다. 401 은
 * [com.bts.issue.adapter.inbound.rest.CurrentActor] 가, 403/404 는 [ProjectSettingsService]
 * (mock)가 던지는 도메인 예외를 [ProjectSettingsExceptionHandler] 가 변환한 결과다.
 *
 * ### 테스트 케이스
 * - T5-1. PATCH 권한 있는 actor(PROJECT_ADMIN) → 204 No Content (재조회 없음)
 * - T5-2. 미인증 → 401. 서비스 미도달 검증(`verify(exactly = 0)`)
 * - T5-3. 권한 없음(MEMBER 등) → 403 + errorCode 본문판별자 + 내부구조 미노출
 * - T5-4. 미존재 프로젝트 → 404
 * - T5-5. 빈 name → 400 (Jakarta Validation). 서비스 미도달 검증
 *
 * ### 403 음성 테스트 비-vacuous 대조
 * T5-1(권한 있는 actor → 204) 과 T5-3(권한 없는 actor → 403) 은 같은 엔드포인트를 인증 상태는
 * 동일(둘 다 인증됨)하게 유지한 채 서비스 결과만 바꿔 대조한다. T5-3 의 actor 는 **인증된** actor 이므로
 * 이 403 은 401 이 아니라 [ProjectSettingsExceptionHandler] 가 [ProjectSettingsForbiddenException] 을
 * 변환한 결과임을 errorCode 로 단언한다(vacuous 401 아님).
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ProjectSettingsControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class ProjectSettingsControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [ProjectSettingsController], [ProjectSettingsExceptionHandler] 와 MockK stub 협력자를 등록한다.
     * 서비스를 MockK 로 대체하므로 [com.bts.shared.permission.ComponentPermissionResolver] 등
     * 서비스 내부 협력자 빈은 필요하지 않다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun projectSettingsService(): ProjectSettingsService = mockk(relaxed = true)

        @Bean
        open fun projectSettingsController(service: ProjectSettingsService): ProjectSettingsController {
            return ProjectSettingsController(service)
        }

        @Bean
        open fun projectSettingsExceptionHandler(): ProjectSettingsExceptionHandler = ProjectSettingsExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var projectSettingsService: ProjectSettingsService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /** PROJECT_ADMIN 권한을 가진 actor. */
    private val adminActorId: UUID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

    /** UPDATE_COMPONENT 권한이 없는 일반(MEMBER) actor. */
    private val regularActorId: UUID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

    private val projectIdOrKey = "ATLAS"
    private val newName = "Renamed Project"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // 슬라이스 mock 은 컨텍스트 캐시로 메서드 간 공유돼 호출기록/stub 이 누적된다.
        // clearMocks 로 매 테스트 격리한다(선례 ProjectCreateControllerTest).
        clearMocks(projectSettingsService)
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── T5-1. 권한 있는 actor → 204 (403 음성 테스트의 양성 대조) ────────────────

    @Test
    fun `PATCH projects id — PROJECT_ADMIN이면 204이고 서비스에 actorId·name이 전달된다`() {
        authenticateAs(adminActorId)
        every { projectSettingsService.changeName(adminActorId, projectIdOrKey, newName) } returns Unit

        mockMvc.perform(
            patch("/api/v1/projects/$projectIdOrKey")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(newName)),
        )
            .andExpect(status().isNoContent)

        verify(exactly = 1) { projectSettingsService.changeName(adminActorId, projectIdOrKey, newName) }
    }

    // ── T5-2. 미인증 → 401. 서비스 미도달 ────────────────────────────────────

    @Test
    fun `PATCH projects id — 미인증이면 401이고 서비스에 도달하지 않는다`() {
        // 인증 미설정(@BeforeEach 에서 clearContext) → CurrentActor 가 401 을 던진다.
        mockMvc.perform(
            patch("/api/v1/projects/$projectIdOrKey")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(newName)),
        )
            .andExpect(status().isUnauthorized)

        verify(exactly = 0) { projectSettingsService.changeName(any(), any(), any()) }
    }

    // ── T5-3. 권한 없음 → 403 + 본문판별자 + 내부구조 미노출 ──────────────────

    @Test
    fun `PATCH projects id — 권한 없는 actor이면 403이고 내부구조를 노출하지 않는다`() {
        authenticateAs(regularActorId)
        every {
            projectSettingsService.changeName(regularActorId, projectIdOrKey, newName)
        } throws ProjectSettingsForbiddenException(regularActorId, UUID.randomUUID())

        mockMvc.perform(
            patch("/api/v1/projects/$projectIdOrKey")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(newName)),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_PROJECT_SETTINGS_FORBIDDEN"))
            .andExpect(jsonPath("$.detail").value("프로젝트 설정을 변경할 권한이 없습니다."))
    }

    // ── T5-4. 미존재 프로젝트 → 404 ────────────────────────────────────────────

    @Test
    fun `PATCH projects id — 미존재 프로젝트이면 404`() {
        authenticateAs(adminActorId)
        every {
            projectSettingsService.changeName(adminActorId, "NOTEXIST", newName)
        } throws ProjectNotFoundException("NOTEXIST")

        mockMvc.perform(
            patch("/api/v1/projects/NOTEXIST")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(newName)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_PROJECT_SETTINGS_NOT_FOUND"))
    }

    // ── T5-5. 빈 name → 400. 서비스 미도달 ─────────────────────────────────────

    @Test
    fun `PATCH projects id — 빈 name이면 400이고 서비스에 도달하지 않는다`() {
        authenticateAs(adminActorId)

        mockMvc.perform(
            patch("/api/v1/projects/$projectIdOrKey")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("")),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_PROJECT_SETTINGS_VALIDATION_FAILED"))

        verify(exactly = 0) { projectSettingsService.changeName(any(), any(), any()) }
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

    private fun requestBody(name: String): String = mapper.writeValueAsString(mapOf("name" to name))
}
