// ProjectCreateController MockMvc 슬라이스 테스트 — POST /projects 201/401/403/409/400 + 403 본문판별자 (FR-PJ-01 Task 7)

package com.bts.issue.project.web

import com.bts.issue.project.application.ProjectCreateApplicationService
import com.bts.issue.project.domain.Project
import com.bts.issue.project.domain.ProjectKeyAlreadyExistsException
import com.bts.shared.permission.GlobalPermissionCodes
import com.bts.shared.permission.SystemPermissionResolver
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

/**
 * ProjectCreateController MockMvc 슬라이스 테스트 (FR-PJ-01 Task 7).
 *
 * `@SpringBootApplication` 없이 `@ContextConfiguration` 으로 최소 컨텍스트를 직접 구성한다
 * ([com.bts.issue.customfield.web.CustomFieldControllerTest] 선례). Spring Security 필터 체인은
 * 로드하지 않으므로 인증은 [SecurityContextHolder] 직접 주입으로 시뮬레이션한다.
 * 401 은 [com.bts.issue.adapter.inbound.rest.CurrentActor] 가, 403 은 컨트롤러의
 * `hasGlobalPermission` 게이트가 낸다(필터 체인 관통 검증은 T8 조립부팅 통합 테스트가 담당).
 *
 * ### 테스트 케이스
 * - S1. POST 권한 있는 actor → 201 + {id, key, name}
 * - S3. 미인증 → 401 (CurrentActor). 서비스(key 조회) 미도달 검증(PJ1-3)
 * - S2/PJ1-8. 인증됐으나 CREATE_PROJECT 없음 → 403 + errorCode 본문판별자 + 내부구조 미노출
 * - S4. 중복 key → 409
 * - PJ1-6. key 정규식 위반 → 400
 *
 * ### 403 음성 테스트 비-vacuous 대조(C2)
 * S1(권한 있는 actor → 201) 과 S2(권한 없는 actor → 403) 는 같은 엔드포인트를 권한 유무만
 * 바꿔 대조한다. S2 의 actor 는 **인증된** actor 이므로, 게이트를 제거하면 401 이 아니라 201 로
 * 통과한다(가드 없어도 401 나오는 vacuous 회피). 즉 이 403 테스트는 가드가 실제로 존재해야만
 * 통과한다 — mutation 실증은 리뷰 보고에 첨부한다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ProjectCreateControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class ProjectCreateControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [ProjectCreateController], [ProjectCreateExceptionHandler] 와 MockK stub 협력자를 등록한다.
     * 서비스를 MockK 로 대체하므로 [com.bts.shared.membership.ProjectMembershipWritePort] 빈은
     * 필요하지 않다(실제 서비스를 썼다면 필요 — plan "또는 실제").
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun projectCreateApplicationService(): ProjectCreateApplicationService = mockk(relaxed = true)

        @Bean
        open fun systemPermissionResolver(): SystemPermissionResolver = mockk(relaxed = true)

        @Bean
        open fun projectCreateController(
            service: ProjectCreateApplicationService,
            resolver: SystemPermissionResolver,
        ): ProjectCreateController = ProjectCreateController(service, resolver)

        @Bean
        open fun projectCreateExceptionHandler(): ProjectCreateExceptionHandler = ProjectCreateExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var projectCreateApplicationService: ProjectCreateApplicationService

    @Autowired
    lateinit var systemPermissionResolver: SystemPermissionResolver

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /** CREATE_PROJECT 권한을 가진 actor */
    private val adminActorId: UUID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

    /** CREATE_PROJECT 권한이 없는 일반 actor */
    private val regularActorId: UUID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

    /** 생성된 프로젝트 id */
    private val newProjectId: UUID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")

    private val newKey = "NEWPROJ"
    private val newName = "New Project"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // 슬라이스 mock 은 컨텍스트 캐시로 메서드 간 공유돼 호출기록/stub 이 누적된다.
        // clearMocks 로 매 테스트 격리한다(선례 PersonalAccessTokenControllerTest).
        clearMocks(projectCreateApplicationService, systemPermissionResolver)
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── S1. 권한 있는 actor → 201 (403 음성 테스트의 양성 대조) ──────────────────

    @Test
    fun `POST projects — 권한 있는 actor 이면 201 + 생성된 프로젝트 반환`() {
        authenticateAs(adminActorId)
        every {
            systemPermissionResolver.hasGlobalPermission(adminActorId, GlobalPermissionCodes.CREATE_PROJECT)
        } returns true
        every {
            projectCreateApplicationService.create(adminActorId, newKey, newName)
        } returns Project(id = newProjectId, key = newKey, name = newName)

        mockMvc.perform(
            post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(newKey, newName)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value(newProjectId.toString()))
            .andExpect(jsonPath("$.data.key").value(newKey))
            .andExpect(jsonPath("$.data.name").value(newName))

        // 추출된 actorId 가 서비스로 전달됨(CurrentActor → 서비스 결선) 검증
        verify(exactly = 1) { projectCreateApplicationService.create(adminActorId, newKey, newName) }
    }

    // ── S3. 미인증 → 401 (CurrentActor). 서비스 미도달(PJ1-3) ────────────────────

    @Test
    fun `POST projects — 미인증이면 401 이고 서비스에 도달하지 않는다`() {
        // 인증 미설정(@BeforeEach 에서 clearContext) → CurrentActor 가 401 을 던진다.
        mockMvc.perform(
            post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(newKey, newName)),
        )
            .andExpect(status().isUnauthorized)

        // 미인증자는 key 중복조회(서비스)에 도달하지 못한다 — 리소스 probe 차단(PJ1-3).
        verify(exactly = 0) { projectCreateApplicationService.create(any(), any(), any()) }
    }

    // ── S2/PJ1-8. 인증됐으나 CREATE_PROJECT 없음 → 403 + 본문판별자 + 내부구조 미노출 ──

    @Test
    fun `POST projects — 권한 없는 actor 이면 403 이고 내부구조를 노출하지 않는다`() {
        authenticateAs(regularActorId)
        every {
            systemPermissionResolver.hasGlobalPermission(regularActorId, GlobalPermissionCodes.CREATE_PROJECT)
        } returns false

        mockMvc.perform(
            post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(newKey, newName)),
        )
            .andExpect(status().isForbidden)
            // 본문판별자 — 권한 거부임을 errorCode 로 식별(vacuous 401 아님).
            .andExpect(jsonPath("$.errorCode").value("ISSUE_PROJECT_FORBIDDEN"))
            // 내부구조(actorId/권한코드) 미노출 — 일반 메시지만.
            .andExpect(jsonPath("$.detail").value("프로젝트를 생성할 권한이 없습니다."))

        // 권한 실패 시 서비스(key 조회/insert)에 도달하지 않는다.
        verify(exactly = 0) { projectCreateApplicationService.create(any(), any(), any()) }
    }

    // ── S4. 중복 key → 409 ──────────────────────────────────────────────────────

    @Test
    fun `POST projects — 중복 key 이면 409`() {
        authenticateAs(adminActorId)
        every {
            systemPermissionResolver.hasGlobalPermission(adminActorId, GlobalPermissionCodes.CREATE_PROJECT)
        } returns true
        every {
            projectCreateApplicationService.create(adminActorId, "DUPKEY", any())
        } throws ProjectKeyAlreadyExistsException("DUPKEY")

        mockMvc.perform(
            post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("DUPKEY", "Dup Project")),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_PROJECT_KEY_ALREADY_EXISTS"))
    }

    // ── PJ1-6. key 정규식 위반 → 400 ────────────────────────────────────────────

    @Test
    fun `POST projects — key 정규식 위반이면 400`() {
        authenticateAs(adminActorId)
        every { systemPermissionResolver.hasGlobalPermission(any(), any()) } returns true

        mockMvc.perform(
            post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                // 소문자+하이픈 → ^[A-Z][A-Z0-9]{1,9}$ 위반
                .content(requestBody("bad-key", "Bad Project")),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_PROJECT_VALIDATION_FAILED"))

        verify(exactly = 0) { projectCreateApplicationService.create(any(), any(), any()) }
    }

    /**
     * key 필드 누락(Kotlin non-null 역직렬화 실패 → HttpMessageNotReadableException) → 400.
     *
     * 판별자 — 이 엔드포인트의 400 계약을 [ProjectCreateExceptionHandler] 가 온전히 소유해야 한다.
     * 핸들러가 없으면 같은 패키지 ProjectLeadExceptionHandler(basePackages 스코프)가 잡아
     * `project-lead-*` 에러 코드를 반환한다. errorCode 가 이 컨트롤러 소유(ISSUE_PROJECT_VALIDATION_FAILED)인지 단언.
     */
    @Test
    fun `POST projects — 필드 누락(역직렬화 실패)이면 400 이고 이 컨트롤러 소유 에러코드`() {
        authenticateAs(adminActorId)
        every { systemPermissionResolver.hasGlobalPermission(any(), any()) } returns true

        mockMvc.perform(
            post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                // key 필드 누락 → Kotlin non-null 역직렬화 실패(HttpMessageNotReadableException)
                .content(mapper.writeValueAsString(mapOf("name" to "No Key Project"))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_PROJECT_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.type").value("https://bts.example.com/problems/project-create-validation-failed"))

        verify(exactly = 0) { projectCreateApplicationService.create(any(), any(), any()) }
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

    private fun requestBody(
        key: String,
        name: String,
    ): String = mapper.writeValueAsString(mapOf("key" to key, "name" to name))
}
