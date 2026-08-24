// /ws STOMP 핸드셰이크가 prod 조립 필터체인을 실제로 통과하는지 실 HTTP 로 검증 (FR-NT-02)

package com.bts.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * prod 조립 컨텍스트에서 `/ws` STOMP 핸드셰이크가 중앙 [com.atlas.bts.identity.config.SecurityConfig]
 * 필터체인을 **실제로 통과**하는지 검증한다 (FR-NT-02).
 *
 * ## 왜 이 테스트만이 관문인가 — 두 설정이 서로를 확인하지 않았다
 * notification BC 의 `WebSocketConfig` 는 `/ws` 가 **HTTP 계층에서는 열려 있다**고 전제하고 인증을
 * STOMP `CONNECT` frame 의 `Authorization: Bearer` 로 미룬다(`StompAuthChannelInterceptor` 단독 책임).
 * 그런데 중앙 `SecurityConfig` 에는 그 permitAll 이 없어 `anyRequest().authenticated()` 가 먼저 잡았고,
 * 브라우저는 업그레이드에서 401 을 받아 아래 오류만 남겼다 —
 * `WebSocket connection to 'wss://…/ws' failed: HTTP Authentication failed; no valid credentials available`.
 * 두 모듈 어느 테스트도 상대 설정을 읽지 않아 유닛은 전부 초록인데 **프로덕션 실시간 알림만 전 화면에서
 * 죽어 있었다**(저장소 지배 결함 양식 — 두 목록이 서로를 확인하지 않는다).
 *
 * ## ★ 「401 이 아님」에 기대지 않는다 — 양성 판별자는 400 이다
 * `/ws` 에 업그레이드 헤더 없이 평범한 GET 을 보내면, **필터를 통과해 WebSocket 핸들러까지 닿았을 때만**
 * Spring 의 핸드셰이크 처리기가 400 Bad Request 로 거절한다("Can \"Upgrade\" only to \"WebSocket\"").
 * 즉 400 은 「필터체인 통과」의 **양성 증명**이다.
 *
 * 반대로 401 에 기대면 공허해진다 — permitAll 이 죽어 필터가 자른 401 과, permitAll 통과 후 핸들러
 * 부재로 `/error` 에서 나는 401 은 상태코드도 `WWW-Authenticate: Bearer` 헤더도 **같다**
 * ([GitWebhookInboundPermitAllTest] KDoc 의 실측 기록). 그래서 이 테스트는 400 만 본다.
 *
 * ## 인증이 사라진 것이 아니다
 * 열린 것은 핸드셰이크뿐이다. CONNECT frame 의 JWT 검증은 `StompAuthChannelInterceptor` 가 그대로 지며
 * (헤더 부재·형식 오류·decode 실패·subject 부재·PAT 거부, 클라이언트발 SEND 도 차단), 그 검증은
 * `:modules:notification` 의 `StompAuthChannelInterceptorTest` 가 별도로 지킨다. 두 테스트는 함께 읽는다.
 */
class WebSocketHandshakePermitAllTest : ProdAssemblyHttpTestBase() {
    @Test
    fun `ws 핸드셰이크는 필터체인을 통과해 WebSocket 핸들러에 닿는다 (400, 401 아님)`() {
        val response = rest.getForEntity("/ws", String::class.java)

        assertThat(response.statusCode)
            .withFailMessage(
                "GET /ws 가 %s 입니다. 400(업그레이드 헤더 없음)이라야 필터체인을 통과해 WebSocket " +
                    "핸들러까지 닿았다는 뜻입니다. 401 이면 SecurityConfig 의 WS_HANDSHAKE_PATH permitAll 이 " +
                    "빠져 브라우저 업그레이드가 핸드셰이크에서 죽습니다(실시간 알림 전면 중단).",
                response.statusCode,
            )
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }

    /**
     * permitAll 이 **정확히 한 경로만** 연다는 음성 증명.
     *
     * ## 왜 여기서는 401 이 공허하지 않은가
     * 위 테스트가 401 을 쓰지 않는 이유는 「필터가 잘랐다」와 「permitAll 통과 후 핸들러 부재로
     * `/error` 에서 났다」가 상태코드도 `WWW-Authenticate` 헤더도 같아 구분되지 않기 때문이다
     * ([GitWebhookInboundPermitAllTest] KDoc 의 실측 기록). 그 모호함은 **permitAll 대상 경로**
     * 에서만 생긴다 — 통과할 수 있어야 헷갈릴 여지가 있다.
     *
     * 아래 경로들은 permitAll 대상이 **아니므로** 통과 자체가 불가능하고, 따라서 여기서 나는
     * 401 은 필터가 자른 401 하나뿐이다. 즉 이 단언은 공허하지 않다.
     *
     * ## 무엇을 막는가
     * 상수를 하위 와일드카드로 넓히거나 매처를 접두사 매칭으로 바꾸면 이 경로들이 함께 열리고
     * 이 테스트가 red 가 된다. 짝 = identity-access 의 `WebSocketHandshakePathGuardTest`
     * (상수의 폭과 매처 종류를 소스 텍스트로 고정).
     */
    @Test
    fun `인접 경로는 permitAll 대상이 아니라 401 이다 (한 경로만 열렸다는 음성 증명)`() {
        for (path in listOf("/wsx", "/ws/anything", "/ws/info")) {
            val response = rest.getForEntity(path, String::class.java)

            assertThat(response.statusCode)
                .withFailMessage(
                    "GET %s 가 %s 입니다. 401 이라야 permitAll 이 그 경로를 열지 않았다는 뜻입니다. " +
                        "WS_HANDSHAKE_PATH 를 하위 와일드카드로 넓혔거나 매처가 접두사 매칭으로 " +
                        "바뀌면 여기가 뚫립니다 — 훗날 그 아래 매핑이 생겼을 때 조용히 익명 노출됩니다.",
                    path,
                    response.statusCode,
                )
                .isEqualTo(HttpStatus.UNAUTHORIZED)
        }
    }

    /**
     * GET **고정**이 살아 있다는 행동 증명 — 소스 가드가 뚫려도 여기서 잡힌다.
     *
     * ## 왜 POST 가 아니라 HEAD 인가 (공허 회피)
     * 위협 문구는 「POST·DELETE 까지 익명이 된다」지만 POST 로 물으면 **답이 공허하다.**
     * CSRF 가 켜져 있고(CookieCsrfTokenRepository) `/ws` 는 CSRF-ignore 목록에 없어, 토큰 없는
     * POST 는 permitAll 여부와 **무관하게** CsrfFilter 에서 걸린다. 익명 요청의 AccessDeniedException
     * 은 ExceptionTranslationFilter 가 엔트리포인트로 넘겨 401 이 되므로 「인가에서 잘렸다」와
     * 상태코드가 같아진다 — 이 파일 첫 테스트가 401 을 쓰지 않는 것과 같은 이유다.
     *
     * HEAD 는 CSRF 기본 안전 메서드 집합(GET·HEAD·TRACE·OPTIONS)에 들어 그 마스킹이 없다. 그리고
     * `AntPathRequestMatcher` 는 메서드를 **정확히** 비교하므로 GET 고정이 살아 있는 한 HEAD 는
     * permitAll 에 매칭되지 않는다. 즉 여기서의 401 은 「메서드가 고정돼 있다」의 양성 판별자다.
     *
     * ## 짝
     * identity-access 의 `WebSocketHandshakePathGuardTest` 가 소스 텍스트로 같은 것을 고정한다.
     * 그 가드는 한때 주석으로 만족돼 공허했다(재리뷰 BLOCKER-N1) — 소스 가드 하나에만 기대면
     * 그런 실패 모드가 조용히 남는다. 이 테스트는 배선을 **행동으로** 재므로 함께 뚫리지 않는다.
     */
    @Test
    fun `GET 이 아닌 메서드는 permitAll 대상이 아니라 401 이다 (메서드 고정의 행동 증명)`() {
        val response = rest.exchange("/ws", HttpMethod.HEAD, null, String::class.java)

        assertThat(response.statusCode)
            .withFailMessage(
                "HEAD /ws 가 %s 입니다. 401 이라야 permitAll 이 GET 으로 고정돼 있다는 뜻입니다. " +
                    "고정이 빠지면 HEAD 가 필터를 통과해 WebSocket 핸들러에 닿고 405 가 납니다 — " +
                    "그때는 POST·DELETE 까지 함께 익명입니다.",
                response.statusCode,
            )
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
