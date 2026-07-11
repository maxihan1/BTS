// Slack slash 명령(/atlas) 수신 컨트롤러 — form-urlencoded 원문 서명검증 후 @Async 위임 (FR-SL-04 Task 8)

package com.bts.slack.web

import com.bts.slack.command.SlashCommandService
import com.bts.slack.security.SlackSignatureVerifier
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Slack slash 명령 수신 엔드포인트 — `POST /slack/commands` (FR-SL-04 Task 8).
 *
 * 서명 검증을 선행하고, 통과 시 즉시 빈 200 ack 를 보낸 뒤 [SlashCommandService.process] 로 위임한다.
 *
 * @param verifier 서명 검증기(미설정·위조·만료·헤더 누락 모두 `false`, fail-closed).
 * @param service slash 명령 오케스트레이터(빈 경유 호출, `@Async` 위임).
 */
@RestController
class SlackCommandsController(
    private val verifier: SlackSignatureVerifier,
    private val service: SlashCommandService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Slack slash 명령 요청을 수신한다.
     *
     * @param timestamp `X-Slack-Request-Timestamp` 헤더값(누락 시 null → 검증 실패).
     * @param signature `X-Slack-Signature` 헤더값(누락 시 null → 검증 실패).
     * @param rawBody 서명 대상 원문 form 바디(그대로 보존, 파싱 전 서명 검증에 사용).
     * @return 정상 위임·필수 필드 누락은 빈 200, 서명 검증 실패는 빈 401, 크기 상한 초과는 빈 413.
     */
    @Suppress("ReturnCount") // 크기·서명 거부·필수필드 누락별 guard clause (SlackEventsController 동형)
    @PostMapping(SLACK_COMMANDS_PATH)
    fun receive(
        @RequestHeader(name = TIMESTAMP_HEADER, required = false) timestamp: String?,
        @RequestHeader(name = SIGNATURE_HEADER, required = false) signature: String?,
        @RequestBody rawBody: String,
    ): ResponseEntity<Void> {
        if (rawBody.toByteArray(StandardCharsets.UTF_8).size > MAX_BODY_BYTES) {
            log.warn("slack_command_body_too_large")
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build()
        }
        if (!verifier.isValid(timestamp, signature, rawBody)) {
            log.info("slack_command_signature_rejected")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        val fields = decodeForm(rawBody)
        val userId = fields[FIELD_USER_ID]
        val teamId = fields[FIELD_TEAM_ID]
        val responseUrl = fields[FIELD_RESPONSE_URL]
        if (userId.isNullOrBlank() || teamId.isNullOrBlank() || responseUrl.isNullOrBlank()) {
            log.info("slack_command_ignored_missing_field")
            return ResponseEntity.ok().build()
        }

        service.process(fields[FIELD_TEXT].orEmpty(), userId, teamId, responseUrl)
        return ResponseEntity.ok().build()
    }

    /** 서명 통과 후 원문 form 바디를 `key=value&…` 쌍으로 URLDecoder(UTF-8) 파싱한다. */
    private fun decodeForm(rawBody: String): Map<String, String> =
        rawBody
            .split(PAIR_DELIMITER)
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val separatorIndex = pair.indexOf(KV_DELIMITER)
                if (separatorIndex < 0) {
                    urlDecode(pair) to ""
                } else {
                    urlDecode(pair.substring(0, separatorIndex)) to urlDecode(pair.substring(separatorIndex + 1))
                }
            }

    private fun urlDecode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private companion object {
        const val SLACK_COMMANDS_PATH = "/slack/commands"
        const val TIMESTAMP_HEADER = "X-Slack-Request-Timestamp"
        const val SIGNATURE_HEADER = "X-Slack-Signature"
        const val FIELD_TEXT = "text"
        const val FIELD_USER_ID = "user_id"
        const val FIELD_TEAM_ID = "team_id"
        const val FIELD_RESPONSE_URL = "response_url"
        const val PAIR_DELIMITER = "&"
        const val KV_DELIMITER = '='
        const val MAX_BODY_BYTES = 16 * 1024
    }
}
