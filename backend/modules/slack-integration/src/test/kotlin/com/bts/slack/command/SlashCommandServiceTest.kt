// SlashCommandService 단위 테스트 — 매핑 여부·명령 종류별 분기와 예외 안전성 검증 (FR-SL-04 Task 7)

package com.bts.slack.command

import com.bts.slack.application.SlackUserMappingRepository
import com.bts.slack.message.SlackBlockKitRenderer
import com.bts.slack.message.SlackResponseUrlClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [SlashCommandService] 단위 테스트 (FR-SL-04 Task 7).
 *
 * 모든 협력자([SlashCommandParser]/[SlackUserMappingRepository]/[SlashCommandHandlers]/
 * [SlackBlockKitRenderer]/[SlackResponseUrlClient])는 mockk로 대체한다 — `@Async` 프록시 동작 자체는
 * [com.bts.slack.config.SlackAsyncConfigTest]가 별도로 검증하고, 이 테스트는 [SlashCommandService.process]를
 * 직접 호출해 순수 분기 로직만 확인한다(SlackUnfurlServiceTest와 동일 관례).
 */
class SlashCommandServiceTest {
    private val parser = mockk<SlashCommandParser>()
    private val userMappingRepository = mockk<SlackUserMappingRepository>()
    private val handlers = mockk<SlashCommandHandlers>()
    private val renderer = mockk<SlackBlockKitRenderer>()
    private val responseUrlClient = mockk<SlackResponseUrlClient>()

    private val service =
        SlashCommandService(
            parser = parser,
            userMappingRepository = userMappingRepository,
            handlers = handlers,
            renderer = renderer,
            responseUrlClient = responseUrlClient,
        )

    private val objectMapper = ObjectMapper()

    private val text = "search PROJ status=open"
    private val slackUserId = "U123"
    private val teamId = "T123"
    private val responseUrl = "https://hooks.slack.com/commands/T123/1234/abcd"
    private val viewerUserId = UUID.fromString("11111111-1111-4111-8111-111111111111")

    private fun blocks(marker: String): ArrayNode = objectMapper.createArrayNode().apply { add(marker) }

    @Test
    fun `매핑된 사용자 - search 명령을 핸들러에 위임하고 결과 블록을 response_url로 전송한다`() {
        val command = SlashCommand.Search("PROJ", "status=open")
        val resultBlocks = blocks("search-result")
        every { parser.parse(text) } returns command
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns viewerUserId
        every { handlers.handle(command, viewerUserId) } returns resultBlocks
        every { responseUrlClient.post(responseUrl, resultBlocks) } returns true

        service.process(text, slackUserId, teamId, responseUrl)

        verify(exactly = 1) { handlers.handle(command, viewerUserId) }
        verify(exactly = 1) { responseUrlClient.post(responseUrl, resultBlocks) }
    }

    @Test
    fun `미매핑 사용자 - view 명령은 핸들러를 호출하지 않고 계정 연결 안내 오류 블록을 전송한다`() {
        val viewText = "view PROJ-1"
        val command = SlashCommand.View("PROJ-1")
        val errorBlocks = blocks("account-link-needed")
        every { parser.parse(viewText) } returns command
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns null
        every { renderer.renderError(SlashCommandService.ACCOUNT_LINK_REQUIRED_MESSAGE) } returns errorBlocks
        every { responseUrlClient.post(responseUrl, errorBlocks) } returns true

        service.process(viewText, slackUserId, teamId, responseUrl)

        verify(exactly = 0) { handlers.handle(any(), any()) }
        verify(exactly = 1) { renderer.renderError(SlashCommandService.ACCOUNT_LINK_REQUIRED_MESSAGE) }
        verify(exactly = 1) { responseUrlClient.post(responseUrl, errorBlocks) }
    }

    @Test
    fun `미매핑 사용자 - help 명령은 계정 연결 안내가 포함된 도움말 블록을 전송한다`() {
        val helpText = "help"
        val helpBlocks = blocks("help-with-notice")
        every { parser.parse(helpText) } returns SlashCommand.Help
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns null
        every { renderer.renderHelp(includeAccountLinkNotice = true) } returns helpBlocks
        every { responseUrlClient.post(responseUrl, helpBlocks) } returns true

        service.process(helpText, slackUserId, teamId, responseUrl)

        verify(exactly = 0) { handlers.handle(any(), any()) }
        verify(exactly = 1) { renderer.renderHelp(includeAccountLinkNotice = true) }
        verify(exactly = 1) { responseUrlClient.post(responseUrl, helpBlocks) }
    }

    @Test
    fun `매핑된 사용자 - UsageError는 핸들러에 위임한다(핸들러가 renderError로 처리)`() {
        val badText = "search"
        val command = SlashCommand.UsageError("프로젝트 키가 필요합니다. 사용법: /atlas search <프로젝트키> <검색조건>")
        val errorBlocks = blocks("usage-error")
        every { parser.parse(badText) } returns command
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns viewerUserId
        every { handlers.handle(command, viewerUserId) } returns errorBlocks
        every { responseUrlClient.post(responseUrl, errorBlocks) } returns true

        service.process(badText, slackUserId, teamId, responseUrl)

        verify(exactly = 1) { handlers.handle(command, viewerUserId) }
        verify(exactly = 1) { responseUrlClient.post(responseUrl, errorBlocks) }
    }

    @Test
    fun `미매핑 사용자 - UsageError는 매핑 여부와 무관하게 사용법 오류 블록을 그대로 전송한다`() {
        val badText = "search"
        val command = SlashCommand.UsageError("프로젝트 키가 필요합니다. 사용법: /atlas search <프로젝트키> <검색조건>")
        val errorBlocks = blocks("usage-error")
        every { parser.parse(badText) } returns command
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns null
        every { renderer.renderError(command.reason) } returns errorBlocks
        every { responseUrlClient.post(responseUrl, errorBlocks) } returns true

        service.process(badText, slackUserId, teamId, responseUrl)

        verify(exactly = 0) { handlers.handle(any(), any()) }
        verify(exactly = 1) { renderer.renderError(command.reason) }
        verify(exactly = 1) { responseUrlClient.post(responseUrl, errorBlocks) }
    }

    @Test
    fun `핸들러가 예외를 던져도 process는 예외를 전파하지 않는다(이미 ack된 best-effort)`() {
        val command = SlashCommand.View("PROJ-1")
        every { parser.parse("view PROJ-1") } returns command
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns viewerUserId
        every { handlers.handle(command, viewerUserId) } throws RuntimeException("boom")

        assertThatCode { service.process("view PROJ-1", slackUserId, teamId, responseUrl) }
            .doesNotThrowAnyException()

        verify(exactly = 0) { responseUrlClient.post(any(), any()) }
    }

    @Test
    fun `response_url 전송 자체가 예외를 던져도 process는 예외를 전파하지 않는다`() {
        val command = SlashCommand.Help
        val helpBlocks = blocks("help")
        every { parser.parse("help") } returns command
        every { userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId) } returns viewerUserId
        every { handlers.handle(command, viewerUserId) } returns helpBlocks
        every { responseUrlClient.post(responseUrl, helpBlocks) } throws IllegalStateException("boom")

        assertThatCode { service.process("help", slackUserId, teamId, responseUrl) }
            .doesNotThrowAnyException()
    }
}
