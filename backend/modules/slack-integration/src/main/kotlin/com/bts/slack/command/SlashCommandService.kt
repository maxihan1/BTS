// Slack slash 명령을 파싱→매핑 해석→실행→response_url 전송까지 오케스트레이션하는 @Async 서비스 (FR-SL-04 Task 7)

package com.bts.slack.command

import com.bts.slack.application.SlackUserMappingRepository
import com.bts.slack.config.SlackAsyncConfig
import com.bts.slack.message.SlackBlockKitRenderer
import com.bts.slack.message.SlackResponseUrlClient
import com.fasterxml.jackson.databind.node.ArrayNode
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * `POST /slack/commands`가 받은 `/atlas` slash 명령 1건을 처리해 `response_url`로 지연 응답을 보내는
 * 오케스트레이터 (FR-SL-04 Task 7).
 *
 * [com.bts.slack.web.SlackEventsController](Task 8)가 서명 검증 후 즉시 200 ack를 보내고, 이 서비스의
 * [process]를 `@Async`([SlackAsyncConfig.SLACK_COMMAND_EXECUTOR_BEAN_NAME] 경계 executor)로 위임한다
 * (3초 룰 — [SlackUnfurlService][com.bts.slack.unfurl.SlackUnfurlService]와 동일 근거). 컨트롤러가
 * 빈을 경유해 호출해야 `@Async` 프록시가 적용된다 — 같은 클래스 내부 self-invocation은 금지.
 *
 * ## `@Async` 스레드에는 `SecurityContext`가 없다
 * 이 서비스는 `SecurityContextHolder`를 절대 참조하지 않는다. 서블릿 요청 스레드에 바인딩된
 * `SecurityContext`는 비동기 워커 스레드로 전파되지 않기 때문이다. 명령을 실행하는 Atlas 사용자
 * (`viewerUserId`)는 [SlackUserMappingRepository.findUserIdBySlackUserId]로 DB에서 역매핑해 해석한다
 * — 서명 검증된 [slackUserId]/[teamId]만 신뢰하고, 그로부터 도출된 값만 [handlers]에 전달한다.
 *
 * ## 미매핑 사용자는 친절한 안내 (unfurl의 silent skip과 대비, ADR D6)
 * [com.bts.slack.unfurl.SlackUnfurlService]는 미매핑 공유자를 조용히 skip한다(채널에 노출되는 카드라
 * 실패를 알리면 오히려 정보가 샌다). 반대로 slash 명령은 호출자 본인에게만 보이는 ephemeral 응답이므로,
 * 미매핑 상태를 숨기지 않고 계정 연결 방법을 안내하는 편이 사용자 경험에 유리하다 — [Help]는
 * [SlackBlockKitRenderer.renderHelp]의 `includeAccountLinkNotice=true`로, 그 외 명령은
 * [ACCOUNT_LINK_REQUIRED_MESSAGE] 오류 블록으로 안내한다.
 *
 * ## 사용법 오류는 매핑 여부와 무관하게 그대로 노출
 * [SlashCommand.UsageError]는 계정 연결 여부와 상관없는 순수 입력 형식 문제이므로, 매핑 여부와
 * 무관하게 항상 [SlashCommand.UsageError.reason]을 그대로 오류 블록에 담아 응답한다. 매핑된 사용자는
 * [handlers]에 위임해 처리되고(핸들러가 내부적으로 `renderer.renderError(reason)`을 호출),
 * 미매핑 사용자는 [handlers]를 호출할 `viewerUserId`가 없으므로 이 서비스가 직접
 * `renderer.renderError(reason)`을 호출한다 — 두 경로 모두 최종적으로 동일한 사용법 안내를 반환한다.
 *
 * ## best-effort — 예외를 삼키지 않되 전파하지 않는다
 * 컨트롤러는 이미 즉시 200 ack를 보낸 뒤이므로, [process] 도중 어떤 단계에서 예외가 나도 되돌릴 에러
 * 응답 채널이 없다. 전 과정을 try/catch로 감싸 예외를 로그(WARN)로만 남기고 흡수한다 — 로그에는
 * 예외 클래스명만 남기고, [slackUserId]/[teamId]/[responseUrl] 원문이나 토큰은 남기지 않는다(§1.1.2).
 *
 * @param parser slash 명령 `text`를 [SlashCommand]로 파싱하는 순수 로직.
 * @param userMappingRepository Slack 사용자 → Atlas 사용자 역매핑 조회.
 * @param handlers 매핑된 사용자에 한해 명령을 실행해 응답 블록을 만드는 실행기.
 * @param renderer 미매핑 사용자용 안내/오류 블록을 직접 렌더할 때 쓰는 Block Kit 렌더러.
 * @param responseUrlClient 완성된 블록을 `response_url`로 전송하는 best-effort 클라이언트.
 */
@Component
class SlashCommandService(
    private val parser: SlashCommandParser,
    private val userMappingRepository: SlackUserMappingRepository,
    private val handlers: SlashCommandHandlers,
    private val renderer: SlackBlockKitRenderer,
    private val responseUrlClient: SlackResponseUrlClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * slash 명령 `text`를 파싱·실행해 [responseUrl]로 응답을 전송한다.
     *
     * @param text `/atlas` 뒤에 붙은 나머지 전체 원문.
     * @param slackUserId 명령을 호출한 Slack 사용자 id(서명 검증된 요청에서 추출).
     * @param teamId 호출이 발생한 Slack 워크스페이스 id.
     * @param responseUrl Slack이 발급한 1회용 지연 응답 웹훅 URL.
     */
    @Suppress("TooGenericExceptionCaught") // best-effort — 이미 ack된 이후라 예외를 로그로만 남기고 흡수한다(SlackUnfurlService 동형).
    @Async(SlackAsyncConfig.SLACK_COMMAND_EXECUTOR_BEAN_NAME)
    fun process(
        text: String,
        slackUserId: String,
        teamId: String,
        responseUrl: String,
    ) {
        try {
            val command = parser.parse(text)
            val viewerUserId = userMappingRepository.findUserIdBySlackUserId(slackUserId, teamId)
            val blocks = resolveBlocks(command, viewerUserId)
            responseUrlClient.post(responseUrl, blocks)
        } catch (e: Exception) {
            log.warn("slack_slash_command_process_failed errorType={}", e.javaClass.simpleName)
        }
    }

    /**
     * 매핑된 사용자는 [handlers]에 전부 위임한다(핸들러가 [SlashCommand.UsageError]도 내부적으로
     * `renderError`로 처리). 미매핑 사용자는 [SlashCommand.Help]/[SlashCommand.UsageError]/그 외로
     * 3분기해 이 서비스가 직접 렌더한다(클래스 KDoc 참조).
     */
    private fun resolveBlocks(
        command: SlashCommand,
        viewerUserId: UUID?,
    ): ArrayNode =
        when {
            viewerUserId != null -> handlers.handle(command, viewerUserId)
            command is SlashCommand.Help -> renderer.renderHelp(includeAccountLinkNotice = true)
            command is SlashCommand.UsageError -> renderer.renderError(command.reason)
            else -> renderer.renderError(ACCOUNT_LINK_REQUIRED_MESSAGE)
        }

    companion object {
        /**
         * 미매핑 사용자가 [SlashCommand.View]/[SlashCommand.Search]/[SlashCommand.Create]를 호출했을 때
         * 노출하는 계정 연결 안내 문구. `/atlas help`로 유도해 연결 링크(atlasBaseUrl 조립은
         * [SlackBlockKitRenderer]의 책임)를 확인하게 한다.
         */
        const val ACCOUNT_LINK_REQUIRED_MESSAGE =
            "Atlas 계정이 Slack에 연결되어 있지 않습니다. `/atlas help`로 연결 방법을 확인하세요."
    }
}
