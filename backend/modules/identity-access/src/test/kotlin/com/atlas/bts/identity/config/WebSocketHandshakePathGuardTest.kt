// /ws permitAll 이 「한 경로·MVC 비의존 매처」로 좁혀져 있는지 지키는 소스 가드 (FR-NT-02)

package com.atlas.bts.identity.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * `/ws` permitAll 의 **폭**과 **매처 종류**를 지키는 가드.
 *
 * ## 왜 이름에 `IntegrationTest` 가 없는가
 * 이 파일은 Testcontainers 를 띄우는 통합 테스트가 아니라 **소스 텍스트를 읽는 가드**다
 * (`Files.readString`). 저장소 관례상 `…IntegrationTest` 는 실제 컨테이너/HTTP 왕복을 뜻하므로
 * 그 접미사를 달면 다음 사람이 「HTTP 로 permitAll 통과를 검증한다」고 오독한다 — 아래가 바로
 * 그렇게 검증하면 **공허해지는** 이유를 적은 절이다.
 *
 * ## 왜 HTTP 로 검증하지 않는가 (정직한 계약)
 * 이 모듈 컨텍스트에는 `/ws` **핸들러가 없다**(notification BC 소유). permitAll 이 걸리면 요청은
 * 통과한 뒤 핸들러 부재로 `sendError` → `/error` ERROR 디스패치로 가는데, `/error` 는
 * `anyRequest().authenticated()` 라 익명 요청이 **다시 401** 을 받는다. 그 401 은 필터가 자른 401 과
 * 상태코드도 `WWW-Authenticate: Bearer` 헤더도 **같다** — `:modules:app` 의
 * `GitWebhookInboundPermitAllTest` KDoc 에 그 실측이 이미 기록돼 있다. 즉 이 모듈에서 「permitAll 이
 * 실제로 통과시키는가」를 HTTP 로 물으면 **어떤 답도 공허하다.** 그래서 역할을 둘로 나눈다.
 *
 * - **여기**: 폭(한 경로) + 매처 종류(MVC 비의존). 소스 텍스트라 공허할 수 없다.
 * - `:modules:app` `WebSocketHandshakePermitAllTest`: `/ws` 핸들러가 실재하는 조립 컨텍스트에서
 *   실 HTTP 로 통과를 **양성 증명**한다(400 = 필터를 지나 WebSocket 핸들러에 닿았다는 뜻).
 *
 * 두 짝이 함께 있어야 「열렸다」와 「한 줄만 열었다」가 동시에 지켜진다.
 *
 * ## 왜 상수를 직접 읽지 않는가
 * [SecurityConfig] 의 companion object 는 `private` 이다. 테스트 편의로 프로덕션 가시성을 넓히는 것은
 * 보안 설정 파일에서 하지 않는다 — 대신 소스를 읽는다.
 */
class WebSocketHandshakePathGuardTest {
    private val source: String by lazy {
        val candidates =
            listOf(
                Path.of("src/main/kotlin/com/atlas/bts/identity/config/SecurityConfig.kt"),
                Path.of("modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/SecurityConfig.kt"),
            )
        val found =
            candidates.firstOrNull { Files.exists(it) }
                ?: error("SecurityConfig.kt 를 찾지 못했습니다. 시도 경로: $candidates (cwd=${Path.of("").toAbsolutePath()})")
        Files.readString(found)
    }

    @Test
    fun `ws permitAll 경로 상수는 와일드카드 없는 단일 경로다`() {
        val match = Regex("""const val WS_HANDSHAKE_PATH = "([^"]*)"""").find(source)
        assertThat(match)
            .withFailMessage("SecurityConfig 에 WS_HANDSHAKE_PATH 상수가 없습니다.")
            .isNotNull()

        val path = match?.groupValues?.get(1)
        assertThat(path)
            .withFailMessage(
                "WS_HANDSHAKE_PATH 가 `/ws` 가 아닙니다(실제: %s). 하위를 덮는 와일드카드로 넓히면 " +
                    "훗날 그 아래 매핑이 생겼을 때 조용히 익명 노출됩니다.",
                path,
            )
            .isEqualTo("/ws")
    }

    @Test
    fun `ws permitAll 은 MVC 비의존 antMatcher 로 GET 고정 등록된다`() {
        assertThat(source)
            .withFailMessage(
                "`/ws` permitAll 이 antMatcher 로 등록돼 있지 않습니다. 문자열 매처는 Spring MVC 가 있으면 " +
                    "MvcRequestMatcher 로 해석되는데 `/ws` 는 MVC 핸들러가 아니라 WebSocket 핸들러라 " +
                    "매칭이 보장되지 않습니다(SamlSecurityConfig 가 같은 이유로 antMatcher 를 씁니다).\n" +
                    "또한 메서드를 GET 으로 고정하지 않으면 POST·DELETE 까지 익명이 됩니다 — " +
                    "형제 permitAll(PUBLIC_DASHBOARDS_PATH·ICAL_FEED_PATH)이 쓰는 defense-in-depth 입니다.",
            )
            .contains("auth.requestMatchers(antMatcher(HttpMethod.GET, WS_HANDSHAKE_PATH)).permitAll()")
    }
}
