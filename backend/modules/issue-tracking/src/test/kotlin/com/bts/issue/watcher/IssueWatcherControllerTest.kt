// 이슈 워처 REST 컨트롤러 MockMvc 슬라이스 테스트 (FR-WT-01)

package com.bts.issue.watcher

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.watcher.application.IssueWatcherService
import com.bts.issue.watcher.application.WatcherEntry
import com.bts.issue.watcher.application.WatcherListResult
import com.bts.issue.watcher.application.WatcherUserNotFoundException
import com.bts.issue.watcher.web.IssueWatcherController
import com.bts.issue.watcher.web.WatcherExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

/**
 * IssueWatcherController MockMvc 슬라이스 테스트.
 *
 * [IssueWatcherService] 는 MockK stub으로 대체한다.
 * [WatcherExceptionHandler] 를 컨텍스트에 등록하여 예외→HTTP 변환을 검증한다.
 *
 * 테스트 케이스.
 * - W-1. GET  /watchers → 200 + {watchers, count, isWatching}
 * - W-2. POST /watchers body 없음(self) → 201
 * - W-3. POST /watchers body {userId:타인} → 201
 * - W-4. POST /watchers body {userId:타인} 권한 없음 → 403
 * - W-5. POST /watchers body {userId:존재하지않는사용자} → 422
 * - W-6. DELETE /watchers/{userId} → 204
 * - W-7. DELETE /watchers/{userId} 멱등(없어도 204) → 204
 * - W-8. GET  /watchers 이슈 미존재 → 404
 * - W-9. 서비스 내부 오류 → 500 (fallback)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueWatcherControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueWatcherControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [IssueWatcherController], [WatcherExceptionHandler], MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun objectMapper(): ObjectMapper = ObjectMapper()

        @Bean
        open fun issueWatcherService(): IssueWatcherService = mockk(relaxed = true)

        @Bean
        open fun issueWatcherController(service: IssueWatcherService): IssueWatcherController {
            return IssueWatcherController(service)
        }

        @Bean
        open fun watcherExceptionHandler(): WatcherExceptionHandler = WatcherExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueWatcherService: IssueWatcherService

    lateinit var mockMvc: MockMvc

    private val actorUuid = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val otherUuid = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val missingUuid = UUID.fromString("99999999-9999-4999-8999-999999999999")

    private val sampleWatcherEntry =
        WatcherEntry(
            userId = actorUuid,
            displayName = "Alice",
        )

    private val sampleResult =
        WatcherListResult(
            watchers = listOf(sampleWatcherEntry),
            count = 1,
            isWatching = true,
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
        clearMocks(issueWatcherService)
        SecurityContextHolder.clearContext()
    }

    // ── W-1. GET → 200 ────────────────────────────────────────────────────────

    /**
     * W-1. 워처 목록 조회 → 200 OK + {watchers, count, isWatching}.
     *
     * Given  service.listWatchers 가 sampleResult 를 반환함
     * When   GET /api/v1/issues/ATLAS-1/watchers
     * Then   200 OK, data.watchers 배열, data.count, data.isWatching 포함
     */
    @Test
    fun `GET 워처 목록 조회 — 200 OK plus watchers plus count plus isWatching`() {
        every {
            issueWatcherService.listWatchers(IssueKey("ATLAS-1"), ActorId(actorUuid))
        } returns sampleResult

        mockMvc.perform(get("/api/v1/issues/ATLAS-1/watchers"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.watchers").isArray)
            .andExpect(jsonPath("$.data.watchers[0].userId").value(actorUuid.toString()))
            .andExpect(jsonPath("$.data.watchers[0].displayName").value("Alice"))
            .andExpect(jsonPath("$.data.count").value(1))
            .andExpect(jsonPath("$.data.isWatching").value(true))
    }

    // ── W-2. POST self(body 없음) → 201 ─────────────────────────────────────

    /**
     * W-2. body 없이 POST → self 워처 추가 → 201 Created.
     *
     * Given  service.watch(_, actor, null) 이 정상 반환
     * When   POST /api/v1/issues/ATLAS-1/watchers (body 없음)
     * Then   201 Created
     */
    @Test
    fun `POST body 없음 — self 워처 추가 201`() {
        every {
            issueWatcherService.watch(IssueKey("ATLAS-1"), ActorId(actorUuid), null)
        } returns Unit

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/watchers")
                .contentType(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isCreated)

        verify(exactly = 1) {
            issueWatcherService.watch(IssueKey("ATLAS-1"), ActorId(actorUuid), null)
        }
    }

    // ── W-3. POST 타인 → 201 ─────────────────────────────────────────────────

    /**
     * W-3. body {userId:타인} → 타인 워처 추가 → 201 Created.
     *
     * Given  service.watch(_, actor, otherUuid) 이 정상 반환
     * When   POST /api/v1/issues/ATLAS-1/watchers  body={userId: otherUuid}
     * Then   201 Created
     */
    @Test
    fun `POST userId 타인 — 타인 워처 추가 201`() {
        every {
            issueWatcherService.watch(IssueKey("ATLAS-1"), ActorId(actorUuid), otherUuid)
        } returns Unit

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/watchers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$otherUuid"}"""),
        )
            .andExpect(status().isCreated)

        verify(exactly = 1) {
            issueWatcherService.watch(IssueKey("ATLAS-1"), ActorId(actorUuid), otherUuid)
        }
    }

    // ── W-4. POST 타인 권한 없음 → 403 ───────────────────────────────────────

    /**
     * W-4. 타인 추가 시 권한 없음 → 403 Forbidden.
     *
     * Given  service.watch 가 IssueAccessDeniedException 을 throw
     * When   POST /api/v1/issues/ATLAS-1/watchers  body={userId: otherUuid}
     * Then   403 Forbidden
     */
    @Test
    fun `POST 타인 추가 권한 없음 — 403 Forbidden`() {
        every {
            issueWatcherService.watch(IssueKey("ATLAS-1"), ActorId(actorUuid), otherUuid)
        } throws
            IssueAccessDeniedException(
                actor = ActorId(actorUuid),
                permission = com.bts.shared.permission.IssuePermission.UPDATE,
                scope = com.bts.shared.permission.IssueScope.Issue("ATLAS-1"),
            )

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/watchers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$otherUuid"}"""),
        )
            .andExpect(status().isForbidden)
    }

    // ── W-5. POST 존재하지 않는 userId → 422 ─────────────────────────────────

    /**
     * W-5. 존재하지 않는 userId → 422 Unprocessable Entity.
     *
     * Given  service.watch 가 WatcherUserNotFoundException 을 throw
     * When   POST /api/v1/issues/ATLAS-1/watchers  body={userId: missingUuid}
     * Then   422, errorCode=ISSUE_WATCHER_USER_NOT_FOUND
     */
    @Test
    fun `POST 존재하지 않는 userId — 422 Unprocessable Entity`() {
        every {
            issueWatcherService.watch(IssueKey("ATLAS-1"), ActorId(actorUuid), missingUuid)
        } throws WatcherUserNotFoundException(missingUuid)

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/watchers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$missingUuid"}"""),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_WATCHER_USER_NOT_FOUND"))
            // userId 가 응답 본문에 노출되지 않음을 검증
            .andExpect(jsonPath("$.detail").value(not(containsString(missingUuid.toString()))))
    }

    // ── W-6. DELETE → 204 ────────────────────────────────────────────────────

    /**
     * W-6. 워처 제거 → 204 No Content.
     *
     * Given  service.unwatch 가 정상 반환
     * When   DELETE /api/v1/issues/ATLAS-1/watchers/{userId}
     * Then   204 No Content
     */
    @Test
    fun `DELETE 워처 제거 — 204 No Content`() {
        every {
            issueWatcherService.unwatch(IssueKey("ATLAS-1"), ActorId(actorUuid), actorUuid)
        } returns Unit

        mockMvc.perform(delete("/api/v1/issues/ATLAS-1/watchers/$actorUuid"))
            .andExpect(status().isNoContent)

        verify(exactly = 1) {
            issueWatcherService.unwatch(IssueKey("ATLAS-1"), ActorId(actorUuid), actorUuid)
        }
    }

    // ── W-7. DELETE 멱등(없어도 204) ─────────────────────────────────────────

    /**
     * W-7. 이미 없는 워처 제거(멱등) → 204 No Content.
     *
     * Given  service.unwatch 가 정상 반환 (IssueWatcherRepository.remove 가 false 반환해도 서비스가 무시)
     * When   DELETE /api/v1/issues/ATLAS-1/watchers/{userId}
     * Then   204 No Content (멱등)
     */
    @Test
    fun `DELETE 멱등 — 없어도 204 No Content`() {
        every {
            issueWatcherService.unwatch(IssueKey("ATLAS-1"), ActorId(actorUuid), otherUuid)
        } returns Unit

        mockMvc.perform(delete("/api/v1/issues/ATLAS-1/watchers/$otherUuid"))
            .andExpect(status().isNoContent)
    }

    // ── W-8. 이슈 미존재 → 404 ───────────────────────────────────────────────

    /**
     * W-8. 이슈 미존재 → 404 Not Found.
     *
     * Given  service.listWatchers 가 IssueNotFoundException 을 throw
     * When   GET /api/v1/issues/ATLAS-99/watchers
     * Then   404 Not Found
     */
    @Test
    fun `GET 이슈 미존재 — 404 Not Found`() {
        every {
            issueWatcherService.listWatchers(IssueKey("ATLAS-99"), ActorId(actorUuid))
        } throws IssueNotFoundException(IssueKey("ATLAS-99"))

        mockMvc.perform(get("/api/v1/issues/ATLAS-99/watchers"))
            .andExpect(status().isNotFound)
    }

    // ── W-9. 서비스 내부 오류 → 500 ──────────────────────────────────────────

    /**
     * W-9. 분류되지 않은 예외 → 500 Internal Server Error + errorCode.
     *
     * Given  service.listWatchers 가 RuntimeException 을 throw
     * When   GET /api/v1/issues/ATLAS-1/watchers
     * Then   500, errorCode=ISSUE_INTERNAL_ERROR
     */
    @Test
    fun `GET 서비스 내부 오류 — 500 Internal Server Error`() {
        every {
            issueWatcherService.listWatchers(IssueKey("ATLAS-1"), ActorId(actorUuid))
        } throws RuntimeException("DB 연결 실패")

        mockMvc.perform(get("/api/v1/issues/ATLAS-1/watchers"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_INTERNAL_ERROR"))
    }
}
