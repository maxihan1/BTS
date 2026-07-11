// SlackConnectionExceptionHandler 신규 매핑 검증 — 이중 Slack 계정 연결 시도 409 (FR-SL-03 CONCERN-1 hot-fix)

package com.bts.slack.web

import com.bts.slack.SlackTestSecurityConfig
import com.bts.slack.application.SlackAccountAlreadyLinkedException
import com.bts.slack.application.SlackUserConnectionService
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * [SlackConnectionExceptionHandler] 의 [SlackAccountAlreadyLinkedException] → 409 매핑 검증
 * (FR-SL-03 코드리뷰 CONCERN-1 hot-fix).
 *
 * 이미 다른 BTS 사용자에게 연결된 Slack 계정을 연결하려 하면(V702 `idx_user_slack_mapping_slack_user`
 * UNIQUE 위반 → [org.springframework.dao.DuplicateKeyException] → [SlackAccountAlreadyLinkedException])
 * catch-all 500 이 아니라 **의도된 거부 409** 로 응답해야 한다.
 *
 * [SlackConnectionControllerTest] 가 이미 검증하는 나머지 5개 예외 매핑(422/409/404/503)은 중복 검증하지
 * 않고, 이번 hot-fix 로 신규 추가된 케이스만 다룬다. [SlackConnectionExceptionHandler] 는
 * `assignableTypes = [SlackConnectionController::class]` 로 스코프돼 있어 실제 컨트롤러 슬라이스를 통해서만
 * 트리거할 수 있다 — 별도 stub 컨트롤러로는 advice 가 적용되지 않는다.
 */
@WebMvcTest(controllers = [SlackConnectionController::class])
@Import(SlackTestSecurityConfig::class, SlackConnectionExceptionHandlerTest.SecurityBeans::class)
class SlackConnectionExceptionHandlerTest {
    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun slackUserConnectionService(): SlackUserConnectionService = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var service: SlackUserConnectionService

    private val userId = UUID.fromString("11111111-1111-4111-8111-111111111111")

    /** [SlackConnectionControllerTest] 와 동형 — [Jwt] 를 직접 조립해 principal 로 주입한다. */
    private fun jwtAuth(uid: UUID): RequestPostProcessor {
        val jwt =
            Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .claim("sub", uid.toString())
                .subject(uid.toString())
                .issuedAt(Instant.parse("2026-07-10T00:00:00Z"))
                .expiresAt(Instant.parse("2026-07-10T01:00:00Z"))
                .build()
        return authentication(
            UsernamePasswordAuthenticationToken(jwt, null, listOf(SimpleGrantedAuthority("ROLE_USER"))),
        )
    }

    @Test
    fun `POST connection returns 409 with SLACK_ACCOUNT_ALREADY_LINKED when the slack account is already linked to another user`() {
        every { service.connect(userId) } throws SlackAccountAlreadyLinkedException()

        mockMvc.perform(post("/api/v1/slack/me/connection").with(jwtAuth(userId)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SLACK_ACCOUNT_ALREADY_LINKED"))
    }

    @Test
    fun `POST connection 409 response body does not expose slack_user_id or email`() {
        every { service.connect(userId) } throws SlackAccountAlreadyLinkedException()

        val body =
            mockMvc.perform(post("/api/v1/slack/me/connection").with(jwtAuth(userId)))
                .andExpect(status().isConflict)
                .andReturn()
                .response
                .contentAsString

        assertThat(body).doesNotContain("slack_user_id")
        assertThat(body).doesNotContain("slackUserId")
        assertThat(body).doesNotContain("@")
    }
}
