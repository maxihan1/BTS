// Slack response_url 지연 응답 POST 클라이언트 — 봇 토큰 불요, best-effort 전송 (FR-SL-04 Task 4)

package com.bts.slack.message

import com.bts.shared.http.OutboundHttpClientConfig
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * Slack slash 명령의 지연 응답을 `response_url`에 POST하는 클라이언트 (FR-SL-04 Task 4).
 *
 * ## response_url 인증 모델 — 봇 토큰 불요
 * `response_url`은 Slack이 slash 명령 요청에 실어 보내는 1회용 서명 웹훅 URL이다
 * (`https://hooks.slack.com/commands/...`). URL 자체가 인증 수단이므로 [SlackMessageClient]/
 * [SlackUnfurlClient]와 달리 봇 토큰(`xoxb-...`)이 필요 없다. 유효기간은 발급 후 **30분**, 사용
 * 횟수는 **최대 5회**로 Slack이 제한한다 — 이 클라이언트는 유효성 여부를 판정하지 않고 그대로
 * POST만 시도한다(만료/소진 여부는 Slack 응답의 비-2xx로만 드러난다).
 *
 * ## SDK 대신 RestClient
 * `response_url` POST는 Slack SDK의 API 메서드(`chat.*`)가 아니라 임의 URL에 대한 단순 HTTP POST이므로
 * [SlackOAuthClient][com.bts.slack.oauth.SlackOAuthClient]가 아니라 shared-kernel
 * [OutboundHttpClientConfig]의 [RestClient] 패턴을 재사용한다(automation `WebhookActionClient`와 동일
 * 골격 — 리다이렉트 `Redirect.NEVER`, connect/read 타임아웃 적용).
 *
 * ## 부팅 안전성 ([SlackOAuthClient][com.bts.slack.oauth.SlackOAuthClient]의
 * `Slack.getInstance().methods()` 기본 인자와 동일 패턴)
 * [restClient] 기본값은 [OutboundHttpClientConfig]를 Spring 빈이 아니라 직접 생성해 만든다. 이 모듈의
 * test-boot 컴포넌트 스캔이 `com.bts.slack`로 한정되어 있어 `com.bts.shared.http`의 `@Bean`이 자동
 * 등록되지 않는 환경에서도 부팅이 깨지지 않는다. 테스트는 [restClient]에 직접 생성한 인스턴스를 주입한다.
 *
 * ## SSRF 검증 미적용 (automation `WebhookActionClient`와의 차이)
 * `response_url`은 사용자가 자유 입력하는 값이 아니라, 이 앱이 이미 서명 검증(FR-2)한 Slack 요청이
 * 실어 보낸 고정 도메인(`hooks.slack.com`) URL이다. 임의 URL을 받아 호출하는 automation의
 * `CallWebhook` 액션과 달리 SSRF 표면이 없어 `com.bts.shared.http.OutboundUrlValidator`를 적용하지
 * 않는다([SlackOAuthClient][com.bts.slack.oauth.SlackOAuthClient]가 `slack.com` 고정 호스트라
 * SSRF 검증을 생략한 것과 동일 근거, ADR D2 / 스펙 N5).
 *
 * ## best-effort — 실패해도 예외 전파 없음
 * 컨트롤러는 이미 즉시 200 ack를 보낸 뒤이므로(FR-9), 이 POST가 실패해도 사용자에게 되돌릴 에러 응답
 * 채널이 없다. 실패(비-2xx·네트워크 오류·URL 형식 오류)는 로그만 남기고 [post]는 `false`를 반환한다
 * (재시도하지 않는다 — 사용자가 명령을 다시 입력하면 새 `response_url`을 받는다).
 *
 * ## 비밀값·본문 미노출 (§1.1.2)
 * 로그에는 `response_url`의 호스트만 남긴다(전체 URL·쿼리스트링 미기록). [blocks] 내용(이슈 요약 등
 * 민감 정보를 담을 수 있음)도 로그에 남기지 않는다.
 *
 * @param objectMapper `response_type`·`blocks`·`replace_original` 페이로드 직렬화용 Jackson.
 * @param restClient 아웃바운드 HTTP 전송용 [RestClient]([OutboundHttpClientConfig] — 리다이렉트 미추종).
 */
@Component
class SlackResponseUrlClient(
    private val objectMapper: ObjectMapper,
    private val restClient: RestClient = OutboundHttpClientConfig().outboundHttpRestClient(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [responseUrl]에 ephemeral(호출자에게만 보이는) 응답으로 [blocks]를 POST한다.
     *
     * @param responseUrl Slack이 slash 명령 요청에 실어 보낸 1회용 웹훅 URL.
     * @param blocks 렌더된 Block Kit blocks 배열([SlackBlockKitRenderer] 산출물).
     * @return 2xx 응답을 받았으면 `true`. 전송 실패(비-2xx·네트워크 오류·URL 형식 오류)면 `false`
     *   (best-effort — 예외를 밖으로 전파하지 않는다).
     */
    fun post(
        responseUrl: String,
        blocks: ArrayNode,
    ): Boolean {
        val payload = buildPayload(blocks)
        return try {
            val statusCode = executeRequest(responseUrl, payload)
            handleResponse(responseUrl, statusCode)
        } catch (e: RestClientException) {
            // 네트워크 오류(연결 거부/타임아웃 등) — 재시도하지 않는다. URL 전체·응답 본문 미노출.
            logFailure(responseUrl, "slack_response_url_post_transport_error", e)
            false
        } catch (e: IllegalArgumentException) {
            // responseUrl 자체가 유효한 URI가 아닌 방어적 케이스(정상 흐름에서는 발생하지 않음).
            logFailure(responseUrl, "slack_response_url_post_malformed_url", e)
            false
        }
    }

    /** `{"response_type":"ephemeral","blocks":[...],"replace_original":false}` 페이로드를 만든다. */
    private fun buildPayload(blocks: ArrayNode): ObjectNode =
        objectMapper.createObjectNode().apply {
            put(RESPONSE_TYPE_FIELD, EPHEMERAL_RESPONSE_TYPE)
            set<ArrayNode>(BLOCKS_FIELD, blocks)
            put(REPLACE_ORIGINAL_FIELD, false)
        }

    /** [restClient]로 실제 HTTP POST를 보내고 응답 상태 코드만 추출한다(본문 미소비). */
    private fun executeRequest(
        responseUrl: String,
        payload: ObjectNode,
    ): Int =
        restClient
            .post()
            .uri(responseUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(payload))
            .exchange { _, response -> response.statusCode.value() }

    /** 상태 코드를 로그 + 성공 여부로 매핑한다. */
    private fun handleResponse(
        responseUrl: String,
        statusCode: Int,
    ): Boolean {
        val host = extractHost(responseUrl)
        return if (statusCode in SUCCESS_STATUS_RANGE) {
            log.info("slack_response_url_post_sent url_host={} status={}", host, statusCode)
            true
        } else {
            log.warn("slack_response_url_post_non2xx url_host={} status={}", host, statusCode)
            false
        }
    }

    /** 실패를 일반화된 이벤트명 + 호스트 + 예외 클래스명으로 로그한다(원본 메시지·URL 전체 미노출). */
    private fun logFailure(
        responseUrl: String,
        event: String,
        e: Exception,
    ) {
        log.warn("{} url_host={} error={}", event, extractHost(responseUrl), e.javaClass.simpleName)
    }

    /** URL 에서 호스트만 추출한다. 실패 시 "(unknown)" 반환(automation `WebhookActionClient` 동일 관례). */
    private fun extractHost(url: String): String =
        runCatching { java.net.URI(url).host ?: UNKNOWN_HOST }.getOrElse { UNKNOWN_HOST }

    private companion object {
        const val RESPONSE_TYPE_FIELD = "response_type"
        const val EPHEMERAL_RESPONSE_TYPE = "ephemeral"
        const val BLOCKS_FIELD = "blocks"
        const val REPLACE_ORIGINAL_FIELD = "replace_original"
        const val UNKNOWN_HOST = "(unknown)"
        val SUCCESS_STATUS_RANGE = 200..299
    }
}
