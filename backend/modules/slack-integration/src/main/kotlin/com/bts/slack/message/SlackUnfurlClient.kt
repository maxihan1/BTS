// Slack chat.unfurl 래퍼 — unfurl 카드 발송 + 결과(전송/재시도가능/영구실패) 분류 (FR-SL-03 Task 7)

package com.bts.slack.message

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.slack.api.Slack
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException

/**
 * Slack 공식 SDK(`slack-api-client`)의 [MethodsClient]로 `chat.unfurl`을 호출하는 클라이언트
 * (FR-SL-03 Task 7). [SlackMessageClient]와 동일한 SDK 호출·결과 분류 패턴을 재사용한다.
 *
 * ## unfurl 대상 특정
 * `chat.unfurl`은 DM이 아니라 **이미 게시된 메시지**에 붙은 링크를 카드로 확장한다. 대상은
 * [channel] + [ts](원본 메시지 타임스탬프, `link_shared` 이벤트가 함께 준다)로 특정한다.
 *
 * ## unfurls 맵 직렬화
 * Slack `chat.unfurl`의 `unfurls` 파라미터는 `{ "<url>": { ... 카드 JSON ... } }` 형태의 JSON
 * 객체다. [unfurls]는 URL → 렌더된 카드([ObjectNode])의 맵이며, 이 클라이언트가 [objectMapper]로
 * 직렬화해 SDK의 `rawUnfurls`에 싣는다(호출부는 JSON 문자열을 직접 다루지 않는다).
 *
 * ## 봇 토큰 비노출 (§1.1.2 / [SlackMessageClient]와 동일 관례)
 * [botToken]은 요청에만 싣고 **로그/예외/반환값에 절대 남기지 않는다**. 실패 결과([SlackSendResult])의
 * reason은 오류 종류(Slack 오류 코드, 예외 클래스명)만 담는다.
 *
 * ## 부팅 안전성 ([SlackMessageClient] 동일 패턴)
 * [methods] 기본값 `Slack.getInstance().methods()`는 자격증명·네트워크 없이 생성되므로 부팅이 깨지지
 * 않는다. 테스트는 [methods]에 mock을 주입한다.
 *
 * @param objectMapper unfurls 맵을 `chat.unfurl` raw JSON으로 직렬화.
 * @param methods Slack SDK MethodsClient.
 */
@Component
class SlackUnfurlClient(
    private val objectMapper: ObjectMapper,
    private val methods: MethodsClient = Slack.getInstance().methods(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [channel]의 [ts] 메시지에 붙은 링크들을 [unfurls] 카드로 확장한다.
     *
     * @param botToken 워크스페이스 봇 토큰(요청에만 사용, 미노출).
     * @param channel unfurl 대상 메시지가 게시된 채널 id.
     * @param ts unfurl 대상 메시지의 타임스탬프.
     * @param unfurls URL → 렌더된 카드([ObjectNode])의 맵.
     * @return 전송 결과 분류.
     */
    fun unfurl(
        botToken: String,
        channel: String,
        ts: String,
        unfurls: Map<String, ObjectNode>,
    ): SlackSendResult {
        val rawUnfurls = objectMapper.writeValueAsString(unfurls)
        return try {
            val response =
                methods.chatUnfurl { req ->
                    req.token(botToken)
                        .channel(channel)
                        .ts(ts)
                        .rawUnfurls(rawUnfurls)
                }
            when {
                response.isOk -> SlackSendResult.Sent
                // rate_limited 는 논리 응답으로도 올 수 있어 재시도 대상으로 분류한다.
                response.error == RATE_LIMITED -> SlackSendResult.RetryableFailure(RATE_LIMITED)
                else -> SlackSendResult.PermanentFailure(response.error ?: "unknown")
            }
        } catch (e: IOException) {
            // 네트워크 오류 — 재시도 가능. 토큰/카드 내용 미노출(예외 클래스명만).
            log.warn("slack_chat_unfurl_io_error channel={} error={}", channel, e.javaClass.simpleName)
            SlackSendResult.RetryableFailure("transport:${e.javaClass.simpleName}")
        } catch (e: SlackApiException) {
            // HTTP 비2xx(429/5xx 등) — 재시도 가능. 토큰/카드 내용 미노출.
            log.warn("slack_chat_unfurl_api_error channel={} error={}", channel, e.javaClass.simpleName)
            SlackSendResult.RetryableFailure("http:${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val RATE_LIMITED = "rate_limited"
    }
}
