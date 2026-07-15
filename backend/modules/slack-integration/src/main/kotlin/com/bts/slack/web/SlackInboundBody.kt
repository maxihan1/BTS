// Slack 인바운드 원문 바디를 상한 이내에서만 힙에 적재하는 이중 방어 리더 — 미인증 힙 DoS 차단

package com.bts.slack.web

import jakarta.servlet.http.HttpServletRequest

/**
 * Slack 인바운드 요청의 원문 바디를 **[maxBytes] 이내일 때만** 힙에 적재해 돌려준다.
 *
 * ## 왜 필요한가 — permitAll 인바운드는 서명검증 이전에 본문이 도달한다
 * `/slack/events`·`/slack/commands`·`/slack/interactions` 는 필터 체인 permitAll + 컨트롤러 서명검증으로
 * 인가된다(사용자 JWT 가 없는 서버-투-서버 호출). 즉 **서명이 틀린 요청도 핸들러까지 도달**하므로,
 * `@RequestBody String` 으로 본문을 받으면 signing secret 을 모르는 공격자도 본문 전체를 우리 힙에
 * 적재시킬 수 있다 — [com.bts.slack.security.SlackSignatureVerifier] 의 fail-closed 401 은 적재 **이후**라
 * 방어선이 되지 못한다. 서블릿 컨테이너의 `maxPostSize` 도 파라미터 파싱을 거치지 않는 본문에는 적용되지
 * 않으므로(memory `multipart-default-limit-app-policy-false-green` 의 반면교사 — "서블릿이 대신 막아주지
 * 않는다"), 애플리케이션 계층에서 직접 상한을 강제해야 한다.
 *
 * ## 이중 방어 ([com.bts.automation.adapter.web.AutomationWebhookController] 선례 동형)
 * 1. **`Content-Length` 사전 검사** — 선언값이 상한을 넘으면 본문을 한 바이트도 읽지 않고 즉시 거절(빠른 거절).
 * 2. **[java.io.InputStream.readNBytes] 상한 스트리밍** — `Content-Length` 는 위조·누락(청크 전송)이
 *    가능하므로 헤더만 믿지 않는다. 상한 + 1 바이트까지만 읽어 **실제 수신 바이트**로 초과를 판정한다.
 *    상한을 넘겨도 힙에 올라오는 것은 상한 + 1 바이트뿐이다.
 *
 * ## 반환 계약 — 예외가 아니라 null 거부로 수렴
 * 초과는 예외가 아니라 `null` 로 수렴한다([com.bts.slack.security.SlackSignatureVerifier] 의 "boolean 거부로
 * 수렴" 계약과 동형). 호출자(인바운드 컨트롤러)가 빈 413 으로 매핑하며, 이 엔드포인트들의 응답 계약은
 * Slack 프로토콜 그대로라 BTS `ProblemDetail` 래핑을 쓰지 않는다(수신자가 SPA 가 아니라 Slack 서버다).
 *
 * ## 원문 바이트 그대로 (String 변환 금지)
 * 반환 타입이 [ByteArray] 인 것은 의도적이다. Slack 서명은 **수신 원문 바이트**에 대해 계산되므로,
 * String 으로 변환했다가 다시 인코딩하는 왕복은 유효 UTF-8 이 아닌 바이트를 U+FFFD 로 치환해 서명을
 * 어긋나게 만든다. HMAC 은 이 바이트 배열에 직접 건다
 * ([com.bts.slack.security.SlackSignatureVerifier.isValid] 의 [ByteArray] 경로).
 *
 * @param request 본문을 아직 아무도 소비하지 않은 인바운드 요청(`@RequestBody`/`@RequestParam` 병용 금지 —
 *   form 파싱이 스트림을 먼저 소비하면 빈 바디가 되어 서명검증이 조용히 무력화된다).
 * @param maxBytes 허용 상한(경계 포함 — 정확히 상한이면 통과). 각 컨트롤러가 자신의 페이로드 특성에 맞게 정한다.
 * @return 상한 이내면 수신 원문 바이트, 초과면 `null`(호출자가 빈 413 으로 매핑).
 */
internal fun readBoundedSlackBody(
    request: HttpServletRequest,
    maxBytes: Int,
): ByteArray? {
    if (request.contentLengthLong > maxBytes) {
        return null
    }
    val bytes = request.inputStream.readNBytes(maxBytes + 1)
    return if (bytes.size > maxBytes) null else bytes
}
