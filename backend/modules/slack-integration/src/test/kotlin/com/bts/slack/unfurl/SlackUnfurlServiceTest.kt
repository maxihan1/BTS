// SlackUnfurlService 단위 테스트 — link_shared 오케스트레이션 순서·fail-closed skip·다중링크 (FR-SL-03 Task 10)
package com.bts.slack.unfurl

import com.bts.shared.issue.IssueUnfurlPort
import com.bts.shared.issue.IssueUnfurlView
import com.bts.slack.application.SlackUserMappingRepository
import com.bts.slack.message.SlackBlockKitRenderer
import com.bts.slack.message.SlackSendResult
import com.bts.slack.message.SlackUnfurlClient
import com.bts.slack.worker.SlackBotTokenResolver
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [SlackUnfurlService] 단위 테스트 — 모든 협력자를 mockk로 대체한다.
 *
 * 순서 검증([verifyOrder])과 fail-closed skip 분기(EC1/event.user 부재/S2/S3/S8) 각각이
 * `chat.unfurl`(=[SlackUnfurlClient.unfurl])을 호출하지 않음을 확인한다. S9(다중 링크)는 볼 수
 * 있는 링크만 unfurls 맵에 담겨 1회 호출됨을, 링크별 예외는 그 링크만 skip(폴백 카드 없음)됨을 검증한다.
 */
class SlackUnfurlServiceTest {
    private val botTokenResolver = mockk<SlackBotTokenResolver>()
    private val mappingRepository = mockk<SlackUserMappingRepository>()
    private val urlParser = mockk<AtlasIssueUrlParser>()
    private val issueUnfurlPort = mockk<IssueUnfurlPort>()
    private val renderer = mockk<SlackBlockKitRenderer>()
    private val unfurlClient = mockk<SlackUnfurlClient>()

    private val service =
        SlackUnfurlService(
            botTokenResolver = botTokenResolver,
            mappingRepository = mappingRepository,
            urlParser = urlParser,
            issueUnfurlPort = issueUnfurlPort,
            renderer = renderer,
            unfurlClient = unfurlClient,
        )

    private val objectMapper = ObjectMapper()

    private val teamId = "T123"
    private val slackUserId = "U456"
    private val viewerUserId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val channel = "C789"
    private val messageTs = "1234567890.123456"
    private val issueUrl = "https://atlas.example.com/issues/PROJ-1"
    private val issueKey = "PROJ-1"

    private fun command(
        slackUserId: String? = this.slackUserId,
        links: List<String> = listOf(issueUrl),
    ) = LinkSharedCommand(
        teamId = teamId,
        slackUserId = slackUserId,
        channel = channel,
        messageTs = messageTs,
        links = links,
    )

    private fun view(key: String = issueKey) = IssueUnfurlView(key, "제목", "진행 중", "Medium", null)

    private fun card() = objectMapper.createObjectNode().apply { put("text", "card") }

    @BeforeEach
    fun setUp() {
        every { botTokenResolver.resolve(teamId) } returns "xoxb-token"
    }

    @Test
    fun `정상 흐름 — 봇토큰-역매핑-파싱-포트-렌더-unfurl 순서로 1회 호출`() {
        every { urlParser.parseAll(listOf(issueUrl)) } returns listOf(AtlasIssueLink(issueUrl, issueKey))
        every { mappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns viewerUserId
        every { issueUnfurlPort.getVisibleIssueCard(issueKey, viewerUserId) } returns view()
        every { renderer.renderUnfurlCard(view()) } returns card()
        every {
            unfurlClient.unfurl("xoxb-token", channel, messageTs, mapOf(issueUrl to card()))
        } returns SlackSendResult.Sent

        service.handleLinkShared(command())

        verifyOrder {
            botTokenResolver.resolve(teamId)
            mappingRepository.findUserIdBySlackUserId(slackUserId, teamId)
            urlParser.parseAll(listOf(issueUrl))
            issueUnfurlPort.getVisibleIssueCard(issueKey, viewerUserId)
            renderer.renderUnfurlCard(view())
            unfurlClient.unfurl("xoxb-token", channel, messageTs, mapOf(issueUrl to card()))
        }
    }

    @Test
    fun `봇 미설치(EC1) — 전체 skip, 역매핑도 호출 안 함`() {
        every { botTokenResolver.resolve(teamId) } returns null

        service.handleLinkShared(command())

        verify(exactly = 0) { mappingRepository.findUserIdBySlackUserId(any(), any()) }
        verify(exactly = 0) { unfurlClient.unfurl(any(), any(), any(), any()) }
    }

    @Test
    fun `event_user 부재 — skip, 역매핑 호출 안 함(없음을 허용으로 취급하지 않는다)`() {
        service.handleLinkShared(command(slackUserId = null))

        verify(exactly = 0) { mappingRepository.findUserIdBySlackUserId(any(), any()) }
        verify(exactly = 0) { unfurlClient.unfurl(any(), any(), any(), any()) }
    }

    @Test
    fun `event_user 빈 문자열 — skip`() {
        service.handleLinkShared(command(slackUserId = "   "))

        verify(exactly = 0) { mappingRepository.findUserIdBySlackUserId(any(), any()) }
        verify(exactly = 0) { unfurlClient.unfurl(any(), any(), any(), any()) }
    }

    @Test
    fun `미매핑(S2) — skip, 포트·unfurl 미호출`() {
        every { mappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns null

        service.handleLinkShared(command())

        verify(exactly = 0) { urlParser.parseAll(any()) }
        verify(exactly = 0) { issueUnfurlPort.getVisibleIssueCard(any(), any()) }
        verify(exactly = 0) { unfurlClient.unfurl(any(), any(), any(), any()) }
    }

    @Test
    fun `무권한(S3) — 포트가 null이면 unfurl 미호출`() {
        every { urlParser.parseAll(any()) } returns listOf(AtlasIssueLink(issueUrl, issueKey))
        every { mappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns viewerUserId
        every { issueUnfurlPort.getVisibleIssueCard(issueKey, viewerUserId) } returns null

        service.handleLinkShared(command())

        verify(exactly = 0) { renderer.renderUnfurlCard(any()) }
        verify(exactly = 0) { unfurlClient.unfurl(any(), any(), any(), any()) }
    }

    @Test
    fun `없는 키(S8) — 포트가 null이면 unfurl 미호출`() {
        every { urlParser.parseAll(any()) } returns listOf(AtlasIssueLink(issueUrl, "PROJ-999"))
        every { mappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns viewerUserId
        every { issueUnfurlPort.getVisibleIssueCard("PROJ-999", viewerUserId) } returns null

        service.handleLinkShared(command())

        verify(exactly = 0) { unfurlClient.unfurl(any(), any(), any(), any()) }
    }

    @Test
    fun `다중 링크(S9) — 볼 수 있는 것만 unfurls 맵에 담아 1회 호출`() {
        val visibleUrl = "https://atlas.example.com/issues/PROJ-1"
        val hiddenUrl = "https://atlas.example.com/issues/PROJ-2"
        every { urlParser.parseAll(listOf(visibleUrl, hiddenUrl)) } returns
            listOf(AtlasIssueLink(visibleUrl, "PROJ-1"), AtlasIssueLink(hiddenUrl, "PROJ-2"))
        every { mappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns viewerUserId
        every { issueUnfurlPort.getVisibleIssueCard("PROJ-1", viewerUserId) } returns view("PROJ-1")
        every { issueUnfurlPort.getVisibleIssueCard("PROJ-2", viewerUserId) } returns null
        every { renderer.renderUnfurlCard(view("PROJ-1")) } returns card()
        every { unfurlClient.unfurl(any(), any(), any(), any()) } returns SlackSendResult.Sent

        service.handleLinkShared(command(links = listOf(visibleUrl, hiddenUrl)))

        verify(exactly = 1) {
            unfurlClient.unfurl("xoxb-token", channel, messageTs, mapOf(visibleUrl to card()))
        }
        verify(exactly = 0) { renderer.renderUnfurlCard(view("PROJ-2")) }
    }

    @Test
    fun `링크 조회 예외 — 해당 링크만 skip(폴백 카드 렌더 없음, unfurl 미호출)`() {
        every { urlParser.parseAll(any()) } returns listOf(AtlasIssueLink(issueUrl, issueKey))
        every { mappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns viewerUserId
        every { issueUnfurlPort.getVisibleIssueCard(issueKey, viewerUserId) } throws RuntimeException("boom")

        service.handleLinkShared(command())

        verify(exactly = 0) { renderer.renderUnfurlCard(any()) }
        verify(exactly = 0) { unfurlClient.unfurl(any(), any(), any(), any()) }
    }

    @Test
    fun `Atlas 이슈 링크가 하나도 없으면 skip(포트 미호출)`() {
        every { urlParser.parseAll(any()) } returns emptyList()
        every { mappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns viewerUserId

        service.handleLinkShared(command(links = listOf("https://example.com/not-atlas")))

        verify(exactly = 0) { issueUnfurlPort.getVisibleIssueCard(any(), any()) }
        verify(exactly = 0) { unfurlClient.unfurl(any(), any(), any(), any()) }
    }
}
