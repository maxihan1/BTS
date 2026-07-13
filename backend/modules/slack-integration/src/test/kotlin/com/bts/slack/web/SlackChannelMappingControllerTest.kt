// SlackChannelMappingController 슬라이스 테스트 — 매핑 CRUD 4연산 + 권한/검증/미인증 매핑 (FR-SL-06 Task 7)

package com.bts.slack.web

import com.bts.slack.SlackTestSecurityConfig
import com.bts.slack.application.SlackChannelMappingConflictException
import com.bts.slack.application.SlackChannelMappingNotFoundException
import com.bts.slack.application.SlackChannelMappingPermissionDeniedException
import com.bts.slack.application.SlackChannelMappingService
import com.bts.slack.application.WorkspaceNotInstalledException
import com.bts.slack.domain.ChannelProjectMapping
import com.bts.slack.domain.SlackChannelEventType
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * [SlackChannelMappingController] `@WebMvcTest` 슬라이스 테스트 (FR-SL-06 Task 7).
 *
 * ## 검증 시나리오
 * - POST 201 생성 · GET 200 목록 · PATCH 200 수정 · DELETE 204 삭제.
 * - 예외별 상태매핑 — [SlackChannelMappingPermissionDeniedException] 403,
 *   [SlackChannelMappingNotFoundException] 404, [SlackChannelMappingConflictException]/
 *   [WorkspaceNotInstalledException] 409, `IllegalArgumentException`(eventTypes 도메인 검증) 400.
 * - 미인증 401([SlackTestSecurityConfig] 필터 체인이 거부) + PAT 스타일 non-[Jwt] principal 401
 *   (`SlackConnectionController` 선례와 동일 회귀 가드).
 * - 403 응답 바디는 내부 사정(비관리자 사유·프로젝트 존재 여부)을 노출하지 않는 일반 메시지만 담는다.
 * - 응답 바디에 `team_id`/`teamId` 미포함(§1.1.2).
 *
 * ## 인증 postprocessor — [Jwt] 는 직접 조립, `.jwt()` 미사용
 * [SlackConnectionControllerTest] 와 동일 이유 — `SecurityMockMvcRequestPostProcessors.jwt()`가
 * 요구하는 `spring-security-oauth2-resource-server` 의존성을 이 모듈이 갖지 않는다.
 */
@WebMvcTest(controllers = [SlackChannelMappingController::class])
@Import(SlackTestSecurityConfig::class, SlackChannelMappingControllerTest.SecurityBeans::class)
class SlackChannelMappingControllerTest {
    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun slackChannelMappingService(): SlackChannelMappingService = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var service: SlackChannelMappingService

    private val userId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val mappingId = UUID.fromString("22222222-2222-4222-8222-222222222222")

    @BeforeEach
    fun resetMock() {
        clearMocks(service)
    }

    private fun jwtFor(uid: UUID): Jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .claim("sub", uid.toString())
            .subject(uid.toString())
            .issuedAt(Instant.parse("2026-07-13T00:00:00Z"))
            .expiresAt(Instant.parse("2026-07-13T01:00:00Z"))
            .build()

    private fun jwtAuth(uid: UUID): RequestPostProcessor =
        authentication(
            UsernamePasswordAuthenticationToken(jwtFor(uid), null, listOf(SimpleGrantedAuthority("ROLE_USER"))),
        )

    /** PAT(개인 액세스 토큰) 흉내 — principal이 UUID 문자열일 뿐 [Jwt] 타입이 아니다. */
    private fun patAuth(uid: UUID): RequestPostProcessor =
        authentication(
            UsernamePasswordAuthenticationToken(uid.toString(), null, listOf(SimpleGrantedAuthority("ROLE_PAT"))),
        )

    private fun sampleMapping(): ChannelProjectMapping =
        ChannelProjectMapping(
            id = mappingId,
            teamId = "T999",
            projectKey = "PROJ",
            channelId = "C123",
            channelName = "general",
            eventTypes = setOf(SlackChannelEventType.ISSUE_CREATED, SlackChannelEventType.ISSUE_TRANSITIONED),
            createdAt = FIXED_CREATED_AT,
            updatedAt = FIXED_CREATED_AT,
        )

    // ── POST /api/v1/slack/channel-mappings ────────────────────────────────────

    @Test
    fun `POST channel-mappings returns 201 with created mapping`() {
        every {
            service.create(userId, "PROJ", "C123", "general", setOf(SlackChannelEventType.ISSUE_CREATED))
        } returns sampleMapping()

        mockMvc.perform(
            post("/api/v1/slack/channel-mappings")
                .with(jwtAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"projectKey":"PROJ","channelId":"C123","channelName":"general","eventTypes":["issue.created"]}""",
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(mappingId.toString()))
            .andExpect(jsonPath("$.projectKey").value("PROJ"))
            .andExpect(jsonPath("$.channelId").value("C123"))
            .andExpect(jsonPath("$.channelName").value("general"))
            .andExpect(jsonPath("$.eventTypes[0]").value("issue.created"))
            .andExpect(jsonPath("$.eventTypes[1]").value("issue.transitioned"))
    }

    @Test
    fun `POST channel-mappings returns 401 without authentication`() {
        mockMvc.perform(
            post("/api/v1/slack/channel-mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"projectKey":"PROJ","channelId":"C123","eventTypes":["issue.created"]}"""),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST channel-mappings with PAT-style non-Jwt principal returns 401`() {
        mockMvc.perform(
            post("/api/v1/slack/channel-mappings")
                .with(patAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"projectKey":"PROJ","channelId":"C123","eventTypes":["issue.created"]}"""),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST channel-mappings returns 400 when eventTypes empty or unknown`() {
        every {
            service.create(userId, "PROJ", "C123", null, emptySet())
        } throws IllegalArgumentException(ChannelProjectMapping.EMPTY_EVENT_TYPES_MESSAGE)

        mockMvc.perform(
            post("/api/v1/slack/channel-mappings")
                .with(jwtAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"projectKey":"PROJ","channelId":"C123","eventTypes":[]}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("SLACK_CHANNEL_MAPPING_INVALID"))
    }

    @Test
    fun `POST channel-mappings returns 403 when actor lacks manage permission`() {
        every {
            service.create(userId, "PROJ", "C123", null, setOf(SlackChannelEventType.ISSUE_CREATED))
        } throws SlackChannelMappingPermissionDeniedException()

        val body =
            mockMvc.perform(
                post("/api/v1/slack/channel-mappings")
                    .with(jwtAuth(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"projectKey":"PROJ","channelId":"C123","eventTypes":["issue.created"]}"""),
            )
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("SLACK_CHANNEL_MAPPING_FORBIDDEN"))
                .andReturn()
                .response
                .contentAsString

        assertThat(body).doesNotContain("not allowed to manage")
    }

    @Test
    fun `POST channel-mappings returns 409 when workspace not installed`() {
        every {
            service.create(userId, "PROJ", "C123", null, setOf(SlackChannelEventType.ISSUE_CREATED))
        } throws WorkspaceNotInstalledException()

        mockMvc.perform(
            post("/api/v1/slack/channel-mappings")
                .with(jwtAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"projectKey":"PROJ","channelId":"C123","eventTypes":["issue.created"]}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_INSTALLED"))
    }

    @Test
    fun `POST channel-mappings returns 409 when mapping already exists`() {
        every {
            service.create(userId, "PROJ", "C123", null, setOf(SlackChannelEventType.ISSUE_CREATED))
        } throws SlackChannelMappingConflictException()

        mockMvc.perform(
            post("/api/v1/slack/channel-mappings")
                .with(jwtAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"projectKey":"PROJ","channelId":"C123","eventTypes":["issue.created"]}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SLACK_CHANNEL_MAPPING_CONFLICT"))
    }

    @Test
    fun `POST channel-mappings response body does not expose team_id`() {
        every {
            service.create(userId, "PROJ", "C123", "general", setOf(SlackChannelEventType.ISSUE_CREATED))
        } returns sampleMapping()

        val body =
            mockMvc.perform(
                post("/api/v1/slack/channel-mappings")
                    .with(jwtAuth(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"projectKey":"PROJ","channelId":"C123","channelName":"general","eventTypes":["issue.created"]}""",
                    ),
            )
                .andExpect(status().isCreated)
                .andReturn()
                .response
                .contentAsString

        assertThat(body).doesNotContain("team_id")
        assertThat(body).doesNotContain("teamId")
    }

    // ── GET /api/v1/slack/channel-mappings ─────────────────────────────────────

    @Test
    fun `GET channel-mappings returns 200 with mapping list`() {
        every { service.list(userId, "PROJ") } returns listOf(sampleMapping())

        mockMvc.perform(get("/api/v1/slack/channel-mappings?projectKey=PROJ").with(jwtAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(mappingId.toString()))
            .andExpect(jsonPath("$[0].projectKey").value("PROJ"))
    }

    @Test
    fun `GET channel-mappings returns 401 without authentication`() {
        mockMvc.perform(get("/api/v1/slack/channel-mappings?projectKey=PROJ"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET channel-mappings returns 403 when actor lacks manage permission`() {
        every { service.list(userId, "PROJ") } throws SlackChannelMappingPermissionDeniedException()

        mockMvc.perform(get("/api/v1/slack/channel-mappings?projectKey=PROJ").with(jwtAuth(userId)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("SLACK_CHANNEL_MAPPING_FORBIDDEN"))
    }

    // ── PATCH /api/v1/slack/channel-mappings/{id} ──────────────────────────────

    @Test
    fun `PATCH channel-mappings returns 200 with updated mapping`() {
        every {
            service.update(userId, mappingId, null, null, setOf(SlackChannelEventType.ISSUE_COMMENTED))
        } returns sampleMapping().copy(eventTypes = setOf(SlackChannelEventType.ISSUE_COMMENTED))

        mockMvc.perform(
            patch("/api/v1/slack/channel-mappings/$mappingId")
                .with(jwtAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"eventTypes":["issue.commented"]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eventTypes[0]").value("issue.commented"))
    }

    @Test
    fun `PATCH channel-mappings returns 404 when mapping not found`() {
        every {
            service.update(userId, mappingId, null, null, null)
        } throws SlackChannelMappingNotFoundException()

        mockMvc.perform(
            patch("/api/v1/slack/channel-mappings/$mappingId")
                .with(jwtAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("SLACK_CHANNEL_MAPPING_NOT_FOUND"))
    }

    @Test
    fun `PATCH channel-mappings returns 400 when eventTypes unknown`() {
        every {
            service.update(userId, mappingId, null, null, setOf("bogus.event"))
        } throws IllegalArgumentException("${ChannelProjectMapping.UNKNOWN_EVENT_TYPES_MESSAGE_PREFIX}[bogus.event]")

        mockMvc.perform(
            patch("/api/v1/slack/channel-mappings/$mappingId")
                .with(jwtAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"eventTypes":["bogus.event"]}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("SLACK_CHANNEL_MAPPING_INVALID"))
    }

    @Test
    fun `PATCH channel-mappings returns 403 when actor lacks manage permission`() {
        every {
            service.update(userId, mappingId, null, null, null)
        } throws SlackChannelMappingPermissionDeniedException()

        mockMvc.perform(
            patch("/api/v1/slack/channel-mappings/$mappingId")
                .with(jwtAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("SLACK_CHANNEL_MAPPING_FORBIDDEN"))
    }

    @Test
    fun `PATCH channel-mappings returns 409 when result conflicts`() {
        every {
            service.update(userId, mappingId, "C999", null, null)
        } throws SlackChannelMappingConflictException()

        mockMvc.perform(
            patch("/api/v1/slack/channel-mappings/$mappingId")
                .with(jwtAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"channelId":"C999"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SLACK_CHANNEL_MAPPING_CONFLICT"))
    }

    @Test
    fun `PATCH channel-mappings with PAT-style non-Jwt principal returns 401`() {
        mockMvc.perform(
            patch("/api/v1/slack/channel-mappings/$mappingId")
                .with(patAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isUnauthorized)
    }

    // ── DELETE /api/v1/slack/channel-mappings/{id} ─────────────────────────────

    @Test
    fun `DELETE channel-mappings returns 204`() {
        every { service.delete(userId, mappingId) } just Runs

        mockMvc.perform(delete("/api/v1/slack/channel-mappings/$mappingId").with(jwtAuth(userId)))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE channel-mappings returns 404 when mapping not found`() {
        every { service.delete(userId, mappingId) } throws SlackChannelMappingNotFoundException()

        mockMvc.perform(delete("/api/v1/slack/channel-mappings/$mappingId").with(jwtAuth(userId)))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("SLACK_CHANNEL_MAPPING_NOT_FOUND"))
    }

    @Test
    fun `DELETE channel-mappings returns 403 when actor lacks manage permission`() {
        every { service.delete(userId, mappingId) } throws SlackChannelMappingPermissionDeniedException()

        mockMvc.perform(delete("/api/v1/slack/channel-mappings/$mappingId").with(jwtAuth(userId)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("SLACK_CHANNEL_MAPPING_FORBIDDEN"))
    }

    @Test
    fun `DELETE channel-mappings returns 401 without authentication`() {
        mockMvc.perform(delete("/api/v1/slack/channel-mappings/$mappingId"))
            .andExpect(status().isUnauthorized)
    }

    private companion object {
        /** 결정적 ISO-8601 직렬화 검증용 고정 생성 시각. */
        val FIXED_CREATED_AT: Instant = Instant.parse("2026-07-13T00:00:00Z")
    }
}
