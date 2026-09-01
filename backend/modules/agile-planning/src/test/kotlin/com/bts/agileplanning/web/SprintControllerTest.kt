// SprintController MockMvc 슬라이스 HTTP 통합 테스트 — 9 엔드포인트 + 예외 매핑 RED 명세 (FR-BL-02 Task 5)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.SprintAlreadyActiveException
import com.bts.agileplanning.application.SprintApplicationService
import com.bts.agileplanning.application.SprintIssueConflictException
import com.bts.agileplanning.application.SprintNotFoundException
import com.bts.agileplanning.application.SprintVersionConflictException
import com.bts.agileplanning.domain.InvalidSprintTransitionException
import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.openapitools.jackson.nullable.JsonNullable
import org.openapitools.jackson.nullable.JsonNullableModule
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.LocalDate
import java.util.UUID

/**
 * SprintController MockMvc 슬라이스 HTTP 통합 테스트.
 *
 * [SprintApplicationService] 는 MockK stub 으로 대체한다.
 * 권한 판정은 service 내부에서 수행하므로 service 가 [ResponseStatusException](403) 을 던지도록 stub 한다.
 * Spring Security 컨텍스트는 [SecurityContextHolder] 에 직접 UUID 기반 Authentication 을 주입한다.
 *
 * ### 검증 케이스
 * - CREATE-1. POST /api/v1/sprints 정상 → 201 + DataResponse 봉투
 * - CREATE-2. POST projectKey 미가시(권한 거부) → 403
 * - CREATE-3. POST name 빈 문자열 → 400 AGILE_VALIDATION_FAILED
 * - CREATE-4. POST 비인증 → 401
 * - GET-1. GET /api/v1/sprints/{id} 정상 → 200 + DataResponse 봉투
 * - GET-2. GET 미존재 → 404
 * - LIST-1. GET /api/v1/sprints?projectKey= 정상 → 200 + 배열
 * - LIST-2. GET 목록 권한 거부 → 403
 * - LIST-3. GET projectKey 누락 → 400
 * - PATCH-1. PATCH /api/v1/sprints/{id} 정상 → 200
 * - PATCH-2. PATCH name 빈 문자열 → 400
 * - DELETE-1. DELETE /api/v1/sprints/{id} 정상 → 204
 * - DELETE-2. DELETE 미존재 → 404
 * - START-1. POST /api/v1/sprints/{id}/start 정상 → 200
 * - START-2. POST start 허용되지 않는 전환 → 409 AGILE_CONFLICT
 * - COMPLETE-1. POST /api/v1/sprints/{id}/complete 정상 → 200
 * - COMPLETE-2. POST complete 허용되지 않는 전환 → 409 AGILE_CONFLICT
 * - ASSIGN-1. POST /api/v1/sprints/{id}/issues 정상(최초 할당) → 201
 * - ASSIGN-2. POST issues 이슈 미가시 → 404
 * - ASSIGN-3. POST issues COMPLETED 스프린트 → 409
 * - UNASSIGN-1. DELETE /api/v1/sprints/{id}/issues/{issueKey} 정상 → 204
 * - ORDER-1. 미인증 시 서비스가 호출되지 않는다(존재 probe 차단).
 * - SWALLOW-1. catch-all 이 InvalidSprintTransitionException(409) 를 500 으로 삼키지 않는다.
 * - SWALLOW-2. catch-all 이 SprintNotFoundException(404) 를 500 으로 삼키지 않는다.
 * - SWALLOW-3. catch-all 이 403 을 500 으로 삼키지 않는다.
 * - SWALLOW-4. path id 가 UUID 아님 → 400 (catch-all 이 500 으로 삼키지 않음).
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [SprintControllerTest.TestMvcConfig::class])
@WebAppConfiguration
@Suppress("LargeClass") // 스프린트 REST API 전 시나리오를 단일 슬라이스 테스트로 커버한다
class SprintControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [SprintController], [SprintExceptionHandler] 와 MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig : WebMvcConfigurer {
        @Bean
        open fun sprintApplicationService(): SprintApplicationService = mockk(relaxed = true)

        @Bean
        open fun sprintController(service: SprintApplicationService): SprintController = SprintController(service)

        @Bean
        open fun sprintExceptionHandler(): SprintExceptionHandler = SprintExceptionHandler()

        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            // @EnableWebMvc 슬라이스는 Boot 자동 구성을 우회하므로
            // 기존 Jackson 컨버터의 ObjectMapper 에 JsonNullableModule 을 추가 등록한다.
            converters
                .filterIsInstance<MappingJackson2HttpMessageConverter>()
                .forEach { it.objectMapper.registerModule(JsonNullableModule()) }
        }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var sprintApplicationService: SprintApplicationService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val actorId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val sprintId: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        clearMocks(sprintApplicationService)
        val auth =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = auth
    }

    /** 테스트용 스프린트 도메인 객체를 생성한다. */
    private fun sampleSprint(
        id: UUID = sprintId,
        projectKey: String = "BTS",
        status: SprintStatus = SprintStatus.PLANNED,
        version: Long = 0L,
    ): Sprint =
        Sprint(
            id = id,
            projectKey = projectKey,
            boardId = UUID.randomUUID(),
            name = "Sprint 1",
            goal = "스프린트 목표",
            status = status,
            startDate = LocalDate.of(2026, 7, 1),
            endDate = LocalDate.of(2026, 7, 14),
            version = version,
        )

    // ── CREATE-1. POST 정상 → 201 ─────────────────────────────────────────────

    @Test
    fun `POST sprints 정상 입력이면 201과 DataResponse 봉투를 반환한다`() {
        val sprint = sampleSprint()
        every {
            sprintApplicationService.create(actorId, "BTS", "Sprint 1", "목표", null, null)
        } returns sprint

        val body =
            mapOf(
                "projectKey" to "BTS",
                "name" to "Sprint 1",
                "goal" to "목표",
            )

        mockMvc.perform(
            post("/api/v1/sprints")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.sprintId").value(sprint.id.toString()))
            .andExpect(jsonPath("$.data.projectKey").value("BTS"))
            .andExpect(jsonPath("$.data.name").value("Sprint 1"))
            .andExpect(jsonPath("$.data.status").value("PLANNED"))
    }

    // ── CREATE-2. POST 권한 거부 → 403 ───────────────────────────────────────

    @Test
    fun `POST sprints 권한 거부 시 403을 반환한다`() {
        every {
            sprintApplicationService.create(any(), any(), any(), any(), any(), any())
        } throws ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")

        val body = mapOf("projectKey" to "UNKNOWN", "name" to "Sprint X")

        mockMvc.perform(
            post("/api/v1/sprints")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AGILE_ACCESS_DENIED"))
    }

    // ── CREATE-3. POST name 누락 → 400 ──────────────────────────────────────

    @Test
    fun `POST sprints name이 누락되면 400 AGILE_VALIDATION_FAILED를 반환한다`() {
        // name 은 non-nullable String 이므로 JSON 에서 누락되면 Jackson 이 HttpMessageNotReadableException 을 던진다.
        // HttpMessageNotReadableException → SprintExceptionHandler.handleHttpMessageNotReadable → 400.
        val body = mapOf("projectKey" to "BTS")

        mockMvc.perform(
            post("/api/v1/sprints")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))
    }

    // ── CREATE-4. POST 비인증 → 401 ──────────────────────────────────────────

    @Test
    fun `POST sprints 비인증이면 401을 반환한다`() {
        SecurityContextHolder.clearContext()
        val body = mapOf("projectKey" to "BTS", "name" to "Sprint 1")

        mockMvc.perform(
            post("/api/v1/sprints")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnauthorized)

        verify(exactly = 0) { sprintApplicationService.create(any(), any(), any(), any(), any(), any()) }
    }

    // ── GET-1. GET 단건 정상 → 200 ────────────────────────────────────────────

    @Test
    fun `GET sprints id 정상이면 200과 DataResponse 봉투를 반환한다`() {
        val sprint = sampleSprint()
        every { sprintApplicationService.get(actorId, sprintId) } returns sprint

        mockMvc.perform(get("/api/v1/sprints/$sprintId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sprintId").value(sprintId.toString()))
            .andExpect(jsonPath("$.data.status").value("PLANNED"))
    }

    // ── GET-2. GET 미존재 → 404 ──────────────────────────────────────────────

    @Test
    fun `GET sprints id 미존재면 404를 반환한다`() {
        every { sprintApplicationService.get(actorId, sprintId) } throws SprintNotFoundException()

        mockMvc.perform(get("/api/v1/sprints/$sprintId"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AGILE_SPRINT_NOT_FOUND"))
    }

    // ── LIST-1. GET 목록 정상 → 200 ──────────────────────────────────────────

    @Test
    fun `GET sprints 목록 정상이면 200과 배열을 반환한다`() {
        val sprints = listOf(sampleSprint(), sampleSprint(id = UUID.randomUUID(), version = 1L))
        every { sprintApplicationService.list(actorId, "BTS", null) } returns sprints

        mockMvc.perform(get("/api/v1/sprints").param("projectKey", "BTS"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
    }

    // ── LIST-2. GET 목록 권한 거부 → 403 ─────────────────────────────────────

    @Test
    fun `GET sprints 목록 권한 거부 시 403을 반환한다`() {
        every {
            sprintApplicationService.list(actorId, "BTS", null)
        } throws ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")

        mockMvc.perform(get("/api/v1/sprints").param("projectKey", "BTS"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AGILE_ACCESS_DENIED"))
    }

    // ── LIST-3. GET projectKey 누락 → 400 ────────────────────────────────────

    @Test
    fun `GET sprints projectKey 누락이면 400을 반환한다`() {
        mockMvc.perform(get("/api/v1/sprints"))
            .andExpect(status().isBadRequest)
    }

    // ── PATCH-1. PATCH 정상 → 200 ────────────────────────────────────────────

    @Test
    fun `PATCH sprints id 정상이면 200과 갱신된 스프린트를 반환한다`() {
        val updated = sampleSprint(version = 1L)
        every {
            sprintApplicationService.update(
                actorId,
                sprintId,
                JsonNullable.of("Sprint 1 Updated"),
                JsonNullable.of<String?>(null),
                JsonNullable.of<LocalDate?>(null),
                JsonNullable.of<LocalDate?>(null),
                0L,
            )
        } returns updated

        val body =
            mapOf(
                "name" to "Sprint 1 Updated",
                "goal" to null,
                "startDate" to null,
                "endDate" to null,
                "version" to 0,
            )

        mockMvc.perform(
            patch("/api/v1/sprints/$sprintId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sprintId").value(sprintId.toString()))
    }

    // ── PATCH-2. name만 전송하고 goal/날짜 미전송 시 200 (partial PATCH 보존) ─

    @Test
    fun `PATCH sprints name만 전송하고 goal과 날짜 미전송 시 200을 반환한다`() {
        // partial PATCH: name만 present, 나머지 undefined → 기존 값 유지
        val updated = sampleSprint(version = 1L)
        every {
            sprintApplicationService.update(
                actorId,
                sprintId,
                JsonNullable.of("X"),
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                0L,
            )
        } returns updated

        val body = mapOf("name" to "X", "version" to 0)

        mockMvc.perform(
            patch("/api/v1/sprints/$sprintId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sprintId").value(sprintId.toString()))
    }

    // ── DELETE-1. DELETE 정상 → 204 ──────────────────────────────────────────

    @Test
    fun `DELETE sprints id 정상이면 204를 반환한다`() {
        justRun { sprintApplicationService.softDelete(actorId, sprintId) }

        mockMvc.perform(delete("/api/v1/sprints/$sprintId"))
            .andExpect(status().isNoContent)
    }

    // ── DELETE-2. DELETE 미존재 → 404 ────────────────────────────────────────

    @Test
    fun `DELETE sprints id 미존재면 404를 반환한다`() {
        every { sprintApplicationService.softDelete(actorId, sprintId) } throws SprintNotFoundException()

        mockMvc.perform(delete("/api/v1/sprints/$sprintId"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AGILE_SPRINT_NOT_FOUND"))
    }

    // ── START-1. POST start 정상 → 200 ───────────────────────────────────────

    @Test
    fun `POST sprints id start 정상이면 200과 ACTIVE 스프린트를 반환한다`() {
        val started = sampleSprint(status = SprintStatus.ACTIVE, version = 1L)
        every { sprintApplicationService.start(actorId, sprintId) } returns started

        mockMvc.perform(post("/api/v1/sprints/$sprintId/start"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
    }

    // ── START-2. POST start 허용되지 않는 전환 → 409 ─────────────────────────

    @Test
    fun `POST sprints id start 허용되지 않는 전환이면 409 AGILE_CONFLICT를 반환한다`() {
        every {
            sprintApplicationService.start(actorId, sprintId)
        } throws InvalidSprintTransitionException("Cannot start sprint in status COMPLETED.")

        mockMvc.perform(post("/api/v1/sprints/$sprintId/start"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("AGILE_CONFLICT"))
    }

    // ── START-3. POST start 보드에 활성 스프린트 존재 → 409 전용 코드 ────────
    //
    // ★ 이 테스트가 잡는 것은 「409 가 나는가」가 아니라 「구별되는 코드가 나는가」다.
    // 일반 ResponseStatusException(CONFLICT) 은 SprintExceptionHandler 의 상태 전파 핸들러가
    // 무조건 AGILE_CONFLICT 로 덮어쓴다 — SprintCompletedAssignException 등 기존 409 3형제가
    // 실제로 그렇게 뭉뚱그려져 있다. 전용 @ExceptionHandler 없이는 이 단언이 통과할 수 없다.

    @Test
    fun `POST sprints id start 보드에 활성 스프린트가 있으면 409 AGILE_SPRINT_ALREADY_ACTIVE를 반환한다`() {
        every {
            sprintApplicationService.start(actorId, sprintId)
        } throws SprintAlreadyActiveException()

        mockMvc.perform(post("/api/v1/sprints/$sprintId/start"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("AGILE_SPRINT_ALREADY_ACTIVE"))
    }

    // ── COMPLETE-1. POST complete 정상 → 200 ─────────────────────────────────

    @Test
    fun `POST sprints id complete 정상이면 200과 COMPLETED 스프린트를 반환한다`() {
        val completed = sampleSprint(status = SprintStatus.COMPLETED, version = 2L)
        every { sprintApplicationService.complete(actorId, sprintId) } returns completed

        mockMvc.perform(post("/api/v1/sprints/$sprintId/complete"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
    }

    // ── COMPLETE-2. POST complete 허용되지 않는 전환 → 409 ───────────────────

    @Test
    fun `POST sprints id complete 허용되지 않는 전환이면 409 AGILE_CONFLICT를 반환한다`() {
        every {
            sprintApplicationService.complete(actorId, sprintId)
        } throws InvalidSprintTransitionException("Cannot complete sprint in status PLANNED.")

        mockMvc.perform(post("/api/v1/sprints/$sprintId/complete"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("AGILE_CONFLICT"))
    }

    // ── ASSIGN-1. POST issues 정상 → 201 ─────────────────────────────────────

    @Test
    fun `POST sprints id issues 정상이면 201을 반환한다`() {
        justRun { sprintApplicationService.assignIssue(actorId, sprintId, "BTS-1") }

        val body = mapOf("issueKey" to "BTS-1")

        mockMvc.perform(
            post("/api/v1/sprints/$sprintId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
    }

    // ── ASSIGN-2. POST issues 이슈 미가시 → 404 ──────────────────────────────

    @Test
    fun `POST sprints id issues 이슈 미가시면 404를 반환한다`() {
        every {
            sprintApplicationService.assignIssue(actorId, sprintId, "BTS-999")
        } throws ResponseStatusException(HttpStatus.NOT_FOUND, "이슈를 찾을 수 없습니다.")

        val body = mapOf("issueKey" to "BTS-999")

        mockMvc.perform(
            post("/api/v1/sprints/$sprintId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
    }

    // ── ASSIGN-3. POST issues COMPLETED 스프린트 → 409 ───────────────────────

    @Test
    fun `POST sprints id issues COMPLETED 스프린트면 409를 반환한다`() {
        every {
            sprintApplicationService.assignIssue(actorId, sprintId, "BTS-1")
        } throws ResponseStatusException(HttpStatus.CONFLICT, "완료된 스프린트에는 이슈를 할당할 수 없습니다.")

        val body = mapOf("issueKey" to "BTS-1")

        mockMvc.perform(
            post("/api/v1/sprints/$sprintId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
    }

    // ── UNASSIGN-1. DELETE issues/{issueKey} 정상 → 204 ─────────────────────

    @Test
    fun `DELETE sprints id issues issueKey 정상이면 204를 반환한다`() {
        justRun { sprintApplicationService.unassignIssue(actorId, sprintId, "BTS-1") }

        mockMvc.perform(delete("/api/v1/sprints/$sprintId/issues/BTS-1"))
            .andExpect(status().isNoContent)
    }

    // ── ASSIGN-4. POST issues 동시 할당 UNIQUE 위반 → 409 ────────────────────

    @Test
    fun `POST sprints id issues 동시 할당 UNIQUE 위반 시 service가 SprintIssueConflictException을 던지면 409를 반환한다`() {
        every {
            sprintApplicationService.assignIssue(actorId, sprintId, "BTS-1")
        } throws SprintIssueConflictException()

        val body = mapOf("issueKey" to "BTS-1")

        mockMvc.perform(
            post("/api/v1/sprints/$sprintId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("AGILE_CONFLICT"))
    }

    // ── OCC-1. PATCH OCC 버전 충돌 → 409 ────────────────────────────────────

    @Test
    fun `PATCH sprints id OCC 버전 충돌 시 service가 SprintVersionConflictException을 던지면 409를 반환한다`() {
        every {
            sprintApplicationService.update(
                actorId,
                sprintId,
                JsonNullable.of("Sprint 1 Updated"),
                JsonNullable.of<String?>(null),
                JsonNullable.of<LocalDate?>(null),
                JsonNullable.of<LocalDate?>(null),
                0L,
            )
        } throws SprintVersionConflictException()

        val body =
            mapOf(
                "name" to "Sprint 1 Updated",
                "goal" to null,
                "startDate" to null,
                "endDate" to null,
                "version" to 0,
            )

        mockMvc.perform(
            patch("/api/v1/sprints/$sprintId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("AGILE_CONFLICT"))
    }

    // ── SWALLOW-5. DataIntegrityViolationException → 409 (핸들러 동작 고정) ─

    @Test
    fun `service가 DataIntegrityViolationException을 SprintIssueConflictException으로 변환해 catch-all이 500으로 삼키지 않는다`() {
        // service 내부에서 DataIntegrityViolationException → SprintIssueConflictException 변환 후 throw.
        // 이 테스트는 컨트롤러 핸들러가 SprintIssueConflictException(409)를 정확히 전파하는지 검증한다.
        every {
            sprintApplicationService.assignIssue(actorId, sprintId, "BTS-1")
        } throws SprintIssueConflictException()

        val body = mapOf("issueKey" to "BTS-1")

        mockMvc.perform(
            post("/api/v1/sprints/$sprintId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
    }

    // ── ORDER-1. 미인증 시 서비스 호출 없음 ──────────────────────────────────

    @Test
    fun `미인증 시 서비스가 호출되지 않아 존재 probe를 차단한다`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/sprints/$sprintId"))
            .andExpect(status().isUnauthorized)

        verify(exactly = 0) { sprintApplicationService.get(any(), any()) }
    }

    // ── SWALLOW-1. InvalidSprintTransitionException → 409 (catch-all 삼킴 방지) ──

    @Test
    fun `catch-all이 InvalidSprintTransitionException을 500으로 삼키지 않고 409로 매핑한다`() {
        every {
            sprintApplicationService.start(actorId, sprintId)
        } throws InvalidSprintTransitionException("Cannot start sprint in status ACTIVE.")

        mockMvc.perform(post("/api/v1/sprints/$sprintId/start"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("AGILE_CONFLICT"))
    }

    // ── SWALLOW-2. SprintNotFoundException → 404 (catch-all 삼킴 방지) ─────────

    @Test
    fun `catch-all이 SprintNotFoundException을 500으로 삼키지 않고 404로 매핑한다`() {
        every { sprintApplicationService.get(actorId, sprintId) } throws SprintNotFoundException()

        mockMvc.perform(get("/api/v1/sprints/$sprintId"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AGILE_SPRINT_NOT_FOUND"))
    }

    // ── SWALLOW-3. 403 (catch-all 삼킴 방지) ────────────────────────────────

    @Test
    fun `catch-all이 403 ResponseStatusException을 500으로 삼키지 않는다`() {
        every {
            sprintApplicationService.get(actorId, sprintId)
        } throws ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")

        mockMvc.perform(get("/api/v1/sprints/$sprintId"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AGILE_ACCESS_DENIED"))
    }

    // ── SWALLOW-4. path id 타입 미스매치 → 400 ──────────────────────────────

    @Test
    fun `잘못된 UUID 형식의 path id는 400 AGILE_VALIDATION_FAILED를 반환한다`() {
        mockMvc.perform(get("/api/v1/sprints/not-a-uuid"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("AGILE_VALIDATION_FAILED"))
    }
}
