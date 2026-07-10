// Slack link_shared 이벤트를 fail-closed로 조립해 chat.unfurl까지 오케스트레이션하는 서비스 (FR-SL-03 Task 10)
package com.bts.slack.unfurl

import com.bts.shared.issue.IssueUnfurlPort
import com.bts.shared.issue.IssueUnfurlView
import com.bts.slack.application.SlackUserMappingRepository
import com.bts.slack.config.SlackAsyncConfig
import com.bts.slack.message.SlackBlockKitRenderer
import com.bts.slack.message.SlackUnfurlClient
import com.bts.slack.worker.SlackBotTokenResolver
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Slack `link_shared` 이벤트를 받아 열람 가능한 Atlas 이슈만 unfurl 카드로 되돌리는 오케스트레이터
 * (FR-SL-03 Task 10, ADR D1/D3/D4).
 *
 * [com.bts.slack.web.SlackEventsController]가 서명 검증 후 즉시 200 ack 를 보내고, 이 서비스의
 * [handleLinkShared]를 `@Async`([SlackAsyncConfig.SLACK_UNFURL_EXECUTOR_BEAN_NAME] 경계 executor)로
 * 위임한다(3초 룰). 컨트롤러가 빈을 경유해 호출해야 `@Async` 프록시가 적용된다 — 같은 클래스 내부
 * self-invocation 은 금지.
 *
 * ## fail-closed 조립 순서 (각 단계가 "판정 불가/없음"이면 그 즉시 skip, 다음 단계로 진행하지 않는다)
 * 1. 봇토큰 [SlackBotTokenResolver.resolve] — null(워크스페이스 미설치, EC1) 이면 전체 skip.
 * 2. `event.user`([LinkSharedCommand.slackUserId]) 부재/공백 — 공유자를 특정할 수 없으면 권한 판정
 *    자체가 불가능하므로 skip("없음 = 허용"으로 수렴시키지 않는다. 봇 게시/메시지 편집 이벤트가 이 경로).
 * 3. 역매핑 [SlackUserMappingRepository.findUserIdBySlackUserId] — null(미매핑, S2) 이면 skip.
 * 4. [AtlasIssueUrlParser.parseAll] 로 메시지에 붙은 URL 중 Atlas 이슈 URL만 추출.
 * 5. 링크별 [IssueUnfurlPort.getVisibleIssueCard] — null(무권한 S3·존재하지 않는 키 S8) 이면 **그 링크만**
 *    skip 하고 나머지 링크는 계속 처리한다([buildUnfurls]).
 * 6. 볼 수 있는 링크만 [SlackBlockKitRenderer.renderUnfurlCard] 로 렌더해 url→카드 맵(unfurls)에 담는다.
 * 7. 맵이 비어 있으면 `chat.unfurl` 을 아예 호출하지 않는다. 하나 이상이면
 *    [SlackUnfurlClient.unfurl] 을 **1회**만 호출한다(S9 다중 링크 — 링크마다 별도 호출하지 않는다).
 *
 * @param botTokenResolver teamId → 복호화된 봇 토큰(설치 없으면 null).
 * @param mappingRepository Slack 사용자 → Atlas 사용자 역매핑 조회.
 * @param urlParser 메시지 URL 중 Atlas 이슈 URL만 추출.
 * @param issueUnfurlPort cross-BC 결합 fail-closed 조회 포트(가시 이슈만 반환).
 * @param renderer 이슈 스냅샷 → unfurl 카드 Block Kit 렌더.
 * @param unfurlClient `chat.unfurl` 호출 클라이언트.
 */
@Suppress("LongParameterList") // 오케스트레이터 협력자 주입, 분리 불필요(SlackDeliveryWorker 동형)
@Service
class SlackUnfurlService(
    private val botTokenResolver: SlackBotTokenResolver,
    private val mappingRepository: SlackUserMappingRepository,
    private val urlParser: AtlasIssueUrlParser,
    private val issueUnfurlPort: IssueUnfurlPort,
    private val renderer: SlackBlockKitRenderer,
    private val unfurlClient: SlackUnfurlClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * `link_shared` 이벤트 1건을 처리한다. KDoc 클래스 레벨의 fail-closed 순서를 그대로 따른다.
     *
     * 반환값 없음(`Unit`) — `@Async` 메서드는 결과를 호출자에게 돌려주지 않는다(3초 룰로 컨트롤러는
     * 이미 200 ack 를 보낸 뒤이므로 여기서 예외가 나도 호출자에게 전파할 대상이 없다. 예외는 로그로만
     * 남는다).
     */
    @Suppress("ReturnCount") // 단계별 fail-closed skip 을 early return 으로 표현(SlackDeliveryWorker.dispatch 동형)
    @Async(SlackAsyncConfig.SLACK_UNFURL_EXECUTOR_BEAN_NAME)
    fun handleLinkShared(command: LinkSharedCommand) {
        val botToken = botTokenResolver.resolve(command.teamId)
        if (botToken == null) {
            log.info("slack_unfurl_skip_no_install teamId={}", command.teamId)
            return
        }

        val viewerUserId = resolveViewer(command) ?: return

        val atlasLinks = urlParser.parseAll(command.links)
        val unfurls = buildUnfurls(atlasLinks, viewerUserId)
        if (unfurls.isEmpty()) {
            log.info("slack_unfurl_skip_none_visible teamId={} linkCount={}", command.teamId, atlasLinks.size)
            return
        }

        unfurlClient.unfurl(botToken, command.channel, command.messageTs, unfurls)
    }

    /**
     * `event.user`([LinkSharedCommand.slackUserId]) 부재/공백과 역매핑 실패를 모두 `null`(skip)로
     * 수렴시켜 공유자의 Atlas viewer id를 해석한다. "공유자를 특정할 수 없음"과 "매핑이 없음"을 같은
     * 방식(skip)으로 다루되, 원인은 로그로 구분한다.
     */
    private fun resolveViewer(command: LinkSharedCommand): UUID? {
        val slackUserId = command.slackUserId
        if (slackUserId.isNullOrBlank()) {
            log.info("slack_unfurl_skip_no_sharer teamId={}", command.teamId)
            return null
        }

        val viewerUserId = mappingRepository.findUserIdBySlackUserId(slackUserId, command.teamId)
        if (viewerUserId == null) {
            log.info("slack_unfurl_skip_unmapped teamId={}", command.teamId)
        }
        return viewerUserId
    }

    /**
     * 링크별로 열람 가능한 이슈만 조회·렌더해 url→카드 맵으로 모은다.
     *
     * 볼 수 없는 링크([IssueUnfurlPort.getVisibleIssueCard]가 null)나 조회 중 예외가 난 링크는
     * 그 링크만 결과에서 빠진다 — 나머지 링크 처리를 막지 않는다(S9 다중 링크 부분 성공).
     */
    private fun buildUnfurls(
        links: List<AtlasIssueLink>,
        viewerUserId: UUID,
    ): Map<String, ObjectNode> {
        val unfurls = mutableMapOf<String, ObjectNode>()
        for (link in links) {
            val view = fetchVisibleCard(link.issueKey, viewerUserId) ?: continue
            unfurls[link.url] = renderer.renderUnfurlCard(view)
        }
        return unfurls
    }

    /**
     * 단일 링크의 이슈 스냅샷을 조회한다. 예외는 "이 링크만 skip"으로만 수렴시킨다 — 폴백 카드를
     * 렌더하거나 예외를 [handleLinkShared] 밖으로 전파하지 않는다. 예외 message·이슈 내용은 로그에
     * 남기지 않는다(권한 판정 실패가 로그로 새는 사고 방지, 에러 종류만 기록).
     */
    @Suppress("TooGenericExceptionCaught") // 링크별 조회 실패=skip only, 폴백 카드 금지(보안 계약)
    private fun fetchVisibleCard(
        issueKey: String,
        viewerUserId: UUID,
    ): IssueUnfurlView? =
        try {
            issueUnfurlPort.getVisibleIssueCard(issueKey, viewerUserId)
        } catch (e: Exception) {
            log.warn("slack_unfurl_skip_link_error errorType={}", e.javaClass.simpleName)
            null
        }
}

/**
 * `link_shared` 이벤트 처리에 필요한 입력 값 (FR-SL-03 Task 11 컨트롤러가 payload JSON을 파싱해 채운다).
 *
 * @property teamId Slack 워크스페이스 id(`T…`).
 * @property slackUserId 링크를 공유한 Slack 사용자 id(`event.user`). 봇이 게시했거나 메시지가 편집된
 *   경우 Slack이 이 필드를 비워 보낼 수 있다 — `null`/공백이면 공유자를 특정할 수 없으므로 skip한다.
 * @property channel unfurl 대상 메시지가 게시된 채널 id.
 * @property messageTs unfurl 대상 메시지의 타임스탬프(`chat.unfurl`의 `ts`).
 * @property links 메시지에 붙은 원문 URL 목록(Atlas 이슈가 아닌 URL도 섞여 온다 — [AtlasIssueUrlParser]가 걸러낸다).
 */
data class LinkSharedCommand(
    val teamId: String,
    val slackUserId: String?,
    val channel: String,
    val messageTs: String,
    val links: List<String>,
)
