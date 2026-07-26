// 인증(JWT) 요청이 /error ERROR 디스패치를 탈 때 경로 세그먼트의 원문 토큰이 응답 본문에 실리는지 실 HTTP 로 검증 (N2)

package com.bts.app

import com.atlas.bts.identity.credential.LocalCredentialService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * **인증된 요청**이 서블릿 ERROR 디스패치(`/error`)를 탈 때, 요청 URI 의 경로 세그먼트에 있는 **원문 토큰**이
 * Spring Boot 기본 에러 본문의 `path` 필드로 새어 나가는지 prod 조립 컨텍스트에서 **실 HTTP** 로 검증한다.
 *
 * ## 무엇을 다시 묻는가 — 기존 단언의 표본이 익명 한정이었다
 * 중앙 [com.atlas.bts.identity.config.SecurityConfig] 는 `/error` 를 permitAll 에 넣지 말라는 20줄 주석을
 * 달고 있고, 그 근거를 이렇게 적었다 — *"`/error` 가 `anyRequest()` 에 걸려 authenticated 인 덕분에
 * `BasicErrorController` 가 실행되지 못하고 필터가 빈 401 을 준다 — 즉 지금의 안전은 이 한 줄에 얹혀 있다."*
 *
 * 그 실측(FR-AT-07 PR-C T15)의 표본은 **전부 익명 요청**이었다([GitWebhookInboundPermitAllTest] 의
 * T15-6·T15-7·T15-8 은 `Authorization` 헤더를 붙이지 않는다). 인증 요청에서도 같은지는 측정된 적이 없다.
 *
 * `/error` 가 `authenticated()` 라는 사실은 **인증된 요청을 막지 못한다.** ERROR 디스패치는 같은 필터체인을
 * 다시 타고, Spring Security 6 의 `BearerTokenAuthenticationFilter` 는 인증 성공 시 `SecurityContext` 를
 * `RequestAttributeSecurityContextRepository`(STATELESS 기본 저장소)에 저장한다. 그 저장소는 이름 그대로
 * **요청 attribute** 에 담으므로 같은 요청의 ERROR 디스패치에서 그대로 복원된다. 복원되면
 * `authenticated()` 는 **통과**하고 `BasicErrorController` 가 살아난다.
 * `ErrorProperties.includePath` 기본값은 `ALWAYS` 이고 `server.error.*` yml 오버라이드는 레포에 0건이다.
 *
 * ## ★ PAT 로 측정하면 거짓 음성이 난다 (형제 조립 테스트를 그대로 따라 하지 말 것)
 * 같은 베이스를 쓰는 [ProjectCreatePermissionProdBootTest] 등은 인증에 **PAT**(`Bearer pat_…`)를 쓴다.
 * MFA 게이트·세션 시드를 피할 수 있어 합리적인 선택이지만, **이 테스트에서는 쓰면 안 된다** —
 * `PatAuthenticationFilter` 는 `SecurityContextRepository.saveContext` 를 **호출하지 않는다**(레포 전체
 * `saveContext` 호출 0건). 그래서 PAT 요청은 ERROR 디스패치에서 컨텍스트가 복원되지 않아 **우연히** 안전하다.
 * PAT 로 측정하면 "새지 않는다"는 결론이 나오고 그것은 기전을 빗나간 관측이다.
 * 따라서 이 테스트는 반드시 **실 로그인 JWT** 를 쓴다.
 *
 * ## ★ 판별자는 상태코드가 아니라 응답 **본문**이다
 * 401 은 두 출처(필터 401 · `/error` 401)가 같은 상태코드를 쓰고, `WWW-Authenticate: Bearer` 도 양쪽에
 * 붙어 판별자가 못 된다([GitWebhookInboundPermitAllTest] KDoc 실측). 유일한 판별자는 본문의 토큰 문자열이다.
 * 보조 판별자로 `Allow` 헤더를 쓴다 — 405 는 `DefaultHandlerExceptionResolver` 가 `sendError` 직전에
 * `Allow` 를 세팅하고 그 헤더는 ERROR 디스패치 응답에도 살아남으므로, 이 헤더의 존재는
 * "요청이 DispatcherServlet 까지 도달했다" = "필터가 인증을 통과시켰다"의 양성 증거다.
 *
 * ## 두 축을 함께 본다
 * - **축 A (405).** `GET /api/v1/webhooks/git/{token}` — permitAll 이 POST 로 메서드 고정돼 있어 GET 은
 *   `anyRequest().authenticated()` 로 떨어진다. 익명이면 필터 401(T15-7), **인증이면 통과해** 매핑 부재로
 *   405 → `sendError` → `/error`.
 * - **축 B (404).** `GET /ical/feed/{token}.ics` — GET permitAll 이라 컨트롤러까지 가고 미등록 토큰은
 *   `ResponseStatusException(404)` 로 수렴한다. 컨트롤러 `@ExceptionHandler` 밖이라 `sendError` → `/error`.
 *   ADR `2026-07-25-public-dashboard-error-instance-sanitization` 의 **잔여 위험 #2**("실증하지 않았다")가 이 축이다.
 *
 * ## 사전 조건
 * dev postgres 기동 — `docker compose -f infra/docker-compose.dev.yml up -d postgres` (5433).
 * 컨텍스트 캐시 공유를 위해 [ProdAssemblyHttpTestBase] 를 상속만 하고 `@SpringBootTest` 를 자체 선언하지 않는다.
 */
class AuthenticatedErrorPathTokenLeakTest : ProdAssemblyHttpTestBase() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var localCredentialService: LocalCredentialService

    private val userId: UUID = UUID.randomUUID()

    /**
     * 로그인 가능한 사용자 1명을 심는다. `authn_providers` 행은 불요 —
     * `LocalProvider` 는 provider 설정 조회 없이 `local_credentials` 만 본다([LocalAuthFlowIntegrationTest] 동형).
     *
     * ★ `display_name` 을 반드시 넣어야 한다. 컬럼은 nullable 이지만 `UserRowMapper`(UserRepository.kt:548)가
     * non-null 로 매핑해 NULL 이면 `findByUsername` 이 NPE 를 던진다. 형제 조립 테스트들은 `(id, username)` 만
     * 넣는데(PAT 인증이라 `findByUsername` 을 타지 않는다) 그것을 그대로 따라 하면 로그인이 500 으로 죽는다.
     */
    @BeforeEach
    fun seed() {
        jdbc.update("DELETE FROM users WHERE username = ?", USERNAME)
        jdbc.update(
            "INSERT INTO users (id, username, display_name) VALUES (?, ?, ?)",
            userId,
            USERNAME,
            DISPLAY_NAME,
        )
        localCredentialService.store(userId, PASSWORD.toCharArray())
    }

    @AfterEach
    fun cleanup() {
        jdbc.update("DELETE FROM users WHERE username = ?", USERNAME)
    }

    @Test
    fun `인증된 405 요청의 에러 본문에 경로 토큰이 실리지 않는다 (N2-A)`() {
        val jwt = loginJwt()

        val response = get("http://localhost:$port/api/v1/webhooks/git/$GIT_RAW_TOKEN", jwt)

        // 양성 증거 — 요청이 DispatcherServlet 매핑 조회까지 도달했다(= 필터가 인증을 통과시켰다).
        // 이 헤더가 비어 있으면 필터가 잘랐다는 뜻이고, 그러면 아래 토큰 부재 단언은 공허해진다.
        assertThat(response.headers().firstValue(HEADER_ALLOW))
            .withFailMessage(
                "Allow 헤더가 없다 — 요청이 DispatcherServlet 에 도달하지 못했다(상태 %d, 본문 %s). " +
                    "인증이 통과되지 않았다면 이 축은 N2 를 측정하지 못한다.",
                response.statusCode(),
                response.body(),
            ).isPresent

        assertThat(response.body()).doesNotContain(GIT_RAW_TOKEN)
    }

    @Test
    fun `인증된 404 iCal 요청의 에러 본문에 경로 토큰이 실리지 않는다 (N2-B)`() {
        val jwt = loginJwt()

        val response = get("http://localhost:$port/ical/feed/$ICAL_RAW_TOKEN.ics", jwt)

        assertThat(response.body()).doesNotContain(ICAL_RAW_TOKEN)
    }

    /**
     * 익명 대조군 — 기존 단언(익명은 안전)이 이 조립에서도 여전히 참인지 같은 축에서 확인한다.
     * 이 축이 초록이고 위 두 축이 빨강이면 델타가 정확히 **인증 여부** 하나임이 증명된다.
     */
    @Test
    fun `익명 405 요청은 필터가 잘라 경로 토큰이 실리지 않는다 (N2-C 대조군)`() {
        val response = get("http://localhost:$port/api/v1/webhooks/git/$GIT_RAW_TOKEN", jwt = null)

        assertThat(response.statusCode()).isEqualTo(HTTP_UNAUTHORIZED)
        // 필터가 REQUEST 디스패치에서 잘랐다면 매핑 조회 자체가 없어 Allow 가 붙을 수 없다.
        assertThat(response.headers().firstValue(HEADER_ALLOW)).isEmpty
        assertThat(response.body()).doesNotContain(GIT_RAW_TOKEN)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * `POST /api/v1/auth/login` 실 왕복으로 access JWT 를 받는다. login 은 permitAll + CSRF skip 이다.
     * 실 로그인이라 `SidRevokeJwtConverter` 가 요구하는 활성 세션(sid)도 함께 생긴다 — 직접 발급한 JWT 로는
     * 세션 시드 없이 401 이 난다.
     */
    private fun loginJwt(): String {
        val body = """{"provider":"local","username":"$USERNAME","password":"$PASSWORD"}"""
        val response =
            exchange("http://localhost:$port/api/v1/auth/login", mapOf(HEADER_CONTENT_TYPE to CONTENT_TYPE_JSON)) {
                it.POST(HttpRequest.BodyPublishers.ofString(body))
            }
        check(response.statusCode() == HTTP_OK) { "login 실패 ${response.statusCode()}: ${response.body()}" }
        return ACCESS_TOKEN_REGEX.find(response.body())?.groupValues?.get(1)
            ?: error("access_token 파싱 실패: ${response.body()}")
    }

    private fun get(
        url: String,
        jwt: String?,
    ): HttpResponse<String> {
        val headers = if (jwt == null) emptyMap() else mapOf(HEADER_AUTHORIZATION to "$BEARER_PREFIX$jwt")
        return exchange(url, headers) { it.GET() }
    }

    /** 실 HTTP 왕복 1회 — 인증 재시도·리다이렉트 자동추종이 없어야 원 응답 본문을 그대로 관측한다. */
    private fun exchange(
        url: String,
        headers: Map<String, String>,
        configure: (HttpRequest.Builder) -> HttpRequest.Builder,
    ): HttpResponse<String> {
        val builder = configure(HttpRequest.newBuilder(URI.create(url)))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        /** 이 테스트 전용 사용자 — 다른 조립 테스트 시드와 섞이지 않게 고유 username 을 쓴다. */
        const val USERNAME = "n2-error-leak-probe"

        /** `UserRowMapper` 가 non-null 로 읽으므로 시드에서 반드시 채운다(위 seed KDoc). */
        const val DISPLAY_NAME = "N2 Error Leak Probe"

        /** 테스트 전용 비밀번호 — 실 자격증명 아님. */
        const val PASSWORD = "n2-Probe-Password-1234!"

        /** git 웹훅 경로에 실리는 원문 토큰(테스트 전용). 응답 본문 누출 판정의 검색어다. */
        const val GIT_RAW_TOKEN = "n2-git-raw-token-abcdef0123456789"

        /** iCal 피드 경로에 실리는 원문 토큰(테스트 전용). 등록될 필요는 없다 — 404 수렴 자체가 이 축의 조건이다. */
        const val ICAL_RAW_TOKEN = "n2-ical-raw-token-abcdef0123456789"

        const val HEADER_AUTHORIZATION = "Authorization"
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val CONTENT_TYPE_JSON = "application/json"
        const val BEARER_PREFIX = "Bearer "

        /** 405 판별자 — 요청이 DispatcherServlet 매핑 조회까지 도달했다는 증거(T15-7 과 같은 근거). */
        const val HEADER_ALLOW = "Allow"

        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401

        val ACCESS_TOKEN_REGEX = Regex(""""access_token"\s*:\s*"([^"]+)"""")
    }
}
