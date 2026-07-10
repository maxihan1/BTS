// AutomationWebhookController 통합 테스트 — 유효 토큰 202+enqueue / 미존재·비활성·삭제 404 / payload 상한 413 (FR-AT-01 Task 9)

package com.bts.automation.web

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestSecurityConfig
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * [com.bts.automation.adapter.web.AutomationWebhookController] 통합 테스트 (FR-AT-01 Task 9).
 *
 * [AutomationTestBootApplication] 을 실 임베디드 서블릿(Tomcat, test 스코프)으로 기동해
 * `webEnvironment = RANDOM_PORT` + [TestRestTemplate] 실 HTTP 로 검증한다. payload 크기 상한(G2)은
 * 애플리케이션 계층 로직이라 MockMvc 로도 로직 자체는 통과하지만, 서블릿 경계까지 포함한
 * end-to-end 를 실증하기 위해 실 HTTP 라운드트립으로 검증한다
 * ([[multipart-default-limit-app-policy-false-green]] 반면교사).
 *
 * [AutomationTestSecurityConfig] 를 `@Import` 해 permitAll 인가 경계를 실제 필터 체인으로 검증한다
 * (기본 Spring Security 자동구성은 커스텀 필터 체인 없이는 전 경로 인증을 요구한다).
 *
 * ## 검증 시나리오
 * - 유효 토큰 → 202 + `q_automation_execution` 큐에 `{ruleId, triggerType=WEBHOOK, triggerEvent}` 도달(payload 그대로)
 * - 존재하지 않는/비활성/소프트 삭제 토큰 → 404(S7/G5, 존재 숨김)
 * - payload 상한(256KB) 초과 → 413(G2) / 정확히 상한 → 202(경계값)
 * - 유효하지 않은 JSON 본문 → 400
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Import(AutomationTestcontainersBase::class, AutomationTestSecurityConfig::class)
class AutomationWebhookControllerTest {
    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var repository: AutomationRuleRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var objectMapper: ObjectMapper

    /** 결정적 테스트를 위한 고정 기준 시각(Clock 주입 대신 헬퍼 인자로 전달, repository 테스트 동형). */
    private val now: Instant = Instant.parse("2026-07-10T00:00:00Z")

    @BeforeEach
    fun cleanUp() {
        // 테스트 격리 — 각 케이스 전에 테이블/큐를 비운다(AutomationRuleRepositoryTest 동형).
        jdbcTemplate.update("DELETE FROM automation_rules")
        jdbcTemplate.execute("SELECT pgmq.purge_queue('q_automation_execution')")
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun webhookRule(tokenHash: String): AutomationRule =
        AutomationRule.create(
            projectKey = "ATLAS",
            name = "인바운드 웹훅 룰",
            triggerType = TriggerType.WEBHOOK,
            createdBy = UUID.randomUUID(),
            webhookTokenHash = tokenHash,
            now = now,
        )

    /** 컨트롤러의 [com.bts.automation.adapter.web.AutomationWebhookController] 내부 해시와 동일 알고리즘. */
    private fun sha256Hex(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }

    private fun postWebhook(
        token: String,
        body: ByteArray,
    ): ResponseEntity<String> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val entity = HttpEntity(body, headers)
        return restTemplate.exchange(
            "/api/v1/automation/webhooks/$token",
            HttpMethod.POST,
            entity,
            String::class.java,
        )
    }

    /** [totalBytes] 정확히 그 바이트 수인 유효 JSON 본문을 만든다(prefix/suffix 는 ASCII 라 byte==char 길이). */
    private fun jsonPayloadOfSize(totalBytes: Int): String {
        val prefix = "{\"pad\":\""
        val suffix = "\"}"
        val padLength = totalBytes - prefix.toByteArray(Charsets.UTF_8).size - suffix.toByteArray(Charsets.UTF_8).size
        require(padLength >= 0) { "totalBytes($totalBytes) 가 prefix/suffix 보다 작습니다." }
        return prefix + "a".repeat(padLength) + suffix
    }

    // ── 유효 토큰 → 202 + enqueue ────────────────────────────────────────────

    @Test
    fun `유효 토큰 - 202 응답과 함께 q_automation_execution 에 payload 가 도달한다`() {
        val rawToken = "webhook-token-valid"
        val rule = webhookRule(sha256Hex(rawToken))
        repository.save(rule)
        val payload = """{"eventType":"external.event","value":42}"""

        val response = postWebhook(rawToken, payload.toByteArray(Charsets.UTF_8))

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        val message =
            jdbcTemplate.queryForObject(
                "SELECT message::text FROM pgmq.read('q_automation_execution', 30, 10) LIMIT 1",
                String::class.java,
            )
        val node = objectMapper.readTree(message)
        assertThat(node.get("ruleId").asText()).isEqualTo(rule.id.toString())
        assertThat(node.get("triggerType").asText()).isEqualTo("WEBHOOK")
        assertThat(node.get("triggerEvent").get("eventType").asText()).isEqualTo("external.event")
        assertThat(node.get("triggerEvent").get("value").asInt()).isEqualTo(42)
    }

    // ── 미존재/비활성/삭제 토큰 → 404 (S7/G5, 존재 숨김) ──────────────────────

    @Test
    fun `존재하지 않는 토큰 - 404`() {
        val response = postWebhook("nonexistent-token", "{}".toByteArray(Charsets.UTF_8))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `비활성 룰 토큰 - 404`() {
        val rawToken = "webhook-token-disabled"
        val rule = webhookRule(sha256Hex(rawToken))
        repository.save(rule)
        repository.update(rule.disable(now))

        val response = postWebhook(rawToken, "{}".toByteArray(Charsets.UTF_8))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `소프트 삭제된 룰 토큰 - 404`() {
        val rawToken = "webhook-token-deleted"
        val rule = webhookRule(sha256Hex(rawToken))
        repository.save(rule)
        repository.softDelete(rule.id, now)

        val response = postWebhook(rawToken, "{}".toByteArray(Charsets.UTF_8))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    // ── payload 크기 상한 (G2) ─────────────────────────────────────────────────

    @Test
    fun `payload 가 상한(256KB)을 초과하면 413 을 반환한다`() {
        val rawToken = "webhook-token-oversized"
        repository.save(webhookRule(sha256Hex(rawToken)))
        val oversized = jsonPayloadOfSize(MAX_PAYLOAD_BYTES + 1)

        val response = postWebhook(rawToken, oversized.toByteArray(Charsets.UTF_8))

        assertThat(response.statusCode).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
    }

    @Test
    fun `payload 가 정확히 상한(256KB)이면 통과해 202 를 반환한다`() {
        val rawToken = "webhook-token-boundary"
        repository.save(webhookRule(sha256Hex(rawToken)))
        val boundary = jsonPayloadOfSize(MAX_PAYLOAD_BYTES)

        val response = postWebhook(rawToken, boundary.toByteArray(Charsets.UTF_8))

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
    }

    // ── 유효하지 않은 JSON 본문 ───────────────────────────────────────────────

    @Test
    fun `본문이 유효한 JSON 이 아니면 400 을 반환한다`() {
        val rawToken = "webhook-token-invalid-json"
        repository.save(webhookRule(sha256Hex(rawToken)))

        val response = postWebhook(rawToken, "not-json-at-all{{{".toByteArray(Charsets.UTF_8))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    private companion object {
        /** [com.bts.automation.adapter.web.AutomationWebhookController] 의 상한과 동일(G2). */
        const val MAX_PAYLOAD_BYTES = 256 * 1024
    }
}
