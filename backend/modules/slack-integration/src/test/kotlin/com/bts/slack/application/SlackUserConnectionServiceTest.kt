// SlackUserConnectionService의 이메일 조회·설치 조회·토큰 해석·lookup·link 오케스트레이션을 검증하는 단위 테스트

package com.bts.slack.application

import com.bts.shared.user.UserLookupPort
import com.bts.slack.domain.SlackInstall
import com.bts.slack.domain.SlackUserMapping
import com.bts.slack.message.SlackUserLookupClient
import com.bts.slack.message.SlackUserLookupResult
import com.bts.slack.worker.SlackBotTokenResolver
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import java.time.Instant
import java.util.UUID

/**
 * [SlackUserConnectionService] 단위 테스트 (FR-SL-02 D6 Task 3).
 *
 * 오케스트레이션 경계만 검증한다 — 5개 협력자([UserLookupPort]·[SlackInstallRepository]·
 * [SlackBotTokenResolver]·[SlackUserLookupClient]·[SlackUserMappingService])를 모두 mockk 로 대체한다.
 *
 * 검증 축.
 * - 연결 성공 경로 — 외부 HTTP 호출([SlackUserLookupClient.lookupByEmail])이 DB 쓰기([SlackUserMappingService.link])
 *   보다 먼저 일어나는지 순서 검증(tx 밖 원칙).
 * - 각 선행 조건 부재(이메일/설치/봇 토큰) 시 명시적 예외로 조기 반환(다음 협력자 미호출 검증).
 * - lookup 결과 3분류(NotFound/MissingScope/Transient) → 각각 다른 예외로 매핑.
 * - [SlackUserConnectionService.getStatus]/[SlackUserConnectionService.disconnect] 위임 계약.
 * - link 가 [DuplicateKeyException](이미 다른 사용자에게 연결된 Slack 계정, V702 UNIQUE 위반) 을 던지면
 *   [SlackAccountAlreadyLinkedException] 으로 번역되는지(CONCERN-1 hot-fix — 의도된 거부가 500 이 아닌 409 로).
 * - 예외 message 에 이메일·Slack 사용자 id·Slack 원본 에러 문자열이 담기지 않는지(§1.1.2 비밀값 미노출).
 */
class SlackUserConnectionServiceTest {
    private val userLookupPort = mockk<UserLookupPort>()
    private val installRepository = mockk<SlackInstallRepository>()
    private val botTokenResolver = mockk<SlackBotTokenResolver>()
    private val userLookupClient = mockk<SlackUserLookupClient>()
    private val userMappingService = mockk<SlackUserMappingService>()

    private val service =
        SlackUserConnectionService(
            userLookupPort = userLookupPort,
            installRepository = installRepository,
            botTokenResolver = botTokenResolver,
            userLookupClient = userLookupClient,
            userMappingService = userMappingService,
        )

    // ── connect ──────────────────────────────────────────────────────────────

    @Test
    fun `connect - 이메일·설치·토큰·lookup Found면 link 후 연결 상태를 반환하고 lookup이 link보다 먼저 호출된다`() {
        every { userLookupPort.findEmailById(USER_ID) } returns EMAIL
        every { installRepository.findCurrentInstallation() } returns installationView()
        every { botTokenResolver.resolve(TEAM_ID) } returns BOT_TOKEN
        every { userLookupClient.lookupByEmail(BOT_TOKEN, EMAIL) } returns
            SlackUserLookupResult.Found(SLACK_USER_ID, TEAM_ID)
        every { userMappingService.link(USER_ID, SLACK_USER_ID, TEAM_ID) } just Runs
        every { userMappingService.resolveByUserId(USER_ID) } returns
            SlackUserMapping(USER_ID, SLACK_USER_ID, TEAM_ID, LINKED_AT)

        val status = service.connect(USER_ID)

        assertThat(status.connected).isTrue()
        assertThat(status.workspaceName).isEqualTo("Acme Workspace")
        assertThat(status.linkedAt).isEqualTo(LINKED_AT)
        verify { userMappingService.link(USER_ID, SLACK_USER_ID, TEAM_ID) }
        // 외부 HTTP 호출이 DB 쓰기보다 먼저 — tx 밖 순서(교훈 advisory-lock-bigint-toctou 계열).
        verifyOrder {
            userLookupClient.lookupByEmail(BOT_TOKEN, EMAIL)
            userMappingService.link(USER_ID, SLACK_USER_ID, TEAM_ID)
        }
    }

    @Test
    fun `connect - 이메일이 없으면 EmailUnavailableException을 던지고 이후 단계를 진행하지 않는다`() {
        every { userLookupPort.findEmailById(USER_ID) } returns null

        assertThatThrownBy { service.connect(USER_ID) }
            .isInstanceOf(EmailUnavailableException::class.java)

        verify(exactly = 0) { installRepository.findCurrentInstallation() }
        verify(exactly = 0) { userLookupClient.lookupByEmail(any(), any()) }
    }

    @Test
    fun `connect - 워크스페이스 설치가 없으면 WorkspaceNotInstalledException을 던진다`() {
        every { userLookupPort.findEmailById(USER_ID) } returns EMAIL
        every { installRepository.findCurrentInstallation() } returns null

        assertThatThrownBy { service.connect(USER_ID) }
            .isInstanceOf(WorkspaceNotInstalledException::class.java)

        verify(exactly = 0) { botTokenResolver.resolve(any()) }
        verify(exactly = 0) { userLookupClient.lookupByEmail(any(), any()) }
    }

    @Test
    fun `connect - 설치는 있으나 봇 토큰 해석이 null이면(TOCTOU) WorkspaceNotInstalledException을 던진다`() {
        every { userLookupPort.findEmailById(USER_ID) } returns EMAIL
        every { installRepository.findCurrentInstallation() } returns installationView()
        every { botTokenResolver.resolve(TEAM_ID) } returns null

        assertThatThrownBy { service.connect(USER_ID) }
            .isInstanceOf(WorkspaceNotInstalledException::class.java)

        verify(exactly = 0) { userLookupClient.lookupByEmail(any(), any()) }
        verify(exactly = 0) { userMappingService.link(any(), any(), any()) }
    }

    @Test
    fun `connect - lookup이 MissingScope면 SlackScopeMissingException을 던지고 link하지 않는다`() {
        every { userLookupPort.findEmailById(USER_ID) } returns EMAIL
        every { installRepository.findCurrentInstallation() } returns installationView()
        every { botTokenResolver.resolve(TEAM_ID) } returns BOT_TOKEN
        every { userLookupClient.lookupByEmail(BOT_TOKEN, EMAIL) } returns SlackUserLookupResult.MissingScope

        assertThatThrownBy { service.connect(USER_ID) }
            .isInstanceOf(SlackScopeMissingException::class.java)

        verify(exactly = 0) { userMappingService.link(any(), any(), any()) }
    }

    @Test
    fun `connect - lookup이 NotFound면 SlackUserNotFoundException을 던지고 link하지 않는다`() {
        every { userLookupPort.findEmailById(USER_ID) } returns EMAIL
        every { installRepository.findCurrentInstallation() } returns installationView()
        every { botTokenResolver.resolve(TEAM_ID) } returns BOT_TOKEN
        every { userLookupClient.lookupByEmail(BOT_TOKEN, EMAIL) } returns SlackUserLookupResult.NotFound

        assertThatThrownBy { service.connect(USER_ID) }
            .isInstanceOf(SlackUserNotFoundException::class.java)

        verify(exactly = 0) { userMappingService.link(any(), any(), any()) }
    }

    @Test
    fun `connect - lookup이 Transient면 SlackTemporarilyUnavailableException을 던지고 link하지 않는다`() {
        every { userLookupPort.findEmailById(USER_ID) } returns EMAIL
        every { installRepository.findCurrentInstallation() } returns installationView()
        every { botTokenResolver.resolve(TEAM_ID) } returns BOT_TOKEN
        every { userLookupClient.lookupByEmail(BOT_TOKEN, EMAIL) } returns
            SlackUserLookupResult.Transient("http:SlackApiException")

        assertThatThrownBy { service.connect(USER_ID) }
            .isInstanceOf(SlackTemporarilyUnavailableException::class.java)

        verify(exactly = 0) { userMappingService.link(any(), any(), any()) }
    }

    @Test
    fun `connect - link가 DuplicateKeyException을 던지면 SlackAccountAlreadyLinkedException으로 번역한다`() {
        every { userLookupPort.findEmailById(USER_ID) } returns EMAIL
        every { installRepository.findCurrentInstallation() } returns installationView()
        every { botTokenResolver.resolve(TEAM_ID) } returns BOT_TOKEN
        every { userLookupClient.lookupByEmail(BOT_TOKEN, EMAIL) } returns
            SlackUserLookupResult.Found(SLACK_USER_ID, TEAM_ID)
        every { userMappingService.link(USER_ID, SLACK_USER_ID, TEAM_ID) } throws
            DuplicateKeyException("duplicate key value violates unique constraint \"idx_user_slack_mapping\"")

        assertThatThrownBy { service.connect(USER_ID) }
            .isInstanceOf(SlackAccountAlreadyLinkedException::class.java)

        // link 실패 후에는 방금 기록한 값을 재조회하지 않는다(기록 자체가 없었으므로).
        verify(exactly = 0) { userMappingService.resolveByUserId(any()) }
    }

    // ── getStatus ────────────────────────────────────────────────────────────

    @Test
    fun `getStatus - 매핑이 있고 설치도 있으면 연결 상태와 워크스페이스 이름을 반환한다`() {
        every { userMappingService.resolveByUserId(USER_ID) } returns
            SlackUserMapping(USER_ID, SLACK_USER_ID, TEAM_ID, LINKED_AT)
        every { installRepository.findByTeamId(TEAM_ID) } returns slackInstall()

        val status = service.getStatus(USER_ID)

        assertThat(status.connected).isTrue()
        assertThat(status.workspaceName).isEqualTo("Acme Workspace")
        assertThat(status.linkedAt).isEqualTo(LINKED_AT)
    }

    @Test
    fun `getStatus - 매핑은 있으나 설치가 없으면 workspaceName은 null이고 connected는 true를 유지한다`() {
        every { userMappingService.resolveByUserId(USER_ID) } returns
            SlackUserMapping(USER_ID, SLACK_USER_ID, TEAM_ID, LINKED_AT)
        every { installRepository.findByTeamId(TEAM_ID) } returns null

        val status = service.getStatus(USER_ID)

        assertThat(status.connected).isTrue()
        assertThat(status.workspaceName).isNull()
        assertThat(status.linkedAt).isEqualTo(LINKED_AT)
    }

    @Test
    fun `getStatus - 매핑이 없으면 미연결 상태를 반환하고 설치를 조회하지 않는다`() {
        every { userMappingService.resolveByUserId(USER_ID) } returns null

        val status = service.getStatus(USER_ID)

        assertThat(status.connected).isFalse()
        assertThat(status.workspaceName).isNull()
        assertThat(status.linkedAt).isNull()
        verify(exactly = 0) { installRepository.findByTeamId(any()) }
    }

    // ── disconnect ───────────────────────────────────────────────────────────

    @Test
    fun `disconnect - SlackUserMappingService_unlink에 위임한다`() {
        every { userMappingService.unlink(USER_ID) } just Runs

        service.disconnect(USER_ID)

        verify { userMappingService.unlink(USER_ID) }
    }

    // ── 예외 message 비밀값 미노출 ────────────────────────────────────────────────

    @Test
    fun `연결 예외 6종의 message에는 이메일·Slack 사용자 id·Slack 원본 에러 문자열이 담기지 않는다`() {
        val exceptions =
            listOf(
                EmailUnavailableException(),
                WorkspaceNotInstalledException(),
                SlackScopeMissingException(),
                SlackUserNotFoundException(),
                SlackTemporarilyUnavailableException(),
                SlackAccountAlreadyLinkedException(),
            )

        exceptions.forEach { exception ->
            assertThat(exception.message).isNotNull()
            val message = requireNotNull(exception.message)
            assertThat(message).doesNotContain(EMAIL)
            assertThat(message).doesNotContain(SLACK_USER_ID)
            assertThat(message).doesNotContain("http:SlackApiException")
        }
    }

    // ── fixture builders ────────────────────────────────────────────────────

    private fun installationView() =
        SlackInstallationView(
            teamId = TEAM_ID,
            teamName = "Acme Workspace",
            botUserId = "U0BOT",
            installedAt = LINKED_AT,
            updatedAt = LINKED_AT,
            installedBy = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
        )

    private fun slackInstall() =
        SlackInstall(
            teamId = TEAM_ID,
            teamName = "Acme Workspace",
            botUserId = "U0BOT",
            appId = "A123APP",
            botTokenEncrypted = "encrypted-token-ciphertext",
            scopes = "chat:write,users:read.email",
            isEnterpriseInstall = false,
            installedBy = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
        )

    private companion object {
        val USER_ID: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
        const val EMAIL = "user@example.com"
        const val TEAM_ID = "T123WS"
        const val SLACK_USER_ID = "U1"
        const val BOT_TOKEN = "xoxb-plaintext-bot-token-1234567890"
        val LINKED_AT: Instant = Instant.parse("2026-07-10T00:00:00Z")
    }
}
