// Slack 인터랙티브(완료 버튼/완료 모달 제출) 수신 컨트롤러 — form 원문 서명검증 후 동기 처리 결과를 직렬화 (FR-SL-05 PR1 Task 10)

package com.bts.slack.web

import com.bts.slack.interaction.InteractionResult
import com.bts.slack.interaction.SlackInteractionPayloadParser
import com.bts.slack.interaction.SlackInteractionService
import com.bts.slack.security.SlackSignatureVerifier
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Slack 인터랙티브 컴포넌트 수신 엔드포인트 — `POST /slack/interactions` (FR-SL-05 PR1 Task 10).
 *
 * 완료 버튼 클릭(`block_actions`)과 완료 모달 제출(`view_submission`)을 받는 서버-투-서버 인바운드
 * 컨트롤러다. [SlackCommandsController](FR-SL-04)·[SlackEventsController](FR-SL-03) 와 동형으로 서명 검증을
 * 무조건 선행하고, 통과 시 [SlackInteractionPayloadParser] 로 payload 를 파싱해 [SlackInteractionService]
 * 에 **동기** 위임한 뒤, 서비스가 돌려준 [InteractionResult] 를 그대로 HTTP 본문으로 직렬화한다.
 *
 * ## 1) 원문 바디 보존 — 서명 대상이므로 `@RequestParam` 병용 금지 (★핵심 함정)
 * Slack 인터랙티브 요청 바디는 `application/x-www-form-urlencoded`(`payload=<URL-encoded JSON>`)이고,
 * 서명(`X-Slack-Signature`)은 **수신 원문 바이트 그대로**에 대해 계산된다([SlackSignatureVerifier]).
 * 따라서 form 을 파싱하기 전에 원문을 확보해야 한다. `@RequestParam`/`@ModelAttribute` 를 병용하면 Spring 이
 * form 을 먼저 파싱하며 바디 스트림을 소비하고, 그 뒤 `@RequestBody String` 은 **빈 문자열**로 들어와
 * 서명검증이 조용히 무력화된다. 그래서 이 컨트롤러는 `@RequestBody String` 과 `@RequestHeader` **만** 받고,
 * 서명검증을 통과한 **이후에만** [extractPayload] 로 `payload` 필드를 수동 form-decode 한다
 * ([SlackCommandsController] 동형, `@RequestParam` 절대 병용 금지).
 *
 * ## 2) 인증 — 서명 검증으로 대체 (JWT 없음, DEVELOPMENT.md §1.1.4 예외)
 * Slack 서버가 직접 호출하는 엔드포인트라 사용자 JWT 가 없다. [SlackSignatureVerifier] 로 요청 서명을
 * 검증해 인증을 대체한다. 필터 체인의 permitAll 배선(별도 Task)과 **무관하게** 이 컨트롤러는 서명 검증을
 * 무조건 선행하며, 실패(위조·헤더 누락·재전송·secret 미설정 모두 포함)는 빈 401 로만 매핑한다 —
 * 비밀값·원문·예외 message 를 절대 노출하지 않는다(§1.1.2, 로그는 사유만).
 *
 * ## 3) 전부 동기 — `@Async` 없음, 결과가 곧 응답 본문
 * 완료 인터랙션은 모달 `trigger_id` 3초 만료와 `view_submission` 동기 계약([InteractionResult]) 때문에
 * 비동기로 미룰 수 없다([SlackInteractionService] KDoc). 서비스가 openModal/전이/chat.update 를 동기
 * 수행하고 [InteractionResult] 를 돌려주면, 이 컨트롤러가 [toResponse] 로 직렬화한다.
 * - [InteractionResult.AckEmpty] → 빈 200(모달 닫기 / block_actions ack / no-op).
 * - [InteractionResult.ResponseActionErrors] → 200 + `{"response_action":"errors",…}` JSON 본문(모달 유지).
 *
 * ## 4) DoS 가드 — 본문 크기 상한
 * payload 는 message blocks 를 포함해 slash 명령보다 크므로 상한을 넉넉히 [MAX_BODY_BYTES](64KB)로 두되,
 * HMAC 계산 대상 원문이므로 **서명검증 이전에** 상한을 먼저 검사해 거대 페이로드가 HMAC 연산·이후 파싱을
 * 유발하지 못하게 막는다(초과 시 빈 413). 이 상한은 비즈니스 한도가 아니라 방어적 천장이다.
 *
 * @param verifier 서명 검증기(미설정·위조·만료·헤더 누락 모두 `false`, fail-closed).
 * @param parser payload JSON → [com.bts.slack.interaction.SlackInteractionPayload] 방어적 파서.
 * @param service 완료 인터랙션 동기 오케스트레이터(결과가 곧 HTTP 본문).
 */
@RestController
class SlackInteractionsController(
    private val verifier: SlackSignatureVerifier,
    private val parser: SlackInteractionPayloadParser,
    private val service: SlackInteractionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Slack 인터랙티브 요청을 수신한다.
     *
     * @param timestamp `X-Slack-Request-Timestamp` 헤더값(누락 시 null → 검증 실패).
     * @param signature `X-Slack-Signature` 헤더값(누락 시 null → 검증 실패).
     * @param rawBody 서명 대상 원문 form 바디(그대로 보존, 파싱 전 서명 검증에 사용).
     * @return 처리 결과([InteractionResult]) 직렬화(빈 200 또는 200 + errors JSON), 서명 실패는 빈 401,
     *   크기 상한 초과는 빈 413, `payload` 필드 누락은 방어적 빈 200.
     */
    @Suppress("ReturnCount") // 크기·서명 거부·payload 누락별 guard clause (SlackCommandsController 동형)
    @PostMapping(SLACK_INTERACTIONS_PATH)
    fun receive(
        @RequestHeader(name = TIMESTAMP_HEADER, required = false) timestamp: String?,
        @RequestHeader(name = SIGNATURE_HEADER, required = false) signature: String?,
        @RequestBody rawBody: String,
    ): ResponseEntity<Any> {
        if (rawBody.toByteArray(StandardCharsets.UTF_8).size > MAX_BODY_BYTES) {
            log.warn("slack_interaction_body_too_large")
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build()
        }
        if (!verifier.isValid(timestamp, signature, rawBody)) {
            log.info("slack_interaction_signature_rejected")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        val payloadJson = extractPayload(rawBody)
        if (payloadJson == null) {
            log.info("slack_interaction_ignored_missing_payload")
            return ResponseEntity.ok().build()
        }

        return toResponse(service.handle(parser.parse(payloadJson)))
    }

    /** 서비스 결과를 HTTP 응답으로 직렬화한다. AckEmpty=빈 200, ResponseActionErrors=200 + errors JSON. */
    private fun toResponse(result: InteractionResult): ResponseEntity<Any> =
        when (result) {
            InteractionResult.AckEmpty -> ResponseEntity.ok().build()
            is InteractionResult.ResponseActionErrors ->
                ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body<Any>(result.json)
        }

    /**
     * 서명 검증을 통과한 원문 form 바디에서 `payload` 필드값(URL-encoded JSON)을 추출·디코드한다
     * (`@RequestParam` 병용 금지 — 클래스 KDoc §1 참조). 인터랙티브 요청 바디는 `payload=<…>` 단일 필드이며,
     * 필드 부재·값 공백은 null 로 수렴한다(호출자가 방어적 빈 200 처리). 값은 [URLDecoder] 로 UTF-8 디코드한다.
     */
    private fun extractPayload(rawBody: String): String? {
        val encoded =
            rawBody
                .split(PAIR_DELIMITER)
                .firstOrNull { it.startsWith(PAYLOAD_PREFIX) }
                ?.substring(PAYLOAD_PREFIX.length)
        return if (encoded.isNullOrEmpty()) null else URLDecoder.decode(encoded, StandardCharsets.UTF_8)
    }

    private companion object {
        const val SLACK_INTERACTIONS_PATH = "/slack/interactions"
        const val TIMESTAMP_HEADER = "X-Slack-Request-Timestamp"
        const val SIGNATURE_HEADER = "X-Slack-Signature"

        /** form 바디 내 `key=value` 쌍 구분자. */
        const val PAIR_DELIMITER = "&"

        /** 인터랙티브 payload 필드 접두(`payload=`). 이 접두로 시작하는 첫 쌍의 값만 디코드한다. */
        const val PAYLOAD_PREFIX = "payload="

        /** 원문 바디 크기 상한(DoS 방어 천장). payload 는 message blocks 를 포함해 slash 보다 크므로 넉넉히 64KB. */
        const val MAX_BODY_BYTES = 64 * 1024
    }
}
