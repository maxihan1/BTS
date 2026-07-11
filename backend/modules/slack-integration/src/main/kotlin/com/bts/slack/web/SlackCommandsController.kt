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
 * [SlackEventsController] (FR-SL-03) 와 동형의 서버-투-서버 인바운드 컨트롤러다. 서명 검증을 무조건
 * 선행하고, 통과 시 즉시 빈 200 ack 를 보낸 뒤 실제 처리는 [SlashCommandService.process] 로 `@Async`
 * 위임한다. 아래 4가지가 이 컨트롤러의 존재 이유다.
 *
 * ## 1) 원문 바디 보존 — 서명 대상이므로 `@RequestParam` 병용 금지 (★핵심 함정)
 * Slack slash 요청 바디는 `application/x-www-form-urlencoded` 이고, 서명(`X-Slack-Signature`)은
 * **수신 원문 바이트 그대로**에 대해 계산된다([SlackSignatureVerifier]). 따라서 form 을 파싱하기 전에
 * 원문을 확보해야 한다. `@RequestParam`/`@ModelAttribute` 를 병용하면 Spring 이 form 을 먼저 파싱하며
 * 바디 입력 스트림을 소비하고, 그 뒤 `@RequestBody String` 은 **빈 문자열**로 들어와 서명검증이 조용히
 * 무력화된다. 그래서 이 컨트롤러는 `@RequestBody String` 과 `@RequestHeader` **만** 받고, 서명검증을
 * 통과한 **이후에만** [decodeForm] 으로 수동 form-decode 한다(`@RequestParam` 절대 병용 금지).
 * `StringHttpMessageConverter` 가 form 미디어 타입도 원문 그대로 문자열로 읽어주므로 별도 스트림
 * fallback 은 필요 없다(테스트로 rawBody 온전 캡처를 증명 — [SlackCommandsControllerTest]).
 *
 * ## 2) 인증 — 서명 검증으로 대체 (JWT 없음, DEVELOPMENT.md §1.1.4 예외)
 * Slack 서버가 직접 호출하는 엔드포인트라 사용자 JWT 가 없다. [SlackSignatureVerifier] 로 요청 서명을
 * 검증해 인증을 대체한다([SlackEventsController] 선례). 필터 체인의 permitAll 배선(별도 Task)과 **무관하게**
 * 이 컨트롤러는 서명 검증을 무조건 선행하며, 실패(위조·헤더 누락·재전송·secret 미설정 모두 포함)는
 * 빈 401 로만 매핑한다 — 비밀값·원문·예외 message 를 절대 노출하지 않는다(§1.1.2, 로그는 사유만).
 *
 * ## 3) 즉시 ack — 3초 룰 (`@Async` 위임)
 * Slack 은 slash 요청에 3초 내 응답을 요구한다. 서명 통과 시 [SlashCommandService.process] 를 빈 경유로
 * 호출(=`@Async` 프록시 적용)하고, 완료를 기다리지 않고 즉시 빈 200 을 반환한다. 실제 결과는 서비스가
 * `response_url` 로 지연 전송한다([SlackEventsController] 의 `link_shared` 위임과 동일 근거).
 *
 * ## 4) DoS 가드 — 본문 크기 상한
 * 원문 바디는 HMAC 계산 대상이므로, 서명검증 이전에 [MAX_BODY_BYTES] 상한을 먼저 검사해 거대 페이로드가
 * HMAC 연산·이후 파싱을 유발하지 못하게 막는다(초과 시 빈 413). Slack slash 페이로드는 작으므로 이 상한은
 * 비즈니스 한도가 아니라 방어적 천장이다.
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
    ): ResponseEntity<Any> {
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

    /**
     * 서명 검증을 통과한 원문 form 바디를 `key=value&…` 쌍으로 수동 파싱한다(`@RequestParam` 병용 금지 —
     * 클래스 KDoc §1 참조). 각 키/값은 [URLDecoder] 로 UTF-8 디코드하며, `+` 는 공백으로, `%3D` 등은
     * 원문자로 복원된다. `=` 없는 조각은 값 빈 문자열로, 빈 조각은 건너뛴다. 중복 키는 마지막 값이 유효하다.
     */
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

        /** slash 명령이 실행할 나머지 원문(`/atlas` 뒤). 미포함 시 빈 문자열로 처리해 `/atlas` 단독을 허용. */
        const val FIELD_TEXT = "text"

        /** 명령을 호출한 Slack 사용자 id. 위임에 필수(누락 시 방어적 무시). */
        const val FIELD_USER_ID = "user_id"

        /** 호출이 발생한 Slack 워크스페이스 id. 위임에 필수(누락 시 방어적 무시). */
        const val FIELD_TEAM_ID = "team_id"

        /** Slack 이 발급한 1회용 지연 응답 웹훅 URL. 위임에 필수(없으면 응답 보낼 곳이 없어 무시). */
        const val FIELD_RESPONSE_URL = "response_url"

        /** form 바디 내 `key=value` 쌍 구분자. */
        const val PAIR_DELIMITER = "&"

        /** 각 쌍의 키/값 구분자. */
        const val KV_DELIMITER = '='

        /** 원문 바디 크기 상한(DoS 방어 천장). Slack slash 페이로드는 이보다 훨씬 작다. */
        const val MAX_BODY_BYTES = 16 * 1024
    }
}
