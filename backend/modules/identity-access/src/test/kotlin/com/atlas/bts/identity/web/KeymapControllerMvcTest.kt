// KeymapController 슬라이스 테스트 — 단축키 조회/replace-all PATCH/검증 400·충돌 409/JWT 전용 인증 (FR-PF-03 Task 5)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.keymap.KeymapAction
import com.atlas.bts.identity.keymap.KeymapBinding
import com.atlas.bts.identity.keymap.KeymapConflictException
import com.atlas.bts.identity.keymap.KeymapValidationException
import com.atlas.bts.identity.keymap.KeymapViolation
import com.atlas.bts.identity.keymap.UserKeymapService
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.SessionService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [KeymapController] WebMvcTest 슬라이스 테스트 (FR-PF-03 Task 5, [PreferencesControllerMvcTest] 미러).
 *
 * ## 검증 시나리오
 * - GET   /me/keymap — 200 병합 응답(action/keyCombo/trigger/customized) / 미인증 401 / 비-JWT(PAT) 401.
 * - PATCH /me/keymap — 유효 replace-all 200 / 검증 실패 400(KEYMAP_VALIDATION_FAILED) /
 *   충돌 409(KEYMAP_CONFLICT + conflicts) / 비-JWT(PAT) 401.
 */
@WebMvcTest(
    controllers = [KeymapController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, KeymapControllerMvcTest.SecurityBeans::class)
class KeymapControllerMvcTest {
    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-07-08T10:00:00Z"), ZoneOffset.UTC)

        @Bean
        fun sidRevokeJwtConverter(clock: Clock): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource {
            return CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))
        }

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun userKeymapService(): UserKeymapService = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var userKeymapService: UserKeymapService

    private val userId = UUID.fromString("33333333-3333-4333-8333-333333333333")

    @BeforeEach
    fun resetMock() {
        clearMocks(userKeymapService)
    }

    private fun jwtFor(uid: UUID) = jwt().jwt { builder -> builder.subject(uid.toString()) }

    /** [KeymapAction.DEFAULT_BINDINGS] 에서 [overrides] 로 지정한 action 만 교체한 5종 완비 목록. */
    private fun bindingsWith(vararg overrides: Pair<KeymapAction, String>): List<KeymapBinding> {
        val overrideMap = overrides.toMap()
        return KeymapAction.entries.map { action ->
            KeymapBinding(action.id, overrideMap[action] ?: action.defaultKeyCombo)
        }
    }

    // ── GET /me/keymap ──────────────────────────────────────────────────────────

    @Test
    fun `GET me keymap returns 401 without authentication`() {
        mockMvc.perform(get("/api/v1/users/me/keymap"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET me keymap returns 401 with PAT-style non-JWT principal`() {
        mockMvc.perform(get("/api/v1/users/me/keymap").with(user("some-authenticated-principal")))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET me keymap returns 200 with merged bindings`() {
        val bindings = bindingsWith(KeymapAction.CREATE_ISSUE to "n")
        every { userKeymapService.getKeymap(userId) } returns bindings

        mockMvc.perform(get("/api/v1/users/me/keymap").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bindings.length()").value(5))
            .andExpect(jsonPath("$.bindings[0].action").value("help"))
            .andExpect(jsonPath("$.bindings[0].keyCombo").value("?"))
            .andExpect(jsonPath("$.bindings[0].trigger").value("single"))
            .andExpect(jsonPath("$.bindings[0].customized").value(false))
            .andExpect(jsonPath("$.bindings[1].action").value("create-issue"))
            .andExpect(jsonPath("$.bindings[1].keyCombo").value("n"))
            .andExpect(jsonPath("$.bindings[1].customized").value(true))
            .andExpect(jsonPath("$.bindings[3].action").value("goto-my-issues"))
            .andExpect(jsonPath("$.bindings[3].trigger").value("leader"))
    }

    // ── PATCH /me/keymap ────────────────────────────────────────────────────────

    @Test
    fun `PATCH me keymap with valid replace-all returns 200 updated`() {
        val bindings = bindingsWith(KeymapAction.SEARCH to "k")
        every { userKeymapService.patchKeymap(userId, bindings) } returns bindings

        val body =
            bindings.joinToString(",", prefix = "{\"bindings\":[", postfix = "]}") {
                """{"action":"${it.action}","keyCombo":"${it.keyCombo}"}"""
            }

        mockMvc.perform(
            patch("/api/v1/users/me/keymap")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bindings[2].action").value("search"))
            .andExpect(jsonPath("$.bindings[2].keyCombo").value("k"))
            .andExpect(jsonPath("$.bindings[2].customized").value(true))
    }

    @Test
    fun `PATCH me keymap with whitelist violation returns 400`() {
        every { userKeymapService.patchKeymap(userId, any()) } throws
            KeymapValidationException("유효하지 않은 단축키 설정입니다.")

        mockMvc.perform(
            patch("/api/v1/users/me/keymap")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"bindings":[{"action":"help","keyCombo":"?"}]}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("KEYMAP_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("유효하지 않은 단축키 설정입니다."))
    }

    @Test
    fun `PATCH me keymap with duplicate conflict returns 409 with conflicts`() {
        every { userKeymapService.patchKeymap(userId, any()) } throws
            KeymapConflictException(
                "겹치는 단축키가 있습니다.",
                listOf(KeymapViolation.Duplicate("c", setOf(KeymapAction.CREATE_ISSUE.id, KeymapAction.SEARCH.id))),
            )

        val bindings = bindingsWith(KeymapAction.SEARCH to "c")
        val body =
            bindings.joinToString(",", prefix = "{\"bindings\":[", postfix = "]}") {
                """{"action":"${it.action}","keyCombo":"${it.keyCombo}"}"""
            }

        mockMvc.perform(
            patch("/api/v1/users/me/keymap")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("KEYMAP_CONFLICT"))
            .andExpect(jsonPath("$.message").value("겹치는 단축키가 있습니다."))
            .andExpect(jsonPath("$.conflicts.length()").value(1))
            .andExpect(jsonPath("$.conflicts[0].type").value("duplicate"))
            .andExpect(jsonPath("$.conflicts[0].keyCombo").value("c"))
            .andExpect(jsonPath("$.conflicts[0].actions[0]").value("create-issue"))
            .andExpect(jsonPath("$.conflicts[0].actions[1]").value("search"))
    }

    @Test
    fun `PATCH me keymap returns 401 with PAT-style non-JWT principal`() {
        mockMvc.perform(
            patch("/api/v1/users/me/keymap")
                .with(user("some-authenticated-principal"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"bindings":[]}"""),
        )
            .andExpect(status().isUnauthorized)
    }
}
