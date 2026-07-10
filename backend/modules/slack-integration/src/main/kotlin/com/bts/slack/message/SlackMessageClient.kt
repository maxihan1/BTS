// Slack chat.postMessage 래퍼 — DM 발송 + 결과(전송/재시도가능/영구실패) 분류 (FR-SL-02 Task 6)

package com.bts.slack.message

import com.slack.api.Slack
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException

/**
 * `chat.postMessage` 호출 결과 분류.
 *
 * [SlackDeliveryWorker]가 이 결과로 큐 ack 여부를 결정한다.
 * - [Sent]: 전송 성공 → dedup 기록 + 큐 삭제.
 * - [RetryableFailure]: 일시 오류(429/5xx/네트워크/rate_limited) → 큐 삭제 안 함(재전달 재시도).
 * - [PermanentFailure]: 논리 오류(channel_not_found/invalid_auth 등) → 큐 삭제(재시도 무의미).
 */
sealed interface SlackSendResult {
    /** 전송 성공(`ok:true`). */
    data object Sent : SlackSendResult

    /** 재시도 가능한 일시 실패. [reason]은 비밀값을 담지 않는 진단 문자열이다. */
    data class RetryableFailure(val reason: String) : SlackSendResult

    /** 재시도 무의미한 영구 실패. [reason]은 Slack 오류 코드 등 비밀값 아닌 문자열이다. */
    data class PermanentFailure(val reason: String) : SlackSendResult
}

/**
 * Slack 공식 SDK(`slack-api-client`)의 [MethodsClient]로 `chat.postMessage`를 호출하는 클라이언트
 * (FR-SL-02 Task 6).
 *
 * ## DM 대상
 * DM은 `channel`에 Slack 사용자 id(`U…`)를 그대로 실어 보낸다(`chat:write` + `im:write` 스코프로 IM 자동 해석).
 *
 * ## 봇 토큰 비노출 (§1.1.2 / FR-SL-01 3중 미노출 관례)
 * [postDirectMessage]의 `botToken`은 요청에만 싣고 **로그/예외/반환값에 절대 남기지 않는다**. 실패 결과
 * ([SlackSendResult])의 reason은 오류 종류(예: Slack 오류 코드, 예외 클래스명)만 담는다.
 *
 * ## 부팅 안전성 (FR-SL-01 DefaultSlackOAuthClient 동일 패턴)
 * [methods] 기본값 `Slack.getInstance().methods()`는 자격증명·네트워크 없이 생성되므로 부팅이 깨지지 않는다.
 * 테스트는 [methods]에 mock을 주입한다.
 *
 * @param methods Slack SDK MethodsClient.
 */
@Component
class SlackMessageClient(
    private val methods: MethodsClient = Slack.getInstance().methods(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [slackUserId]에게 DM으로 [message]를 발송한다.
     *
     * @param botToken 워크스페이스 봇 토큰(요청에만 사용, 미노출).
     * @param slackUserId DM 수신 Slack 사용자 id(`U…`) — chat.postMessage의 channel.
     * @param message 렌더된 폴백 text + Block Kit blocks.
     * @return 전송 결과 분류.
     */
    fun postDirectMessage(
        botToken: String,
        slackUserId: String,
        message: RenderedSlackMessage,
    ): SlackSendResult {
        return try {
            val response =
                methods.chatPostMessage { req ->
                    req.token(botToken)
                        .channel(slackUserId)
                        .text(message.text)
                        .blocksAsString(message.blocks)
                }
            when {
                response.isOk -> SlackSendResult.Sent
                // rate_limited 는 논리 응답으로도 올 수 있어 재시도 대상으로 분류한다.
                response.error == RATE_LIMITED -> SlackSendResult.RetryableFailure(RATE_LIMITED)
                else -> SlackSendResult.PermanentFailure(response.error ?: "unknown")
            }
        } catch (e: IOException) {
            // 네트워크 오류 — 재시도 가능. 토큰/메시지 미노출(예외 클래스명만).
            log.warn("slack_post_message_io_error slackUserId={} error={}", slackUserId, e.javaClass.simpleName)
            SlackSendResult.RetryableFailure("transport:${e.javaClass.simpleName}")
        } catch (e: SlackApiException) {
            // HTTP 비2xx(429/5xx 등) — 재시도 가능. 토큰/메시지 미노출.
            log.warn("slack_post_message_api_error slackUserId={} error={}", slackUserId, e.javaClass.simpleName)
            SlackSendResult.RetryableFailure("http:${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val RATE_LIMITED = "rate_limited"
    }
}
