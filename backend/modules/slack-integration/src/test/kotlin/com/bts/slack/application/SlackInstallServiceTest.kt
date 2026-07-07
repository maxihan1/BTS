// SlackInstallService의 관리자 가드·state 검증·토큰 교환·암호화 upsert 오케스트레이션을 검증하는 단위 테스트

package com.bts.slack.application

import com.bts.shared.crypto.SecretEncryptor
import com.bts.shared.permission.SystemPermissionResolver
import com.bts.slack.domain.SlackInstall
import com.bts.slack.oauth.SlackOAuthClient
import com.bts.slack.oauth.SlackOAuthStateSigner
import com.bts.slack.oauth.SlackOAuthTokenResponse
import com.bts.slack.oauth.SlackStateInvalidException
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [SlackInstallService] 단위 테스트 (FR-SL-01 Task 8).
 *
 * 오케스트레이션 경계만 검증한다 — DataSource·Spring 컨텍스트 없이 fake(mockk) repository/oauth client 를
 * 주입하고, state 서명([SlackOAuthStateSigner])과 봇 토큰 암호화([SecretEncryptor])는 **실제 구현**에
 * 고정 테스트 키를 넣어 round-trip 을 그대로 확인한다.
 *
 * 검증 축.
 * - 관리자 가드(fail-closed) — 비관리자는 [SlackForbiddenException], 토큰 흐름 미개시(auth-extraction-before-lookup).
 * - state 에 installedBy 박제 — 발급된 state 를 실제 signer 로 복원해 확인.
 * - 저장 직전 봇 토큰이 **암호문**(평문 미저장, DEVELOPMENT.md §1.1.1).
 * - 실패 경로(state 불일치 / ok:false / enterprise install / access_token 부재)에서 upsert 미수행 + 예외 매핑.
 */
class SlackInstallServiceTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC)
    private val stateSigner = SlackOAuthStateSigner(STATE_KEY, clock)
    private val encryptor = SecretEncryptor(ENCRYPTION_KEY, ENCRYPTION_SALT)
    private val resolver = mockk<SystemPermissionResolver>()
    private val oauthClient = mockk<SlackOAuthClient>()
    private val repository = mockk<SlackInstallRepository>()

    private val service =
        SlackInstallService(
            permissionResolver = resolver,
            stateSigner = stateSigner,
            oauthClient = oauthClient,
            secretEncryptor = encryptor,
            installRepository = repository,
        )

    private val adminId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val nonAdminId = UUID.fromString("99999999-8888-7777-6666-555555555555")

    // ── startInstall (관리자 가드 + authorize URL) ────────────────────────────

    @Test
    fun `startInstall - 관리자면 authorize URL을 반환하고 state에 installedBy를 박제한다`() {
        every { resolver.isSystemAdmin(adminId) } returns true
        val capturedState = slot<String>()
        every { oauthClient.buildAuthorizeUrl(capture(capturedState)) } returns
            "https://slack.com/oauth/v2/authorize?client_id=x&state=signed"

        val url = service.startInstall(adminId)

        assertThat(url).startsWith("https://slack.com/oauth/v2/authorize")
        // 발급된 state 를 실제 signer 로 복원하면 개시자(installedBy)가 그대로 나와야 한다.
        assertThat(stateSigner.verify(capturedState.captured)).isEqualTo(adminId)
    }

    @Test
    fun `startInstall - 비관리자면 SlackForbiddenException을 던지고 토큰 흐름을 시작하지 않는다`() {
        every { resolver.isSystemAdmin(nonAdminId) } returns false

        assertThatThrownBy { service.startInstall(nonAdminId) }
            .isInstanceOf(SlackForbiddenException::class.java)

        // 가드는 리소스 접근 이전에 — authorize URL 생성으로 진행하지 않는다.
        verify(exactly = 0) { oauthClient.buildAuthorizeUrl(any()) }
    }

    // ── completeInstall (state 검증 → 교환 → 암호화 → upsert) ──────────────────

    @Test
    fun `completeInstall - 성공하면 봇 토큰을 암호화해 upsert하고 결과를 반환한다`() {
        val state = stateSigner.issue(adminId)
        every { oauthClient.exchangeCode(VALID_CODE) } returns workspaceResponse(accessToken = PLAINTEXT_TOKEN)
        val captured = slot<SlackInstall>()
        every { repository.upsert(capture(captured)) } just Runs

        val result = service.completeInstall(VALID_CODE, state)

        // 저장 직전 install 의 봇 토큰은 평문이 아니라 암호문이어야 한다(§1.1.1).
        assertThat(captured.captured.botTokenEncrypted).isNotEqualTo(PLAINTEXT_TOKEN)
        assertThat(encryptor.decrypt(captured.captured.botTokenEncrypted)).isEqualTo(PLAINTEXT_TOKEN)
        // installedBy 는 state 에서 복원된 개시자여야 한다.
        assertThat(captured.captured.installedBy).isEqualTo(adminId)
        assertThat(captured.captured.teamId).isEqualTo("T123WS")
        // 결과는 완료 리다이렉트에 필요한 메타만 담고 평문 토큰은 포함하지 않는다.
        assertThat(result.teamId).isEqualTo("T123WS")
        assertThat(result.teamName).isEqualTo("Acme Workspace")
    }

    @Test
    fun `completeInstall - state 불일치면 SlackStateInvalidException을 던지고 교환하지 않는다`() {
        assertThatThrownBy { service.completeInstall(VALID_CODE, "tampered.state") }
            .isInstanceOf(SlackStateInvalidException::class.java)

        verify(exactly = 0) { oauthClient.exchangeCode(any()) }
        verify(exactly = 0) { repository.upsert(any()) }
    }

    @Test
    fun `completeInstall - ok가 false면 SlackOAuthFailedException을 던지고 error 코드를 보존한다`() {
        val state = stateSigner.issue(adminId)
        every { oauthClient.exchangeCode(BAD_CODE) } returns failedResponse(error = "invalid_code")

        assertThatThrownBy { service.completeInstall(BAD_CODE, state) }
            .isInstanceOf(SlackOAuthFailedException::class.java)
            // Slack error 코드는 리다이렉트용으로 보존하되 예외 message 로는 누출하지 않는다(fr-pm-04 회귀).
            .hasMessageNotContaining("invalid_code")
            .extracting("errorCode").isEqualTo("invalid_code")

        verify(exactly = 0) { repository.upsert(any()) }
    }

    @Test
    fun `completeInstall - enterprise install이면 SlackUnsupportedInstallException을 던지고 저장하지 않는다`() {
        val state = stateSigner.issue(adminId)
        every { oauthClient.exchangeCode(ENTERPRISE_CODE) } returns enterpriseResponse(accessToken = PLAINTEXT_TOKEN)

        assertThatThrownBy { service.completeInstall(ENTERPRISE_CODE, state) }
            .isInstanceOf(SlackUnsupportedInstallException::class.java)

        verify(exactly = 0) { repository.upsert(any()) }
    }

    @Test
    fun `completeInstall - access_token이 없으면 SlackOAuthFailedException을 던지고 저장하지 않는다`() {
        val state = stateSigner.issue(adminId)
        every { oauthClient.exchangeCode(NO_TOKEN_CODE) } returns workspaceResponse(accessToken = null)

        assertThatThrownBy { service.completeInstall(NO_TOKEN_CODE, state) }
            .isInstanceOf(SlackOAuthFailedException::class.java)

        verify(exactly = 0) { repository.upsert(any()) }
    }

    // ── fixture builders ──────────────────────────────────────────────────────

    /** 정상 워크스페이스 설치 응답(ok:true, team 존재). accessToken 을 null 로 주면 EC7(토큰 부재) 시나리오. */
    private fun workspaceResponse(accessToken: String?): SlackOAuthTokenResponse =
        SlackOAuthTokenResponse(
            ok = true,
            error = null,
            accessToken = accessToken,
            tokenType = "bot",
            scope = "chat:write,commands",
            botUserId = "U123BOT",
            appId = "A123APP",
            teamId = "T123WS",
            teamName = "Acme Workspace",
            isEnterpriseInstall = false,
        )

    /** Slack 이 정상 응답으로 반환한 실패(ok:false, error 코드 존재). */
    private fun failedResponse(error: String): SlackOAuthTokenResponse =
        SlackOAuthTokenResponse(
            ok = false,
            error = error,
            accessToken = null,
            tokenType = null,
            scope = null,
            botUserId = null,
            appId = null,
            teamId = null,
            teamName = null,
            isEnterpriseInstall = false,
        )

    /** org-wide(enterprise) 설치 응답 — team 이 null 이라 워크스페이스 설치가 아니다(EC5/G1). */
    private fun enterpriseResponse(accessToken: String): SlackOAuthTokenResponse =
        SlackOAuthTokenResponse(
            ok = true,
            error = null,
            accessToken = accessToken,
            tokenType = "bot",
            scope = "chat:write",
            botUserId = "U123BOT",
            appId = "A123APP",
            teamId = null,
            teamName = null,
            isEnterpriseInstall = true,
        )

    private companion object {
        const val STATE_KEY = "slack-oauth-state-hmac-key-for-tests-0123456789"
        const val ENCRYPTION_KEY = "slack-bot-token-encryption-key-for-tests"

        // Encryptors.stronger 는 salt 가 유효 hex 문자열일 것을 런타임에 요구한다.
        const val ENCRYPTION_SALT = "deadbeefcafef00d"

        const val PLAINTEXT_TOKEN = "xoxb-plaintext-bot-token-1234567890"
        const val VALID_CODE = "valid-oauth-code"
        const val BAD_CODE = "bad-oauth-code"
        const val ENTERPRISE_CODE = "enterprise-oauth-code"
        const val NO_TOKEN_CODE = "no-token-oauth-code"
    }
}
