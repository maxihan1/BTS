// Slack Events API 수신 컨트롤러 — 서명 검증 후 url_verification 응답/link_shared 위임 (FR-SL-03 Task 11)

package com.bts.slack.web

import com.bts.slack.security.SlackSignatureVerifier
import com.bts.slack.unfurl.LinkSharedCommand
import com.bts.slack.unfurl.SlackUnfurlService
import com.bts.slack.web.dto.SlackEventEnvelope
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * Slack Events API 수신 엔드포인트 — `POST /slack/events` (FR-SL-03 Task 11, ADR D6).
 *
 * ## 인증 — 서명 검증으로 대체 (JWT 없음)
 * Slack 서버가 직접 호출하는 서버-투-서버 엔드포인트라 사용자 JWT 가 없다. [SlackSignatureVerifier] 로
 * 요청 서명을 검증해 인증을 대체한다(DEVELOPMENT.md §1.1.4 "무인증 엔드포인트 금지"의 예외 — 서명
 * 기반). test-boot 필터 체인의 permitAll 배선은 Task 12 소관이며, 이 컨트롤러는 배선과 무관하게
 * 서명 검증을 **무조건 선행**한다.
 *
 * ## 원문 바디 보존 (EC8)
 * Slack 서명은 수신 원문 바이트 그대로에 대해 계산된다. `@RequestBody String` 으로 받아 Spring 이
 * JSON 트리/객체로 재직렬화하며 공백·키 순서가 달라지는 것을 막는다 — 파싱은 서명 검증을 통과한
 * **이후에만** 수행한다.
 *
 * ## 응답 계약 — Slack 프로토콜 그대로 (BTS `data`/`error` 래핑 없음)
 * 이 엔드포인트는 BTS SPA 가 아니라 Slack 서버가 파싱하므로 Slack Events API 규격을 그대로 따른다.
 * - `url_verification` → `{"challenge": <값>}` (Slack 이 요구하는 정확한 shape).
 * - `event_callback`(link_shared 포함) 처리 완료 · 그 외 타입/이벤트 무시 → 빈 200(3초 룰, Slack 은
 *   본문을 파싱하지 않는다).
 * - 서명 검증 실패(위조·헤더 누락·재전송·secret 미설정 모두 포함) → 빈 401 — 비밀값·원문·예외
 *   message 는 절대 노출하지 않는다(NFR2).
 *
 * ## `link_shared` 위임 — Spring 빈 경유 (self-invocation 아님)
 * [SlackUnfurlService.handleLinkShared] 는 `@Async` 메서드다. 이 컨트롤러는 그 빈을 주입받아
 * 호출하므로 Spring 프록시를 정상적으로 거친다. 컨트롤러는 이 호출이 끝나길 기다리지 않고(비동기)
 * 즉시 200 을 반환해 3초 룰을 지킨다.
 *
 * @param verifier 서명 검증기(미설정·위조·만료·헤더 누락 모두 `false`, fail-closed).
 * @param unfurlService `link_shared` 이벤트 오케스트레이터(빈 경유 호출, `@Async` 위임).
 * @param objectMapper 서명 검증 통과 후 원문을 방어적으로 파싱하는 Jackson 매퍼(Spring 자동 구성 빈).
 */
@RestController
class SlackEventsController(
    private val verifier: SlackSignatureVerifier,
    private val unfurlService: SlackUnfurlService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Slack Events API 요청을 수신한다.
     *
     * @param timestamp `X-Slack-Request-Timestamp` 헤더값(누락 시 null → 검증 실패).
     * @param signature `X-Slack-Signature` 헤더값(누락 시 null → 검증 실패).
     * @param rawBody 서명 대상 원문 바디(그대로 보존, 파싱 전 서명 검증에 사용).
     * @return `url_verification` 은 `{"challenge": …}` 200, 그 외 정상 처리·무시는 빈 200,
     *   서명 검증 실패는 빈 401.
     */
    @PostMapping(SLACK_EVENTS_PATH)
    fun receive(
        @RequestHeader(name = TIMESTAMP_HEADER, required = false) timestamp: String?,
        @RequestHeader(name = SIGNATURE_HEADER, required = false) signature: String?,
        @RequestBody rawBody: String,
    ): ResponseEntity<Any> {
        if (!verifier.isValid(timestamp, signature, rawBody)) {
            log.info("slack_events_signature_rejected")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        val envelope = parseEnvelope(rawBody) ?: return ResponseEntity.ok().build()

        return when (envelope.type) {
            TYPE_URL_VERIFICATION -> handleUrlVerification(envelope)
            TYPE_EVENT_CALLBACK -> handleEventCallback(envelope)
            else -> ResponseEntity.ok().build()
        }
    }

    /** `url_verification` — challenge 를 그대로 되돌린다. challenge 부재는 방어적으로 빈 200 처리. */
    private fun handleUrlVerification(envelope: SlackEventEnvelope): ResponseEntity<Any> {
        val challenge = envelope.challenge ?: return ResponseEntity.ok().build()
        return ResponseEntity.ok(mapOf(CHALLENGE_FIELD to challenge))
    }

    /**
     * `event_callback` — `link_shared` 이벤트만 [SlackUnfurlService.handleLinkShared] 로 위임하고
     * 즉시 빈 200 을 반환한다(3초 룰, 위임은 `@Async` 라 블록하지 않는다). 그 외 이벤트 타입이거나
     * unfurl 대상을 특정할 teamId·channel·messageTs 가 없으면(방어적 파싱) 무시한다.
     */
    private fun handleEventCallback(envelope: SlackEventEnvelope): ResponseEntity<Any> {
        val event = envelope.event
        val teamId = envelope.teamId
        if (event?.type != TYPE_LINK_SHARED || teamId.isNullOrBlank()) {
            return ResponseEntity.ok().build()
        }
        val channel = event.channel
        val messageTs = event.messageTs
        if (channel.isNullOrBlank() || messageTs.isNullOrBlank()) {
            log.info("slack_events_link_shared_ignored_missing_target teamId={}", teamId)
            return ResponseEntity.ok().build()
        }

        unfurlService.handleLinkShared(
            LinkSharedCommand(
                teamId = teamId,
                slackUserId = event.user,
                channel = channel,
                messageTs = messageTs,
                links = event.links.orEmpty().mapNotNull { it.url },
            ),
        )
        return ResponseEntity.ok().build()
    }

    /**
     * 서명 검증 통과 후 원문을 방어적으로 파싱한다.
     *
     * @return 유효한 JSON 봉투. 파싱 실패(유효한 JSON 이 아님)면 `null`(무시 — 400 대신 빈 200으로
     *   수렴시켜 불필요한 Slack 재전송을 유발하지 않는다. 원문·예외 message 는 로그에 남기지 않는다).
     */
    private fun parseEnvelope(rawBody: String): SlackEventEnvelope? =
        try {
            objectMapper.readValue(rawBody, SlackEventEnvelope::class.java)
        } catch (e: JsonProcessingException) {
            log.warn("slack_events_payload_parse_failed")
            null
        }

    private companion object {
        const val SLACK_EVENTS_PATH = "/slack/events"
        const val TIMESTAMP_HEADER = "X-Slack-Request-Timestamp"
        const val SIGNATURE_HEADER = "X-Slack-Signature"
        const val TYPE_URL_VERIFICATION = "url_verification"
        const val TYPE_EVENT_CALLBACK = "event_callback"
        const val TYPE_LINK_SHARED = "link_shared"
        const val CHALLENGE_FIELD = "challenge"
    }
}
