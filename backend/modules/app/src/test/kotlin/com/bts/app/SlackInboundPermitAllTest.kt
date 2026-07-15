// slack 인바운드 4경로가 prod 조립 필터체인을 실제로 통과하는지 실 HTTP 로 검증 (FR-AT-07 PR-A §A-5 · T3)

package com.bts.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * prod 조립 컨텍스트에서 slack 인바운드 4경로가 중앙 `SecurityConfig` 필터체인(permitAll + CSRF-ignore)을
 * 실제로 통과하는지 **실 HTTP** 로 검증한다 (FR-AT-07 PR-A, ADR `2026-07-15-slack-inbound-permitall-central`).
 *
 * ## 왜 이 테스트가 필요한가
 * 각 BC 는 **테스트 전용** 필터체인으로 자기 경계를 검증한다(slack 은 `SlackTestSecurityConfig`). 그 설정은
 * `@TestConfiguration` 이라 prod 조립에는 존재하지 않으므로, BC 테스트가 전부 초록불이어도 prod 에서는
 * `anyRequest().authenticated()` 에 걸려 컨트롤러 도달 전에 거부될 수 있다. 이 테스트만이 **중앙**
 * `SecurityConfig` 가 조립 컨텍스트에서 실제로 slack 을 통과시키는지 말해 준다.
 *
 * ## ★ 양성 단언 — "401 이 아님"에 기대지 않는다
 * [com.bts.slack.security.SlackSignatureVerifier] 는 결정론적 HMAC-SHA256(`v0:{timestamp}:{rawBody}`)이고
 * signing secret 은 [ProdAssemblyHttpTestBase] 가 [TEST_SLACK_SIGNING_SECRET] 로 주입한다. 따라서 이 테스트가
 * **유효 서명을 직접 계산**해 보낼 수 있고, 200 응답은 "필터 통과 + 서명 검증 통과"를 한 번에 **양성 증명**한다.
 * 응답 상태만 부정형으로 훑는 검사(예: `WWW-Authenticate` 포렌식)에 의존하지 않는다.
 *
 * ## 검증 주체 이관의 실증
 * permitAll 은 인증을 **없애는** 것이 아니라 검증 주체를 **필터 → 컨트롤러(서명 검증)** 로 옮기는 것이다
 * (ADR D2). 유효 서명 200(S-A1)과 무효 서명 401(S-A2)이 이 명제를 양쪽에서 실증한다.
 *
 * ## 경로별 개별 단언 (spec EC-A5)
 * 4경로를 하나로 뭉뚱그리면 매처 오타를 못 잡으므로 경로마다 테스트를 하나씩 둔다.
 *
 * ## 사전 조건
 * dev postgres 기동 — `docker compose -f infra/docker-compose.dev.yml up -d postgres` (5433).
 * 컨텍스트 캐시 공유를 위해 [ProdAssemblyHttpTestBase] 를 상속만 하고 `@SpringBootTest`·`@ActiveProfiles`·
 * `@DynamicPropertySource` 를 자체 선언하지 않는다(베이스 KDoc "webEnvironment는 컨텍스트 캐시 키의 일부다").
 */
class SlackInboundPermitAllTest : ProdAssemblyHttpTestBase() {
    @Test
    fun `유효 서명 url_verification 이 필터를 통과해 challenge 를 에코한다 (S-A1)`() {
        val body = """{"type":"url_verification","challenge":"$CHALLENGE"}"""

        val response = rest.exchange(EVENTS_PATH, HttpMethod.POST, signed(body, MediaType.APPLICATION_JSON), String::class.java)

        // 200 = 필터 통과(permitAll + CSRF-ignore) + 컨트롤러 서명 검증 통과의 동시 증명.
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        // challenge 에코 = 서명이 실제로 검증됐고 컨트롤러 본문 로직까지 실행됐다는 증거.
        assertThat(response.body).contains(CHALLENGE)
    }

    @Test
    fun `무효 서명 url_verification 은 컨트롤러가 401 로 거부한다 (S-A2)`() {
        val body = """{"type":"url_verification","challenge":"$CHALLENGE"}"""
        val forged = entity(body, MediaType.APPLICATION_JSON, Instant.now().epochSecond.toString(), FORGED_SIGNATURE)

        val response = rest.exchange(EVENTS_PATH, HttpMethod.POST, forged, String::class.java)

        // 필터가 아니라 SlackEventsController 의 서명 검증이 준 401 — 검증 주체가 컨트롤러임의 실증.
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        // 컨트롤러는 빈 401 만 준다(비밀값·원문 미노출). challenge 가 돌아왔다면 서명 검증이 뚫린 것.
        assertThat(response.body.orEmpty()).doesNotContain(CHALLENGE)
    }

    @Test
    fun `유효 서명 slash 명령이 필터를 통과해 컨트롤러에 도달한다 (EC-A5)`() {
        // 필수 필드(user_id·team_id·response_url) 없는 form 바디 → 컨트롤러가 방어적으로 빈 200 (위임 없음).
        val body = "command=%2Fatlas"

        val response =
            rest.exchange(COMMANDS_PATH, HttpMethod.POST, signed(body, MediaType.APPLICATION_FORM_URLENCODED), String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `유효 서명 인터랙션이 필터를 통과해 컨트롤러에 도달한다 (EC-A5)`() {
        // payload 필드 없는 form 바디 → 컨트롤러가 방어적으로 빈 200 (서비스 위임 없음).
        val body = "unknown=1"

        val response =
            rest.exchange(
                INTERACTIONS_PATH,
                HttpMethod.POST,
                signed(body, MediaType.APPLICATION_FORM_URLENCODED),
                String::class.java,
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `slack 설치 콜백 GET 이 익명으로 필터를 통과해 결과 경로로 302 한다 (EC-A5)`() {
        // code·state 부재 → SlackInstallController 가 프론트 결과 경로로 실패 302 (서명 state 로 자체 검증).
        val response = rest.exchange(INSTALL_CALLBACK_PATH, HttpMethod.GET, HttpEntity.EMPTY, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.FOUND)
        // Location 에 컨트롤러가 실은 실패 코드가 있어야 = 필터가 아니라 컨트롤러가 응답했다는 증거.
        assertThat(response.headers.location.toString()).contains("error=missing_params")
    }

    @Test
    fun `slack install 개시는 익명 요청을 필터가 계속 거부한다 (EC-A1 범위 누출 0)`() {
        // /slack/install 은 SLACK_INBOUND_PATHS 에 없다 — authenticated() + 컨트롤러 뒤 admin fail-closed 이중가드 유지.
        val response = rest.exchange(INSTALL_PATH, HttpMethod.GET, HttpEntity.EMPTY, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        // ★ 401 만으로는 부족하다 — 실수로 permitAll 되면 SlackActorExtractor 가 던지는 401 이 SlackInstallExceptionHandler
        // 를 거쳐 ProblemDetail 본문과 함께 돌아오므로 상태만 보는 단언은 vacuous 하게 통과한다
        // ([[archunit-vacuous-rule-silent-pass]]). 본문에 컨트롤러 에러코드가 없어야 = 필터가 거부한 것.
        assertThat(response.body.orEmpty()).doesNotContain(CONTROLLER_UNAUTHENTICATED_ERROR_CODE)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** 지금 시각 기준 유효 서명 헤더(`X-Slack-Request-Timestamp` + `X-Slack-Signature`)를 붙인 요청 엔티티. */
    private fun signed(
        body: String,
        contentType: MediaType,
    ): HttpEntity<String> {
        val timestamp = Instant.now().epochSecond.toString()
        return entity(body, contentType, timestamp, SIGNATURE_PREFIX + hmacSha256Hex("v0:$timestamp:$body"))
    }

    private fun entity(
        body: String,
        contentType: MediaType,
        timestamp: String,
        signature: String,
    ): HttpEntity<String> {
        val headers = HttpHeaders()
        headers.contentType = contentType
        headers.set(TIMESTAMP_HEADER, timestamp)
        headers.set(SIGNATURE_HEADER, signature)
        return HttpEntity(body, headers)
    }

    /**
     * [TEST_SLACK_SIGNING_SECRET] 로 base string 을 HMAC-SHA256 서명해 lowercase hex 로 돌려준다 —
     * `SlackSignatureVerifier.computeSignature` 와 동일한 계산을 검증 대상 코드와 무관하게 재현한다.
     */
    private fun hmacSha256Hex(baseString: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(TEST_SLACK_SIGNING_SECRET.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return HexFormat.of().formatHex(mac.doFinal(baseString.toByteArray(Charsets.UTF_8)))
    }

    private companion object {
        const val EVENTS_PATH = "/slack/events"
        const val COMMANDS_PATH = "/slack/commands"
        const val INTERACTIONS_PATH = "/slack/interactions"
        const val INSTALL_CALLBACK_PATH = "/slack/install/callback"

        /** permitAll 목록에 **없어야** 하는 관리자 설치 개시 경로 (EC-A1 음성 가드). */
        const val INSTALL_PATH = "/slack/install"

        const val TIMESTAMP_HEADER = "X-Slack-Request-Timestamp"
        const val SIGNATURE_HEADER = "X-Slack-Signature"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val SIGNATURE_PREFIX = "v0="

        /** url_verification 왕복 증거값 — 응답 본문에 그대로 에코돼야 한다. */
        const val CHALLENGE = "abc123"

        /** 형식은 맞지만(`v0=`+hex) secret 을 모르는 서명 — 서명 불일치 거부 경로를 탄다. */
        const val FORGED_SIGNATURE = "v0=" + "0".repeat(64)

        /** [com.bts.slack.web.SlackInstallExceptionHandler] 가 미인증에 싣는 에러 코드(= 컨트롤러 도달 증거). */
        const val CONTROLLER_UNAUTHENTICATED_ERROR_CODE = "SLACK_UNAUTHENTICATED"
    }
}
