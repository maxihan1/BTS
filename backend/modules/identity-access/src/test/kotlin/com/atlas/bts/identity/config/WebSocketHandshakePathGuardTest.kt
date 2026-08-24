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
        val path = handshakePathIn(source)
        assertThat(path)
            .withFailMessage("SecurityConfig 에 WS_HANDSHAKE_PATH 상수가 없습니다.")
            .isNotNull()

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
        assertThat(getFixedWiringPresentIn(source))
            .withFailMessage(
                "`/ws` permitAll 이 antMatcher 로 등록돼 있지 않습니다. 문자열 매처는 Spring MVC 가 있으면 " +
                    "MvcRequestMatcher 로 해석되는데 `/ws` 는 MVC 핸들러가 아니라 WebSocket 핸들러라 " +
                    "매칭이 보장되지 않습니다(SamlSecurityConfig 가 같은 이유로 antMatcher 를 씁니다).\n" +
                    "또한 메서드를 GET 으로 고정하지 않으면 POST·DELETE 까지 익명이 됩니다 — " +
                    "형제 permitAll(PUBLIC_DASHBOARDS_PATH·ICAL_FEED_PATH)이 쓰는 defense-in-depth 입니다.",
            )
            .isTrue()
    }

    @Test
    fun `배선을 주석으로만 남기고 메서드 고정을 빼면 red 가 된다`() {
        val mutated = source.replace(GET_FIXED_WIRING, "// $GET_FIXED_WIRING\n                $METHODLESS_WIRING")
        assertThat(mutated)
            .withFailMessage("뮤테이션이 적용되지 않았습니다 — 원본에서 GET 고정 배선을 찾지 못했습니다.")
            .isNotEqualTo(source)

        assertThat(getFixedWiringPresentIn(mutated))
            .withFailMessage(
                "GET 고정 배선을 주석으로만 남기고 실제 등록을 antMatcher(WS_HANDSHAKE_PATH) 로 되돌렸는데도 " +
                    "가드가 통과했습니다. 이 가드는 소스 텍스트를 읽으므로 주석을 걷어내지 않으면 " +
                    "`POST /ws`·`DELETE /ws` 가 익명이 돼도 초록입니다.",
            )
            .isFalse()
    }

    @Test
    fun `경로 상수를 주석으로만 남기고 와일드카드로 넓히면 red 가 된다`() {
        val mutated =
            source.replace(
                PATH_CONSTANT_DECLARATION,
                "// $PATH_CONSTANT_DECLARATION\n        const val WS_HANDSHAKE_PATH = \"/ws/**\"",
            )
        assertThat(mutated)
            .withFailMessage("뮤테이션이 적용되지 않았습니다 — 원본에서 경로 상수 선언을 찾지 못했습니다.")
            .isNotEqualTo(source)

        assertThat(handshakePathIn(mutated))
            .withFailMessage(
                "경로 상수를 주석으로만 남기고 실제 값을 `/ws/**` 로 넓혔는데도 가드가 `/ws` 를 읽었습니다. " +
                    "정규식이 주석 속 리터럴을 먼저 맞추면 상수가 넓어져도 조용히 통과합니다.",
            )
            .isNotEqualTo("/ws")
    }

    @Test
    fun `주석 제거가 실제 배선까지 지우지는 않는다`() {
        val stripped = withoutComments(source)

        assertThat(stripped.length)
            .withFailMessage("주석 제거가 아무것도 걷어내지 못했습니다 — withoutComments 가 항등 함수가 됐습니다.")
            .isLessThan(source.length)

        assertThat(stripped)
            .withFailMessage("주석 제거가 과해 실제 배선까지 지웠습니다. 이 가드는 그 상태에서 공허해집니다.")
            .contains(GET_FIXED_WIRING)
            .contains(PATH_CONSTANT_DECLARATION)
    }

    private companion object {
        const val GET_FIXED_WIRING = "auth.requestMatchers(antMatcher(HttpMethod.GET, WS_HANDSHAKE_PATH)).permitAll()"
        const val METHODLESS_WIRING = "auth.requestMatchers(antMatcher(WS_HANDSHAKE_PATH)).permitAll()"
        const val PATH_CONSTANT_DECLARATION = "const val WS_HANDSHAKE_PATH = \"/ws\""

        val PATH_CONSTANT_RE = Regex("""const val WS_HANDSHAKE_PATH = "([^"]*)"""")

        const val BLOCK_OPEN = "/*"
        const val BLOCK_CLOSE = "*/"
        const val LINE_COMMENT = "//"
        const val QUOTE = '"'
        const val ESCAPE = '\\'

        /**
         * Kotlin 주석을 걷어낸다 — 블록 주석과 KDoc, 그리고 줄 주석까지. 후행 줄 주석도 자른다.
         *
         * ## 왜 필요한가
         * 이 가드는 소스 **텍스트**를 읽는다. 주석을 남겨 두면 실제 배선이 무엇이든 주석이 같은
         * 문자열을 품고 있는 한 통과한다 — GET 고정을 주석으로만 남기고 등록에서 빼도 초록이었다.
         * `SecurityConfig.kt` 는 KDoc·🛑 블록이 코드를 그대로 인용하는 스타일이 지배적이라
         * 우연이 아니라 **관례**가 이 가드를 무력화한다.
         *
         * ## 왜 정규식 한 방이 아닌가 — 실측
         * 블록 주석 여닫이를 정규식으로 비탐욕 매칭하면 **Ant 경로 패턴이 블록 주석 시작으로 읽힌다.**
         * 액추에이터 경로 패턴(SecurityConfig.kt:174)의 슬래시-별표가 여는 표기로 잡혀 그 아래 KDoc 의
         * 닫는 표기까지가 통째로 지워졌고, 지키려던 배선(:255)이 함께 사라져 가드가 오히려 공허해졌다.
         * 이 저장소는 문자열 리터럴에 그 표기를 흔하게 쓴다(:264 · :359 · :370 · :434 · :436).
         * 그래서 **문자열 리터럴 안을 건너뛰는** 스캐너여야 한다.
         *
         * 그 과다 제거를 잡아낸 것이 `주석 제거가 실제 배선까지 지우지는 않는다` 테스트다 —
         * 비-공허 짝은 장식이 아니라 실제로 값을 치렀다.
         */
        fun withoutComments(source: String): String {
            var inBlock = false
            return source
                .lineSequence()
                .joinToString("\n") { line ->
                    val (kept, stillInBlock) = stripCommentsFrom(line, inBlock)
                    inBlock = stillInBlock
                    kept
                }
        }

        /**
         * 한 줄에서 주석을 걷어낸다. 반환은 (남은 코드, 줄 끝에서도 블록 주석 안인가).
         *
         * 문자열 리터럴 안에 들어 있는 줄 주석 표기와 블록 주석 여는 표기는 주석이 아니다.
         * 이스케이프된 따옴표도 문자열 안으로 센다. raw string 은 0건이라 그 표기는 다루지 않는다.
         */
        private fun stripCommentsFrom(
            line: String,
            blockOpen: Boolean,
        ): Pair<String, Boolean> {
            val kept = StringBuilder()
            var inBlock = blockOpen
            var lineCommentHit = false
            var i = 0
            while (i < line.length && !lineCommentHit) {
                when {
                    inBlock && line.startsWith(BLOCK_CLOSE, i) -> {
                        inBlock = false
                        i += BLOCK_CLOSE.length
                    }
                    inBlock -> i++
                    line.startsWith(LINE_COMMENT, i) -> lineCommentHit = true
                    line.startsWith(BLOCK_OPEN, i) -> {
                        inBlock = true
                        i += BLOCK_OPEN.length
                    }
                    line[i] == QUOTE -> i = copyStringLiteral(line, i, kept)
                    else -> {
                        kept.append(line[i])
                        i++
                    }
                }
            }
            return kept.toString() to inBlock
        }

        /**
         * 여는 따옴표부터 닫는 따옴표까지를 [kept] 에 그대로 옮기고 다음 위치를 돌려준다.
         * 문자열이 그 줄에서 닫히지 않으면 줄 끝까지 옮긴다.
         */
        private fun copyStringLiteral(
            line: String,
            openAt: Int,
            kept: StringBuilder,
        ): Int {
            kept.append(line[openAt])
            var i = openAt + 1
            var closed = false
            while (i < line.length && !closed) {
                val c = line[i]
                kept.append(c)
                if (c == ESCAPE && i + 1 < line.length) {
                    kept.append(line[i + 1])
                    i += 2
                } else {
                    closed = c == QUOTE
                    i++
                }
            }
            return i
        }

        fun getFixedWiringPresentIn(source: String): Boolean = withoutComments(source).contains(GET_FIXED_WIRING)

        fun handshakePathIn(source: String): String? {
            val match = PATH_CONSTANT_RE.find(withoutComments(source))
            return match?.groupValues?.get(1)
        }
    }
}
