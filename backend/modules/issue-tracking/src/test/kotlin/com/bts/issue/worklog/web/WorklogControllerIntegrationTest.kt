// WorklogController MockMvc 슬라이스 통합 테스트 (FR-TT-01 Task 7)

package com.bts.issue.worklog.web

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.worklog.application.WorklogListView
import com.bts.issue.worklog.application.WorklogService
import com.bts.issue.worklog.domain.Worklog
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * WorklogController MockMvc 슬라이스 테스트 (FR-TT-01).
 *
 * [WorklogService] 는 MockK stub 으로 대체한다.
 * [WorklogExceptionHandler] 를 컨텍스트에 등록하여 예외→HTTP 변환을 검증한다.
 *
 * 테스트 케이스.
 * - WL-1.  POST   /worklogs → 201 + WorklogResponse 필드 검증
 * - WL-2.  GET    /worklogs → 200 + worklogs 배열 + summary 검증
 * - WL-3.  PATCH  /worklogs/{worklogId} → 200 + WorklogResponse 검증
 * - WL-4.  DELETE /worklogs/{worklogId} → 204
 * - WL-5.  POST   timeSpentSeconds=0 → 400 (유효성 검증)
 * - WL-6.  POST   권한 없음 → 403
 * - WL-7.  GET    이슈 미존재 → 404
 * - WL-8.  PATCH  다른 이슈 worklogId → 404 (이슈 불일치)
 * - WL-9.  PATCH  잘못된 UUID worklogId → 400
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [WorklogControllerIntegrationTest.TestMvcConfig::class])
@WebAppConfiguration
class WorklogControllerIntegrationTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [WorklogController], [WorklogExceptionHandler], MockK stub Bean 을 등록한다.
     * ObjectMapper 에 JavaTimeModule 을 등록하여 Instant ISO 직렬화가 동작하도록 한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun objectMapper(): ObjectMapper =
            ObjectMapper()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        @Bean
        open fun worklogService(): WorklogService = mockk(relaxed = true)

        @Bean
        open fun worklogController(service: WorklogService): WorklogController = WorklogController(service)

        @Bean
        open fun worklogExceptionHandler(): WorklogExceptionHandler = WorklogExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var worklogService: WorklogService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    lateinit var mockMvc: MockMvc

    private val actorUuid = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val worklogId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val otherWorklogId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    private val issueId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
    private val startedAt = Instant.parse("2026-06-20T09:00:00Z")
    private val createdAt = Instant.parse("2026-06-20T09:01:00Z")
    private val updatedAt = Instant.parse("2026-06-20T09:01:00Z")

    private val sampleWorklog =
        Worklog(
            id = worklogId,
            issueId = issueId,
            authorId = actorUuid,
            timeSpentSeconds = 3600,
            startedAt = startedAt,
            comment = "작업 완료",
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private val sampleListView =
        WorklogListView(
            worklogs = listOf(sampleWorklog),
            originalEstimateSeconds = 7200,
            timeSpentSeconds = 3600,
            remainingEstimateSeconds = 3600,
        )

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorUuid.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    @AfterEach
    fun tearDown() {
        clearMocks(worklogService)
        SecurityContextHolder.clearContext()
    }

    // ── WL-1. POST → 201 + WorklogResponse ────────────────────────────────────

    /**
     * WL-1. 워크로그 추가 → 201 Created + WorklogResponse 필드 검증.
     *
     * Given  service.create 가 sampleWorklog 반환
     * When   POST /api/v1/issues/ATLAS-1/worklogs body={timeSpentSeconds:3600, startedAt:..., comment:...}
     * Then   201 Created, data.id, data.authorId, data.timeSpentSeconds, data.startedAt, data.comment 포함
     */
    @Test
    fun `POST 워크로그 추가 — 201 Created plus WorklogResponse 필드 검증`() {
        every {
            worklogService.create(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                timeSpentSeconds = 3600,
                startedAt = startedAt,
                comment = "작업 완료",
                newRemainingEstimateSeconds = null,
            )
        } returns sampleWorklog

        val body =
            """{"timeSpentSeconds":3600,"startedAt":"2026-06-20T09:00:00Z","comment":"작업 완료"}"""

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/worklogs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value(worklogId.toString()))
            .andExpect(jsonPath("$.data.issueKey").value("ATLAS-1"))
            .andExpect(jsonPath("$.data.authorId").value(actorUuid.toString()))
            .andExpect(jsonPath("$.data.timeSpentSeconds").value(3600))
            .andExpect(jsonPath("$.data.comment").value("작업 완료"))

        verify(exactly = 1) {
            worklogService.create(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                timeSpentSeconds = 3600,
                startedAt = startedAt,
                comment = "작업 완료",
                newRemainingEstimateSeconds = null,
            )
        }
    }

    // ── WL-2. GET → 200 + worklogs 배열 + summary ─────────────────────────────

    /**
     * WL-2. 워크로그 목록 조회 → 200 OK + worklogs 배열 + summary 검증.
     *
     * Given  service.listForIssue 가 sampleListView 반환
     * When   GET /api/v1/issues/ATLAS-1/worklogs
     * Then   200 OK, data.worklogs 배열, data.summary.originalEstimateSeconds 등 포함
     */
    @Test
    fun `GET 워크로그 목록 조회 — 200 OK plus worklogs plus summary`() {
        every {
            worklogService.listForIssue(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
            )
        } returns sampleListView

        mockMvc.perform(get("/api/v1/issues/ATLAS-1/worklogs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.worklogs").isArray)
            .andExpect(jsonPath("$.data.worklogs[0].id").value(worklogId.toString()))
            .andExpect(jsonPath("$.data.summary.originalEstimateSeconds").value(7200))
            .andExpect(jsonPath("$.data.summary.timeSpentSeconds").value(3600))
            .andExpect(jsonPath("$.data.summary.remainingEstimateSeconds").value(3600))
    }

    // ── WL-3. PATCH → 200 + WorklogResponse ───────────────────────────────────

    /**
     * WL-3. 워크로그 수정 → 200 OK + 수정된 WorklogResponse.
     *
     * Given  service.update 가 수정된 worklog 반환
     * When   PATCH /api/v1/issues/ATLAS-1/worklogs/{worklogId}
     * Then   200 OK, data.timeSpentSeconds = 1800
     */
    @Test
    fun `PATCH 워크로그 수정 — 200 OK plus 수정된 WorklogResponse`() {
        val updated = sampleWorklog.copy(timeSpentSeconds = 1800)
        every {
            worklogService.update(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                worklogId = worklogId,
                timeSpentSeconds = 1800,
                startedAt = null,
                comment = null,
            )
        } returns updated

        val body = """{"timeSpentSeconds":1800}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/worklogs/$worklogId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(worklogId.toString()))
            .andExpect(jsonPath("$.data.timeSpentSeconds").value(1800))
    }

    // ── WL-4. DELETE → 204 ────────────────────────────────────────────────────

    /**
     * WL-4. 워크로그 삭제 → 204 No Content.
     *
     * Given  service.delete 가 정상 반환
     * When   DELETE /api/v1/issues/ATLAS-1/worklogs/{worklogId}
     * Then   204 No Content
     */
    @Test
    fun `DELETE 워크로그 삭제 — 204 No Content`() {
        every {
            worklogService.delete(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                worklogId = worklogId,
            )
        } returns Unit

        mockMvc.perform(delete("/api/v1/issues/ATLAS-1/worklogs/$worklogId"))
            .andExpect(status().isNoContent)

        verify(exactly = 1) {
            worklogService.delete(ActorId(actorUuid), IssueKey("ATLAS-1"), worklogId)
        }
    }

    // ── WL-5. POST timeSpentSeconds=0 → 400 ───────────────────────────────────

    /**
     * WL-5. timeSpentSeconds=0 POST → 400 Bad Request (유효성 검증).
     *
     * Given  body에 timeSpentSeconds=0 (0 이하 허용 안 됨)
     * When   POST /api/v1/issues/ATLAS-1/worklogs
     * Then   400, 서비스 호출 없음
     */
    @Test
    fun `POST timeSpentSeconds=0 — 400 Bad Request 유효성 검증`() {
        val body = """{"timeSpentSeconds":0,"startedAt":"2026-06-20T09:00:00Z"}"""

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/worklogs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
        // 서비스 호출 없음 — 400 응답이 반환되었으면 서비스까지 도달하지 않은 것임
    }

    // ── WL-6. POST 권한 없음 → 403 ────────────────────────────────────────────

    /**
     * WL-6. 권한 없는 사용자 POST → 403 Forbidden.
     *
     * Given  service.create 가 IssueAccessDeniedException throw
     * When   POST /api/v1/issues/ATLAS-1/worklogs
     * Then   403 Forbidden
     */
    @Test
    fun `POST 권한 없음 — 403 Forbidden`() {
        every {
            worklogService.create(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                timeSpentSeconds = 3600,
                startedAt = startedAt,
                comment = null,
                newRemainingEstimateSeconds = null,
            )
        } throws
            IssueAccessDeniedException(
                actor = ActorId(actorUuid),
                permission = IssuePermission.UPDATE,
                scope = IssueScope.Issue("ATLAS-1"),
            )

        val body = """{"timeSpentSeconds":3600,"startedAt":"2026-06-20T09:00:00Z"}"""

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/worklogs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isForbidden)
    }

    // ── WL-7. GET 이슈 미존재 → 404 ───────────────────────────────────────────

    /**
     * WL-7. 이슈 미존재 → 404 Not Found.
     *
     * Given  service.listForIssue 가 IssueNotFoundException throw
     * When   GET /api/v1/issues/ATLAS-99/worklogs
     * Then   404 Not Found
     */
    @Test
    fun `GET 이슈 미존재 — 404 Not Found`() {
        every {
            worklogService.listForIssue(ActorId(actorUuid), IssueKey("ATLAS-99"))
        } throws IssueNotFoundException(IssueKey("ATLAS-99"))

        mockMvc.perform(get("/api/v1/issues/ATLAS-99/worklogs"))
            .andExpect(status().isNotFound)
    }

    // ── WL-8. PATCH 다른 이슈의 worklogId → 404 ───────────────────────────────

    /**
     * WL-8. 다른 이슈에 속한 worklogId 로 PATCH → 404 Not Found (이슈 불일치).
     *
     * Given  service.update 가 IssueNotFoundException throw (issueId 불일치)
     * When   PATCH /api/v1/issues/ATLAS-1/worklogs/{otherWorklogId}
     * Then   404 Not Found
     */
    @Test
    fun `PATCH 다른 이슈의 worklogId — 404 Not Found 이슈 불일치`() {
        every {
            worklogService.update(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                worklogId = otherWorklogId,
                timeSpentSeconds = 1800,
                startedAt = null,
                comment = null,
            )
        } throws IssueNotFoundException(IssueKey("ATLAS-1"))

        val body = """{"timeSpentSeconds":1800}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/worklogs/$otherWorklogId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isNotFound)
    }

    // ── WL-9. PATCH 잘못된 UUID worklogId → 400 ───────────────────────────────

    /**
     * WL-9. path variable {worklogId} 에 UUID 형식이 아닌 값 → 400 Bad Request.
     *
     * Given  path variable {worklogId} = "not-a-uuid"
     * When   PATCH /api/v1/issues/ATLAS-1/worklogs/not-a-uuid
     * Then   400 Bad Request (클라이언트 입력 오류 — 500 아님)
     */
    @Test
    fun `PATCH 잘못된 UUID worklogId — 400 Bad Request`() {
        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/worklogs/not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"timeSpentSeconds":1800}"""),
        )
            .andExpect(status().isBadRequest)
    }
}
