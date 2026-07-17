// GitWebhookController 단위 테스트 — 401 단일화(오라클 부재)·크기상한·시그니처 화이트리스트·DB쓰기0 (FR-AT-07 PR-C Task 10)

package com.bts.automation.adapter.web

import com.bts.automation.adapter.GitWebhookRepository
import com.bts.automation.application.GitWebhookService
import com.bts.automation.domain.GitProvider
import com.bts.automation.domain.GitWebhook
import com.bts.automation.security.GitWebhookSignatureVerifier
import com.bts.shared.crypto.SecretEncryptor
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import java.lang.reflect.Method
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * [GitWebhookController] 의 보안 계약 검증 (FR-AT-07 PR-C Task 10, spec §7·§9).
 *
 * ## 이 컨트롤러가 방어선 그 자체다
 * `POST /api/v1/webhooks/git/{token}` 은 Spring Security 필터에서 permitAll 이다
 * (ADR `2026-07-17-git-webhook-inbound-permitall.md` — DEVELOPMENT.md §1.4 정식 예외). 즉 **서명이 틀린
 * 요청도 핸들러까지 도달**하며, 인증 주체는 필터가 아니라 이 컨트롤러다. 따라서 여기서 검증하는 것은
 * "기능이 동작하는가"가 아니라 **"미인증 요청이 무엇을 얻어갈 수 있는가"** 다.
 *
 * ## 실 [GitWebhookSignatureVerifier] 를 쓴다 (mockk 아님)
 * 검증기를 mockk 로 대체하면 "서명이 통과했는지"가 stub 설정에 좌우되어, 원문 바이트 HMAC·헤더 교차·
 * 복호화 실패 수렴 같은 **실제 계약**을 관측할 수 없다([SlackInboundBodyGuardTest] 선례 동형).
 * [SecretEncryptor] 만 mockk 로 복호화 결과를 고정한다([GitWebhookSignatureVerifierTest] 동형).
 *
 * ## 검증 축 4가지
 * 1. **401 단일화 = 존재 오라클 부재**(B3-sec). EC1~EC4·EC9·EC15 가 전부 같은 errorCode 이고,
 *    EC1(미존재 토큰) 본문과 S2(서명 불일치) 본문이 timestamp 를 빼면 **완전히 동일**하다.
 * 2. **크기 상한이 토큰 존재 여부와 무관**(§5-1 오라클 금지의 상태코드 축). 아래 §크기 상한 참조.
 * 3. **핸들러 시그니처 화이트리스트**(§9-7). blacklist(`@RequestBody` 부재)만으로는
 *    `@RequestParam`·`@ModelAttribute`·`HttpEntity<ByteArray>` 가 전부 통과한다.
 * 4. **서명 미검증 요청은 DB 쓰기 0**(§9-12, DEC-23). dedup INSERT 는 [GitWebhookService] 안에 있으므로
 *    서비스 **미호출**과 `insertDelivery` **미호출**을 함께 단언한다.
 *
 * ## ★ 크기 상한은 토큰 조회보다 **먼저** — 상태코드가 오라클이 되지 않게
 * spec §3.5 의 파이프라인 번호는 ①토큰조회 → ②크기상한이지만, 그 순서로 구현하면
 * **`413` vs `401` 자체가 토큰 존재 오라클**이 된다(초과 본문 + 미존재 토큰 → 401 / + 실재 토큰 → 413).
 * `Content-Length` 사전검사까지 있어 공격자는 **바이트를 한 개도 보내지 않고** 후보 토큰의 실재를 판별할
 * 수 있다 — 404 를 포기하면서까지 막은 그 오라클이다. §7 EC5 가 요구하는 불변식은 "413 은 **서명 검증**
 * 이전"이고, plan Task 10 이 요구하는 불변식은 "토큰 조회는 **payload 파싱**보다 먼저"다. 크기 상한을
 * 맨 앞에 두면 **두 불변식을 모두 지키면서** 오라클이 사라진다([크기 상한은 토큰이 실재하지 않아도 413 이다]).
 * `readNBytes` 가 힙을 256KB 로 캡하므로 DoS 델타는 없다(자세한 근거는 컨트롤러 KDoc).
 */
@Suppress("TooManyFunctions") // 4개 검증축 × EC 9종 + 요청/서명 헬퍼 — 책임은 단일(인바운드 웹훅 방어선)
class GitWebhookControllerTest {
    private val repository = mockk<GitWebhookRepository>()
    private val service = mockk<GitWebhookService>()
    private val encryptor = mockk<SecretEncryptor>()
    private val objectMapper = ObjectMapper()

    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                GitWebhookController(repository, GitWebhookSignatureVerifier(encryptor), service, objectMapper),
            ).build()

    // ── 축 1. 정상 경로 — 유효 서명이면 202 + 서비스 위임 ────────────────────────────

    @Test
    fun `GITHUB - 유효한 HMAC 서명이면 202 로 수락하고 이벤트·배달 헤더를 서비스에 넘긴다`() {
        val webhook = githubWebhook()
        every { encryptor.decrypt(CIPHERTEXT) } returns SECRET
        every { repository.findByTokenHash(tokenHash()) } returns webhook
        justRun { service.handleInboundEvent(any(), any(), any(), any()) }

        mockMvc
            .perform(githubRequest(signature = githubSignature(SECRET, GITHUB_BODY)))
            .andExpect(status().isAccepted)

        verify(exactly = 1) {
            service.handleInboundEvent(webhook, "pull_request", "delivery-1", objectMapper.readTree(GITHUB_BODY))
        }
    }

    @Test
    fun `GITLAB - 유효한 평문 토큰 헤더면 202 로 수락하고 GitLab 전용 헤더를 서비스에 넘긴다`() {
        val webhook = gitlabWebhook()
        every { encryptor.decrypt(CIPHERTEXT) } returns SECRET
        every { repository.findByTokenHash(tokenHash()) } returns webhook
        justRun { service.handleInboundEvent(any(), any(), any(), any()) }

        mockMvc
            .perform(
                jsonRequest(GITLAB_BODY)
                    .header(HEADER_GITLAB_TOKEN, SECRET)
                    .header(HEADER_GITLAB_EVENT, "Merge Request Hook")
                    .header(HEADER_GITLAB_EVENT_UUID, "uuid-1"),
            ).andExpect(status().isAccepted)

        verify(exactly = 1) {
            service.handleInboundEvent(webhook, "Merge Request Hook", "uuid-1", objectMapper.readTree(GITLAB_BODY))
        }
    }

    // ── 축 2. 401 단일화 — EC1~EC4·EC9·EC15 가 사유를 구분하지 않는다 ──────────────────

    @Test
    fun `EC1 - 미존재(또는 소프트삭제) 토큰은 401 단일 errorCode 로 거부한다`() {
        every { repository.findByTokenHash(any()) } returns null

        mockMvc
            .perform(githubRequest(signature = githubSignature(SECRET, GITHUB_BODY)))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value(UNAUTHORIZED_CODE))

        verifyNoDbWrite()
    }

    @Test
    fun `S2 - 서명이 일치하지 않으면 401 단일 errorCode 로 거부한다`() {
        stubGithubWebhook(secret = SECRET)

        mockMvc
            .perform(githubRequest(signature = "sha256=" + "0".repeat(SHA256_HEX_LENGTH)))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value(UNAUTHORIZED_CODE))

        verifyNoDbWrite()
    }

    @Test
    fun `EC2 - GITHUB 등록인데 X-Gitlab-Token 만 보내면 401 (폴백 금지)`() {
        stubGithubWebhook(secret = SECRET)

        mockMvc
            .perform(jsonRequest(GITHUB_BODY).header(HEADER_GITLAB_TOKEN, SECRET))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value(UNAUTHORIZED_CODE))

        verifyNoDbWrite()
    }

    @Test
    fun `EC3 - GITLAB 등록인데 X-Hub-Signature-256 만 보내면 401 (폴백 금지)`() {
        every { encryptor.decrypt(CIPHERTEXT) } returns SECRET
        every { repository.findByTokenHash(tokenHash()) } returns gitlabWebhook()

        mockMvc
            .perform(githubRequest(signature = githubSignature(SECRET, GITHUB_BODY)))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value(UNAUTHORIZED_CODE))

        verifyNoDbWrite()
    }

    @Test
    fun `EC4 - GitHub 레거시 X-Hub-Signature(SHA-1) 는 읽지 않으므로 401`() {
        stubGithubWebhook(secret = SECRET)

        // 컨트롤러가 레거시 헤더를 아예 읽지 않아야 검증기에 null 이 도달해 거부된다.
        mockMvc
            .perform(jsonRequest(GITHUB_BODY).header(HEADER_GITHUB_LEGACY_SIGNATURE, sha1Signature(SECRET, GITHUB_BODY)))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value(UNAUTHORIZED_CODE))

        verifyNoDbWrite()
    }

    @Test
    fun `EC9 - secret 복호화 실패는 500 이 아니라 401 단일 errorCode 로 수렴한다`() {
        // 복호화 예외가 컨트롤러 밖으로 새면 401 이어야 할 응답이 500 이 된다(저장소 3대 예외 변질 사고).
        every { encryptor.decrypt(CIPHERTEXT) } throws IllegalStateException("automation encryption key is not set")
        every { repository.findByTokenHash(tokenHash()) } returns githubWebhook()

        mockMvc
            .perform(githubRequest(signature = githubSignature(SECRET, GITHUB_BODY)))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value(UNAUTHORIZED_CODE))

        verifyNoDbWrite()
    }

    @Test
    fun `EC15 - 복호화 결과가 blank secret 이면 401 (fail-closed)`() {
        stubGithubWebhook(secret = "   ")

        mockMvc
            .perform(githubRequest(signature = githubSignature("   ", GITHUB_BODY)))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value(UNAUTHORIZED_CODE))

        verifyNoDbWrite()
    }

    /**
     * ★★ B3-sec 의 핵심 단언 — 응답 본문이 존재 오라클이 되지 않는다.
     *
     * errorCode·detail·title·type 중 **하나라도** 사유별로 갈리면, 토큰만 아는(= secret 은 모르는) 공격자가
     * "이 토큰이 실재하는가"를 응답으로 판별할 수 있다. 404 를 포기하면서까지 막은 그 오라클이 부활한다.
     */
    @Test
    fun `오라클 부재 - EC1(미존재 토큰) 401 본문과 S2(서명 불일치) 401 본문이 timestamp 를 빼면 동일하다`() {
        val badSignature = "sha256=" + "0".repeat(SHA256_HEX_LENGTH)

        every { repository.findByTokenHash(any()) } returns null
        val absentTokenBody = performUnauthorized(badSignature)

        stubGithubWebhook(secret = SECRET)
        val badSignatureBody = performUnauthorized(badSignature)

        assertThat(withoutTimestamp(absentTokenBody))
            .describedAs(
                "미존재 토큰 401 과 서명 불일치 401 의 응답 본문이 다르면, 그 차이가 곧 토큰 존재 오라클이다 " +
                    "— 사유 구분은 로그에서만 한다(GitWebhookSignatureVerifier 의 git_webhook_* 이벤트명).",
            ).isEqualTo(withoutTimestamp(badSignatureBody))
    }

    // ── 축 3. 크기·미디어타입·파싱 ─────────────────────────────────────────────────

    @Test
    fun `EC5 - 256KB 를 초과한 본문은 413 으로 거절한다`() {
        stubGithubWebhook(secret = SECRET)

        mockMvc
            .perform(jsonRequest(oversizedBody()).header(HEADER_GITHUB_SIGNATURE, "sha256=irrelevant"))
            .andExpect(status().isPayloadTooLarge)

        verifyNoDbWrite()
    }

    /**
     * ★ 크기 상한의 판정이 **토큰 존재 여부에 의존하지 않는다** — 상태코드 축의 오라클 부재.
     * 토큰 조회를 크기 검사보다 먼저 하면 이 케이스가 401 이 되어, `413`/`401` 차이가 곧 존재 오라클이 된다.
     */
    @Test
    fun `크기 상한은 토큰이 실재하지 않아도 413 이다 (413 vs 401 이 존재 오라클이 되지 않는다)`() {
        every { repository.findByTokenHash(any()) } returns null

        mockMvc
            .perform(jsonRequest(oversizedBody()).header(HEADER_GITHUB_SIGNATURE, "sha256=irrelevant"))
            .andExpect(status().isPayloadTooLarge)

        // 크기 거절이 조회보다 먼저이므로 DB 왕복 자체가 없다.
        verify(exactly = 0) { repository.findByTokenHash(any()) }
        verifyNoDbWrite()
    }

    @Test
    fun `경계값 - 정확히 256KB 인 본문은 크기 가드를 통과해 서명 검증까지 진행한다`() {
        stubGithubWebhook(secret = SECRET)

        // off-by-one 방지. 상한 이내이므로 413 이 아니라 서명 검증 결과(불일치 → 401)가 나와야 한다.
        mockMvc
            .perform(jsonRequest(bodyOfSize(MAX_PAYLOAD_BYTES)).header(HEADER_GITHUB_SIGNATURE, "sha256=irrelevant"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `EC6 - form-urlencoded 는 415 로 명시 거부한다`() {
        // consumes 미지정이면 readTree 가 실패해 400/202 로 조용히 흘러가 운영자가 원인을 못 찾는다.
        mockMvc
            .perform(
                post(PATH, RAW_TOKEN)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .content("action=closed")
                    .header(HEADER_GITHUB_EVENT, "pull_request"),
            ).andExpect(status().isUnsupportedMediaType)

        verifyNoDbWrite()
    }

    @Test
    fun `EC7 - 서명이 유효해도 본문이 JSON 이 아니면 400`() {
        val brokenJson = "{\"action\":".toByteArray(Charsets.UTF_8)
        stubGithubWebhook(secret = SECRET)

        mockMvc
            .perform(jsonRequest(brokenJson).header(HEADER_GITHUB_SIGNATURE, githubSignature(SECRET, brokenJson)))
            .andExpect(status().isBadRequest)

        verifyNoDbWrite()
    }

    // ── 축 4. 구조 — 핸들러 시그니처 화이트리스트 (§9-7, NFR-2) ──────────────────────

    /**
     * ★ blacklist 가 아니라 **화이트리스트**로 못 박는다.
     * `@RequestBody` 부재만 검사하면 `@RequestParam`·`@ModelAttribute`(무애노테이션 non-simple 타입도
     * 암묵 적용)·`HttpEntity<ByteArray>` 가 전부 통과해, 서명 검증 **이전에** 본문 전체가 힙에 적재된다
     * (nginx `client_max_body_size 110m` + `mem_limit 1536m` → 미인증 OOM).
     * 특히 `@RequestParam` 병용은 form 파싱이 스트림을 소진해 **서명 검증을 조용히 무력화**한다.
     */
    @Test
    fun `핸들러 파라미터는 정확히 (PathVariable String, HttpServletRequest) 이고 그 외 애노테이션이 없다`() {
        val handler = postHandlerOf(GitWebhookController::class.java)

        assertThat(handler.parameterTypes.toList())
            .describedAs(
                "permitAll 인바운드 핸들러는 HttpServletRequest 를 직접 받아 상한만큼만 스트리밍으로 읽어야 " +
                    "한다. @RequestBody/HttpEntity 는 서명 검증 이전에 본문 전체를 힙에 적재한다.",
            ).containsExactly(String::class.java, HttpServletRequest::class.java)

        assertThat(handler.parameters.flatMap { it.annotations.toList() }.map { it.annotationClass.java.name })
            .describedAs(
                "파라미터 애노테이션 화이트리스트 — @PathVariable 하나만 허용한다. @RequestParam/" +
                    "@ModelAttribute 가 붙으면 서블릿 form 파싱이 본문 스트림을 소진해 서명 검증 대상이 " +
                    "빈 바이트가 된다(bearer-token-resolver-drains-form-body 동류).",
            ).containsExactly(PathVariable::class.java.name)
    }

    /**
     * ★ vacuous 방지 — 위 룰이 "핸들러를 못 찾아서" 통과하는 상황을 차단한다
     * (memory `archunit-vacuous-rule-silent-pass`).
     */
    @Test
    fun `컨트롤러에 POST 핸들러가 정확히 하나 존재한다`() {
        assertThat(postHandlerOf(GitWebhookController::class.java).name).isEqualTo("receive")
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────────

    /** 컨트롤러의 유일한 `@PostMapping` 핸들러를 찾는다(0개/2개 이상이면 룰 자체가 깨진 것이므로 실패). */
    private fun postHandlerOf(controller: Class<*>): Method {
        val handlers = controller.declaredMethods.filter { it.isAnnotationPresent(PostMapping::class.java) }
        assertThat(handlers).describedAs("%s 의 @PostMapping 핸들러", controller.simpleName).hasSize(1)
        return handlers.single()
    }

    /** 서명 미검증 요청은 `git_webhook_deliveries`·`q_automation_execution` 에 어떤 쓰기도 남기지 않는다(DEC-23). */
    private fun verifyNoDbWrite() {
        verify(exactly = 0) { service.handleInboundEvent(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.insertDelivery(any(), any(), any()) }
    }

    private fun performUnauthorized(signature: String): String =
        mockMvc
            .perform(githubRequest(signature = signature))
            .andExpect(status().isUnauthorized)
            .andReturn()
            .response
            .contentAsString

    /** ProblemDetail 본문에서 매 요청 달라지는 `timestamp` 만 제거해 나머지 전체를 비교 가능하게 만든다. */
    private fun withoutTimestamp(body: String): String =
        (objectMapper.readTree(body) as ObjectNode).apply { remove("timestamp") }.toString()

    private fun stubGithubWebhook(secret: String) {
        every { encryptor.decrypt(CIPHERTEXT) } returns secret
        every { repository.findByTokenHash(tokenHash()) } returns githubWebhook()
    }

    private fun githubWebhook(): GitWebhook = webhook(GitProvider.GITHUB)

    private fun gitlabWebhook(): GitWebhook = webhook(GitProvider.GITLAB)

    private fun webhook(provider: GitProvider): GitWebhook =
        GitWebhook(
            id = WEBHOOK_ID,
            projectKey = PROJECT_KEY,
            provider = provider,
            tokenHash = tokenHash(),
            secretEncrypted = CIPHERTEXT,
            createdAt = Instant.parse("2026-07-17T00:00:00Z"),
            createdBy = ACTOR_ID,
            deletedAt = null,
        )

    private fun jsonRequest(body: ByteArray): MockHttpServletRequestBuilder =
        post(PATH, RAW_TOKEN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)

    private fun githubRequest(signature: String): MockHttpServletRequestBuilder =
        jsonRequest(GITHUB_BODY)
            .header(HEADER_GITHUB_SIGNATURE, signature)
            .header(HEADER_GITHUB_EVENT, "pull_request")
            .header(HEADER_GITHUB_DELIVERY, "delivery-1")

    private fun bodyOfSize(bytes: Int): ByteArray = ByteArray(bytes) { 'a'.code.toByte() }

    private fun oversizedBody(): ByteArray = bodyOfSize(MAX_PAYLOAD_BYTES + 1)

    /** 구현과 독립적으로 계산한 참조 토큰 해시 — 컨트롤러가 같은 알고리즘(원문 SHA-256 hex)을 써야 매칭된다. */
    private fun tokenHash(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(RAW_TOKEN.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }

    /** `sha256=` + lowercase-hex(HMAC-SHA256(secret, rawBody)) — GitHub 규격 참조 구현. */
    private fun githubSignature(
        secret: String,
        body: ByteArray,
    ): String = "sha256=" + hmacHex("HmacSHA256", secret, body)

    /** 레거시 `X-Hub-Signature` 규격(SHA-1) — 지원하지 않음을 증명하기 위해서만 만든다. */
    private fun sha1Signature(
        secret: String,
        body: ByteArray,
    ): String = "sha1=" + hmacHex("HmacSHA1", secret, body)

    private fun hmacHex(
        algorithm: String,
        secret: String,
        body: ByteArray,
    ): String {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), algorithm))
        return mac.doFinal(body).joinToString(separator = "") { "%02x".format(it) }
    }

    private companion object {
        const val PATH = "/api/v1/webhooks/git/{token}"
        const val RAW_TOKEN = "gitwebhook-raw-token-DO-NOT-LOG-9142"
        const val PROJECT_KEY = "ATLAS"
        const val SECRET = "hmac-shared-secret-0123456789abcdef"
        const val CIPHERTEXT = "0badc0de"

        /** 401 단일 errorCode — EC1~EC4·EC9·EC15 전부 이 값이어야 한다(spec §5-1). */
        const val UNAUTHORIZED_CODE = "GIT_WEBHOOK_UNAUTHORIZED"

        /** [GitWebhookController] 의 상한과 동일해야 한다(테스트 미러 — automation 웹훅 테스트 동형 관례). */
        const val MAX_PAYLOAD_BYTES = 256 * 1024

        const val SHA256_HEX_LENGTH = 64

        const val HEADER_GITHUB_SIGNATURE = "X-Hub-Signature-256"
        const val HEADER_GITHUB_LEGACY_SIGNATURE = "X-Hub-Signature"
        const val HEADER_GITHUB_EVENT = "X-GitHub-Event"
        const val HEADER_GITHUB_DELIVERY = "X-GitHub-Delivery"
        const val HEADER_GITLAB_TOKEN = "X-Gitlab-Token"
        const val HEADER_GITLAB_EVENT = "X-Gitlab-Event"
        const val HEADER_GITLAB_EVENT_UUID = "X-Gitlab-Event-UUID"

        val WEBHOOK_ID: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val ACTOR_ID: UUID = UUID.fromString("99999999-8888-7777-6666-555555555555")

        val GITHUB_BODY: ByteArray =
            """
            {"action":"closed","pull_request":{"merged":true,"number":7,"title":"Closes ATLAS-1",
            "body":"","base":{"ref":"main"},"merged_at":"2026-07-17T00:00:00Z",
            "html_url":"https://github.com/bts/atlas/pull/7"}}
            """.trimIndent().toByteArray(Charsets.UTF_8)

        val GITLAB_BODY: ByteArray =
            """
            {"object_attributes":{"action":"merge","iid":7,"title":"Closes ATLAS-1","description":"",
            "target_branch":"main","updated_at":"2026-07-17T00:00:00Z",
            "url":"https://gitlab.com/bts/atlas/-/merge_requests/7"}}
            """.trimIndent().toByteArray(Charsets.UTF_8)
    }
}
