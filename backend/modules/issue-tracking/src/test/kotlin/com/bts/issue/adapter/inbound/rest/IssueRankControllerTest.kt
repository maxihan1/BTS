// PATCH /api/v1/issues/{key}/rank 엔드포인트 MockMvc 슬라이스 테스트 (FR-BL-01 Task 5)

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.BacklogRankService
import com.bts.issue.application.InvalidRankNeighborException
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.shared.permission.IssuePermission
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
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
 * PATCH /api/v1/issues/{key}/rank 엔드포인트 MockMvc 슬라이스 테스트 (FR-BL-01 Task 5).
 *
 * [BacklogRankService] 는 상태를 가진 Fake stub 으로 대체한다.
 * IssueKey 가 @JvmInline value class 라서 MockK any() 시그니처 생성 시
 * constructor validation 에 걸리므로 Fake 구현으로 우회한다
 * (메모리 fr-is-06-handoff-mockk-detekt-traps).
 *
 * [IssueApplicationService] 는 relaxed MockK stub 으로 대체한다.
 * [IssueExceptionHandler] 를 컨텍스트에 등록하여 예외 → HTTP 상태 변환을 검증한다.
 * 특히 [InvalidRankNeighborException] 이 catch-all 500 이 아닌 400 으로 응답하는지
 * MockMvc 레벨에서 단언한다 (B1 + 메모리 catch-all-exceptionhandler-swallows-responsestatusexception).
 *
 * 테스트 케이스.
 * - R1. 200: 정상 리랭크 — rank/version 응답 검증.
 * - R2. 400: [InvalidRankNeighborException] → 400 (catch-all 500 변질 차단 검증).
 * - R3. 403: [IssueAccessDeniedException] → 403.
 * - R4. 404: [IssueNotFoundException] → 404.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueRankControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueRankControllerTest {
    /**
     * BacklogRankService 동작을 테스트별로 교체 가능한 Fake stub.
     *
     * @JvmInline value class IssueKey 와 MockK any() 매처 충돌을 Fake 로 우회한다.
     * rerankAction 람다에 원하는 예외 또는 정상 동작을 설정한다.
     */
    class FakeBacklogRankService : BacklogRankService(
        repo = mockk(relaxed = true),
        permissionResolver = mockk(relaxed = true),
        dsl = mockk(relaxed = true),
    ) {
        var rerankAction: () -> Unit = {}
        var findRankResult: String? = null
        var findVersionResult: Long? = 1L

        override fun rerank(
            actor: ActorId,
            key: IssueKey,
            previousIssueKey: IssueKey?,
            nextIssueKey: IssueKey?,
        ) = rerankAction()

        override fun findRankByKey(key: IssueKey): String? = findRankResult

        override fun findVersionByKey(key: IssueKey): Long? = findVersionResult
    }

    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [IssueController], [IssueExceptionHandler], Fake stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun issueApplicationService(): IssueApplicationService = mockk(relaxed = true)

        @Bean
        open fun backlogRankService(): FakeBacklogRankService = FakeBacklogRankService()

        @Bean
        open fun issueController(
            service: IssueApplicationService,
            backlogRankService: FakeBacklogRankService,
        ): IssueController = IssueController(service, backlogRankService = backlogRankService)

        @Bean
        open fun issueExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var fakeBacklogRankService: FakeBacklogRankService

    lateinit var mockMvc: MockMvc

    private val mapper = ObjectMapper()
    private val actorUuid = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val issueKey = "BTS-3"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorUuid.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        // 각 테스트 전 Fake 상태 초기화
        fakeBacklogRankService.rerankAction = {}
        fakeBacklogRankService.findRankResult = null
        fakeBacklogRankService.findVersionResult = 1L
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── R1. 정상 리랭크 — 200 + { data: { key, rank, version } } ─────────────────

    @Test
    fun `PATCH rank 정상 — 200 응답에 key, rank, version 포함`() {
        val previousKey = "BTS-1"
        val nextKey = "BTS-2"
        val expectedRank = "g"
        val expectedVersion = 3L

        // Fake stub: rerank 정상, findRankByKey/findVersionByKey 반환값 설정.
        fakeBacklogRankService.rerankAction = {}
        fakeBacklogRankService.findRankResult = expectedRank
        fakeBacklogRankService.findVersionResult = expectedVersion

        val body = mapper.writeValueAsString(
            mapOf("previousIssueKey" to previousKey, "nextIssueKey" to nextKey),
        )

        mockMvc.perform(
            patch("/api/v1/issues/$issueKey/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value(issueKey))
            .andExpect(jsonPath("$.data.rank").value(expectedRank))
            .andExpect(jsonPath("$.data.version").value(expectedVersion))
    }

    // ── R2. InvalidRankNeighborException → 400 (catch-all 500 변질 차단) ─────────

    @Test
    fun `PATCH rank 이웃 검증 실패 — InvalidRankNeighborException 이 400 으로 응답`() {
        // Fake stub 에서 InvalidRankNeighborException 을 던진다.
        // catch-all Exception→500 이 이 예외를 삼키지 않는지 MockMvc 레벨에서 검증한다 (B1).
        fakeBacklogRankService.rerankAction = {
            throw InvalidRankNeighborException("previousIssueKey 와 nextIssueKey 가 둘 다 null 입니다.")
        }

        val body = mapper.writeValueAsString(
            mapOf("previousIssueKey" to null, "nextIssueKey" to null),
        )

        mockMvc.perform(
            patch("/api/v1/issues/$issueKey/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("INVALID_RANK_NEIGHBOR"))
    }

    // ── R3. IssueAccessDeniedException → 403 ─────────────────────────────────────

    @Test
    fun `PATCH rank 권한 없음 — 403 응답`() {
        val actor = ActorId(actorUuid)
        fakeBacklogRankService.rerankAction = {
            throw IssueAccessDeniedException(
                actor,
                IssuePermission.UPDATE,
                com.bts.shared.permission.IssueScope.Issue(issueKey),
            )
        }

        val body = mapper.writeValueAsString(
            mapOf("previousIssueKey" to "BTS-1", "nextIssueKey" to null),
        )

        mockMvc.perform(
            patch("/api/v1/issues/$issueKey/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
    }

    // ── R4. IssueNotFoundException → 404 ──────────────────────────────────────────

    @Test
    fun `PATCH rank 이슈 미존재 — 404 응답`() {
        fakeBacklogRankService.rerankAction = {
            throw IssueNotFoundException(IssueKey(issueKey))
        }

        val body = mapper.writeValueAsString(
            mapOf("previousIssueKey" to null, "nextIssueKey" to "BTS-2"),
        )

        mockMvc.perform(
            patch("/api/v1/issues/$issueKey/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

}
