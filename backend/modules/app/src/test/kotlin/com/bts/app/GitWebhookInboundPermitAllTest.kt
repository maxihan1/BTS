// git·automation 인바운드 웹훅이 prod 조립 필터체인을 실제로 통과하는지 실 HTTP 로 검증 (FR-AT-07 PR-C · T15)

package com.bts.app

import com.bts.shared.crypto.SecretEncryptor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * prod 조립 컨텍스트에서 **git · automation 인바운드 웹훅 2경로군**이 중앙 `SecurityConfig` 필터체인
 * (permitAll + CSRF-ignore + bearer-skip)을 실제로 통과하는지 **실 HTTP** 로 검증한다
 * (FR-AT-07 PR-C, ADR `2026-07-17-git-webhook-inbound-permitall`). [SlackInboundPermitAllTest] 동형.
 *
 * ## 왜 이 테스트가 유일한 관문인가
 * automation BC 는 **테스트 전용** 필터체인(`AutomationTestSecurityConfig`)으로 자기 경계를 검증한다.
 * 그 설정은 `@TestConfiguration` 이라 prod 조립에 존재하지 않고, 심지어 `csrf { it.disable() }` 이라
 * **중앙 CSRF-ignore 누락을 원리적으로 잡지 못한다**(그 클래스 KDoc 이 명시). 즉 BC 테스트가 전부
 * 초록이어도 prod 에서는 `anyRequest().authenticated()` 나 `CsrfFilter` 에 걸려 죽어 있을 수 있다.
 * 이 테스트만이 **중앙** `SecurityConfig` 가 조립 컨텍스트에서 실제로 통과시키는지 말해 준다.
 *
 * ## ★ 양성 단언 — "401 이 아님"에 기대지 않는다
 * `GitWebhookSignatureVerifier` 는 결정론적 HMAC-SHA256(GitHub `sha256=`+hex(HMAC(secret, rawBody)))이고
 * secret 은 이 테스트가 [SecretEncryptor] 로 직접 암호화해 심는다. 따라서 **유효 서명을 직접 계산**해
 * 보낼 수 있고, 202 는 "필터 통과 + 컨트롤러 서명 검증 통과"를 한 번에 **양성 증명**한다.
 *
 * ## ★ 상태코드만 보면 vacuous — 판별자는 응답 **본문**이다
 * 이 엔드포인트의 401 은 두 출처가 있고 **상태코드가 같다**.
 * - 필터 401 — 본문이 **비어 있고** `WWW-Authenticate: Bearer` 가 붙는다(실측).
 * - 컨트롤러 401 — `application/problem+json` 본문에 [ERROR_CODE_UNAUTHORIZED] 가 실린다(실측).
 *
 * 따라서 "401 이다"만 단언하면 permitAll 이 죽어도 통과한다(slack 쪽에서 실제로 확인된 함정 —
 * [SlackInboundPermitAllTest] 의 `EC-A1` 주석). 본문으로만 검증 주체를 가른다.
 *
 * ## ★ [java.net.http.HttpClient] 를 쓰는 이유 (베이스의 `rest` 를 쓰지 않는다)
 * `TestRestTemplate` 은 `:modules:app` 에 Apache HttpComponents 5 가 없어
 * [org.springframework.http.client.SimpleClientHttpRequestFactory] → `HttpURLConnection` 으로 떨어진다.
 * 그 조합은 **본문이 있는 요청이 401 을 받으면** `HttpRetryException("cannot retry due to server
 * authentication, in streaming mode")` 로 터져 **응답 본문을 아예 읽지 못한다**(실측 — `setOutputStreaming(false)`
 * 로도 재현됐다). 본문이 유일한 판별자인 이 테스트에서 그것은 위 검증 축이 통째로 무의미해진다는 뜻이다.
 * JDK 내장 [HttpClient] 는 인증 재시도·리다이렉트 자동추종이 없어(기본 `Redirect.NEVER`) 원 응답을
 * 그대로 관측한다. 실 Tomcat·실 필터체인을 그대로 타므로 서블릿 우회(가짜 그린)가 아니다.
 *
 * ## 사전 조건
 * dev postgres 기동 — `docker compose -f infra/docker-compose.dev.yml up -d postgres` (5433).
 * 컨텍스트 캐시 공유를 위해 [ProdAssemblyHttpTestBase] 를 상속만 하고 `@SpringBootTest`·`@ActiveProfiles`·
 * `@DynamicPropertySource` 를 자체 선언하지 않는다(베이스 KDoc "webEnvironment는 컨텍스트 캐시 키의 일부다").
 */
class GitWebhookInboundPermitAllTest : ProdAssemblyHttpTestBase() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val webhookId: UUID = UUID.randomUUID()

    /**
     * 등록행을 매 테스트마다 새로 심는다. `git_webhooks` 는 cross-BC FK 가 없어(V307) 프로젝트/사용자
     * 시드 없이 단순 INSERT 로 충분하다. secret 은 베이스가 주입한 것과 **같은 키/salt** 로 직접 암호화해
     * 넣는다 — 앱의 `automationSecretEncryptor` 빈이 이 암호문을 복호화해 202 가 나면 프로퍼티 배선까지
     * 한 번에 증명된다.
     */
    @BeforeEach
    fun seed() {
        jdbc.update("DELETE FROM git_webhooks WHERE project_key = ?", PROJECT_KEY) // deliveries 는 FK CASCADE
        jdbc.update("DELETE FROM automation_rules WHERE project_key = ?", PROJECT_KEY)

        val encryptor = SecretEncryptor(TEST_AUTOMATION_ENCRYPTION_KEY, TEST_AUTOMATION_ENCRYPTION_SALT)
        jdbc.update(
            """
            INSERT INTO git_webhooks (id, project_key, provider, token_hash, secret_encrypted, created_at, created_by)
            VALUES (?, ?, 'GITHUB', ?, ?, ?, ?)
            """.trimIndent(),
            webhookId,
            PROJECT_KEY,
            sha256Hex(GIT_RAW_TOKEN),
            encryptor.encrypt(SECRET),
            Instant.now().atOffset(ZoneOffset.UTC),
            UUID.randomUUID(),
        )
        jdbc.update(
            """
            INSERT INTO automation_rules
                (id, project_key, name, enabled, trigger_type, trigger_config, webhook_token_hash,
                 created_by, actor_user_id)
            VALUES (?, ?, ?, true, 'WEBHOOK', '{}'::jsonb, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            PROJECT_KEY,
            "t15-inbound-permitall-rule",
            sha256Hex(AUTOMATION_RAW_TOKEN),
            UUID.randomUUID(),
            UUID.randomUUID(),
        )
    }

    @Test
    fun `유효 서명 git 웹훅이 필터를 통과해 202 로 수신된다 (T15-1)`() {
        val body = mergeEventBody()

        val response = post(gitUrl(GIT_RAW_TOKEN), body, jsonSignedHeaders(body))

        // 202 = permitAll + CSRF-ignore + bearer-skip 통과 + 컨트롤러 HMAC 검증 통과의 동시 증명.
        assertThat(response.statusCode()).isEqualTo(HTTP_ACCEPTED)
        // 배달행 1건 = 컨트롤러가 응답만 한 게 아니라 GitWebhookService 파이프라인까지 실제로 돌았다는 증거
        // (등록 → 서명검증 → 판정 → dedup INSERT 순서를 전부 통과해야만 이 행이 생긴다).
        assertThat(deliveryCount()).isEqualTo(1)
    }

    @Test
    fun `automation 웹훅이 유효 토큰으로 필터를 통과해 202 로 수신된다 (T15-2)`() {
        val response = post(automationUrl(AUTOMATION_RAW_TOKEN), "{}", mapOf(HEADER_CONTENT_TYPE to CONTENT_TYPE_JSON))

        // automation 경로군은 서명 검증이 없어 **불투명 토큰 소지 자체가 인증**이다(중앙 KDoc §검증 주체 이관).
        // 그래서 이 202 는 git 과 달리 "서명 통과"가 아니라 "필터 통과 + 토큰 조회 성공"을 증명한다.
        assertThat(response.statusCode()).isEqualTo(HTTP_ACCEPTED)
    }

    @Test
    fun `서명 불일치는 필터가 아니라 컨트롤러가 401 로 거부한다 (T15-3)`() {
        val body = mergeEventBody()

        val response = post(gitUrl(GIT_RAW_TOKEN), body, jsonHeaders(FORGED_SIGNATURE))

        assertThat(response.statusCode()).isEqualTo(HTTP_UNAUTHORIZED)
        // ★ 상태코드만으로는 vacuous — 필터 401 도 401 이다. 컨트롤러가 응답했다는 증거는 **본문**뿐이다
        // (필터 401 은 본문이 비어 있음). 이 단언이 곧 "permitAll 이 살아 있다"의 증명이다.
        assertThat(response.body()).contains(ERROR_CODE_UNAUTHORIZED)
    }

    @Test
    fun `미존재 토큰과 서명 불일치의 401 본문이 같다 — 토큰 존재 오라클 부재 (T15-4)`() {
        val body = mergeEventBody()

        val unknownToken = post(gitUrl(UNKNOWN_TOKEN), body, jsonSignedHeaders(body))
        val badSignature = post(gitUrl(GIT_RAW_TOKEN), body, jsonHeaders(FORGED_SIGNATURE))

        assertThat(unknownToken.statusCode()).isEqualTo(HTTP_UNAUTHORIZED)
        assertThat(badSignature.statusCode()).isEqualTo(HTTP_UNAUTHORIZED)
        // EC1(미존재 토큰) 과 S2(서명 불일치) 의 본문이 timestamp 를 빼면 **완전히 같아야** 한다.
        // 조금이라도 다르면(errorCode·detail·type·instance 중 하나라도) 그 차이가 곧 "이 토큰이 실재하는가"를
        // 알려주는 오라클이다 — 404 대신 401 을 쓰면서까지 막은 것이 응답 본문으로 부활한다.
        assertThat(withoutTimestamp(unknownToken.body())).isEqualTo(withoutTimestamp(badSignature.body()))
    }

    @Test
    fun `서명 미검증 요청은 배달 이력에 흔적을 남기지 않는다 (T15-5 · spec §3-8)`() {
        val body = mergeEventBody()

        repeat(UNAUTHENTICATED_ATTEMPTS) {
            val response = post(gitUrl(GIT_RAW_TOKEN), body, jsonHeaders(FORGED_SIGNATURE))
            assertThat(response.statusCode()).isEqualTo(HTTP_UNAUTHORIZED)
        }

        // 미인증 요청은 DB 쓰기 0 — 컨트롤러가 서명 검증을 통과하기 **전에는** GitWebhookService 를
        // 절대 호출하지 않는다는 순서 계약(그 클래스 KDoc "호출 전제")이 지켜지는지 실 HTTP 로 확인한다.
        assertThat(deliveryCount()).isZero()
    }

    @Test
    fun `form-urlencoded 오설정 응답에 원문 토큰이 실리지 않는다 (T15-6)`() {
        // GitHub 웹훅 UI 는 content type 으로 form-urlencoded 를 **고를 수 있고**, GitHub 은 응답 본문을
        // delivery 기록에 저장·표시한다 — 응답에 토큰이 실리면 운영자 오설정 하나로 평문 토큰이 GitHub 에
        // 영구 기록된다(DEVELOPMENT.md §1.1-2).
        val response = post(gitUrl(GIT_RAW_TOKEN), "action=closed", mapOf(HEADER_CONTENT_TYPE to CONTENT_TYPE_FORM))

        // ★ 이 단언은 vacuous 하지 않다 — 뮤테이션으로 실증했다.
        // 컨트롤러는 consumes=JSON 이라 form 요청을 415 로 거부하는데, 415 는 컨트롤러의 @ExceptionHandler 가
        // 잡지 않아 서블릿 ERROR 디스패치(/error)로 넘어간다. /error 는 중앙 SecurityConfig 에서
        // anyRequest().authenticated() 에 걸리므로 **BasicErrorController 가 실행되지 못하고** 필터가 빈 401 을
        // 준다 — 그래서 지금은 누출이 없다. 그러나 /error 를 permitAll 에 넣어 보면(실측) 응답이
        // 415 + `{"path":"/api/v1/webhooks/git/<원문토큰>"}` 로 바뀌어 **이 단언이 실제로 깨진다**.
        // 즉 현재의 안전은 컨트롤러 설계가 아니라 "/error 가 인증 대상"이라는 **간접 조건**에 의존한다.
        // 이 테스트가 그 조건을 못 박는 회귀 가드다(ProblemDetail 의 instance 고정만으로는 닫히지 않는 별개 통로).
        assertThat(response.body()).doesNotContain(GIT_RAW_TOKEN)
    }

    @Test
    fun `GET 은 permitAll 메서드 고정으로 필터가 거부한다 (T15-7)`() {
        val response = get(gitUrl(GIT_RAW_TOKEN))

        assertThat(response.statusCode()).isEqualTo(HTTP_UNAUTHORIZED)
        // 필터가 REQUEST 디스패치에서 잘랐다는 증거 — 컨트롤러 ProblemDetail 이 없어야 한다.
        // (permitAll 이 메서드를 고정하지 않고 경로만 열면 GET 이 DispatcherServlet 까지 도달해 405 를 내고,
        // 그 405 는 /error 를 타며 위 T15-6 과 같은 토큰 누출 통로에 올라탄다.)
        assertThat(response.body()).doesNotContain(ERROR_CODE_UNAUTHORIZED)
        assertThat(response.body()).doesNotContain(GIT_RAW_TOKEN)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** `git_webhook_deliveries` 중 이 테스트 등록행의 배달 수 — 미인증 요청의 DB 쓰기 0 판정용. */
    private fun deliveryCount(): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM git_webhook_deliveries WHERE webhook_id = ?",
            Int::class.java,
            webhookId,
        )!!

    /**
     * ProblemDetail 본문에서 `timestamp` 필드를 지운다 — 두 401 본문의 동치 비교(T15-4)에서
     * **유일하게 달라도 되는** 필드다(호출 시각). 나머지가 하나라도 다르면 존재 오라클이다.
     */
    private fun withoutTimestamp(body: String): String = TIMESTAMP_FIELD_REGEX.replace(body, "")

    /**
     * GITHUB `pull_request` 머지 이벤트 본문 — 이슈키 1건이 프로젝트 스코프에 들어간다.
     *
     * `PrIssueKeyExtractor` 는 Closes/Fixes/Resolves 키워드가 **선행해야만** 이슈키를 추출한다. 맨 키를
     * 넣으면 추출 0건 → 파이프라인이 dedup 이전에 조용히 종료돼 202 는 그대로지만 배달행이 안 생긴다
     * (T15-1 의 배달행 단언이 그때 깨진다).
     *
     * 표현식 본문(`=`)이 아니라 블록 본문인 이유 — ktlint(140자)와 detekt(120자)의 라인 길이 기준이 달라
     * 한 줄로 붙이면 ktlint 는 붙이라 하고 detekt 는 길다고 한다. 블록 본문이 두 규칙을 모두 만족한다.
     */
    private fun mergeEventBody(): String {
        return """{"action":"closed","pull_request":{"merged":true,"title":"Closes $PROJECT_KEY-1"}}"""
    }

    /** 유효 서명 + 머지 이벤트 헤더 — 202 양성 경로용. */
    private fun jsonSignedHeaders(body: String): Map<String, String> = jsonHeaders(githubSignature(body))

    private fun jsonHeaders(signature: String): Map<String, String> =
        mapOf(
            HEADER_CONTENT_TYPE to CONTENT_TYPE_JSON,
            HEADER_GITHUB_SIGNATURE to signature,
            HEADER_GITHUB_EVENT to GITHUB_EVENT_PULL_REQUEST,
            // 매 호출 고유 — 고정하면 재전송 dedup 이 걸려 배달행 단언이 요청 순서에 의존하게 된다.
            HEADER_GITHUB_DELIVERY to UUID.randomUUID().toString(),
        )

    private fun gitUrl(token: String): String = "http://localhost:$port/api/v1/webhooks/git/$token"

    private fun automationUrl(token: String): String = "http://localhost:$port/api/v1/automation/webhooks/$token"

    private fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): HttpResponse<String> = exchange(url, headers) { it.POST(HttpRequest.BodyPublishers.ofString(body)) }

    private fun get(url: String): HttpResponse<String> = exchange(url, emptyMap()) { it.GET() }

    /**
     * 실 HTTP 왕복 1회. 클래스 KDoc "★ HttpClient 를 쓰는 이유" 참조 — 인증 재시도·리다이렉트 자동추종이
     * 없어야 401/302 원 응답의 **본문**을 그대로 관측할 수 있다.
     */
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

    /**
     * `sha256=` + lowercase-hex(HMAC-SHA256([SECRET], body)) — `GitWebhookSignatureVerifier` 와 같은 계산을
     * **검증 대상 코드와 무관하게** 재현한다(그 클래스를 호출하면 자기 자신으로 자기를 검증하게 된다).
     */
    private fun githubSignature(body: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(SECRET.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return GITHUB_SIGNATURE_PREFIX + HexFormat.of().formatHex(mac.doFinal(body.toByteArray(Charsets.UTF_8)))
    }

    /** 등록 시 저장되는 `token_hash` 와 **같은 알고리즘**(원문의 SHA-256 hex)이어야 조회가 매칭된다. */
    private fun sha256Hex(raw: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)),
        )

    private companion object {
        /** 이 테스트 전용 프로젝트 키 — 다른 조립 테스트 데이터와 섞이지 않게 시드/정리 범위를 좁힌다. */
        const val PROJECT_KEY = "T15W"

        /** git 웹훅 원문 토큰(테스트 전용, 실 토큰 아님). 응답 본문 누출 판정의 검색어이기도 하다. */
        const val GIT_RAW_TOKEN = "t15-git-raw-token-abcdef0123456789"

        /** automation 웹훅 원문 토큰(테스트 전용). */
        const val AUTOMATION_RAW_TOKEN = "t15-automation-raw-token-abcdef0123456789"

        /** 등록행에 심는 서명 secret(테스트 전용). */
        const val SECRET = "t15-webhook-signing-secret-not-a-real-secret"

        /** 어떤 등록행과도 매칭되지 않는 토큰 — EC1(미존재) 경로. */
        const val UNKNOWN_TOKEN = "t15-unknown-token-0000"

        /** 형식은 맞지만(`sha256=`+hex 64자) secret 을 모르는 서명 — 서명 불일치 거부 경로. */
        const val FORGED_SIGNATURE = "sha256=0000000000000000000000000000000000000000000000000000000000000000"

        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val CONTENT_TYPE_JSON = "application/json"
        const val CONTENT_TYPE_FORM = "application/x-www-form-urlencoded"
        const val HEADER_GITHUB_SIGNATURE = "X-Hub-Signature-256"
        const val HEADER_GITHUB_EVENT = "X-GitHub-Event"
        const val HEADER_GITHUB_DELIVERY = "X-GitHub-Delivery"
        const val GITHUB_EVENT_PULL_REQUEST = "pull_request"
        const val GITHUB_SIGNATURE_PREFIX = "sha256="
        const val HMAC_ALGORITHM = "HmacSHA256"

        /** `GitWebhookController` 가 EC1~EC4·EC9·EC15 에 **공통**으로 싣는 단일 errorCode(= 컨트롤러 도달 증거). */
        const val ERROR_CODE_UNAUTHORIZED = "GIT_WEBHOOK_UNAUTHORIZED"

        const val HTTP_ACCEPTED = 202
        const val HTTP_UNAUTHORIZED = 401

        /** 미인증 반복 시도 횟수 — 1회면 "우연히 안 썼다"와 구분이 약하다. */
        const val UNAUTHENTICATED_ATTEMPTS = 3

        /** ProblemDetail 의 `timestamp` 필드(두 401 본문 비교에서 제외할 유일한 필드). */
        val TIMESTAMP_FIELD_REGEX = Regex(""","timestamp":"[^"]*"""")
    }
}
