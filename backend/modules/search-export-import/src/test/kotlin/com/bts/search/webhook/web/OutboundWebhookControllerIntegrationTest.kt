// 아웃바운드 webhook 구독 CRUD 컨트롤러 풀스택 통합테스트 — 컨트롤러→서비스→jOOQ→실 DB + SYSTEM_ADMIN 게이트·secret 미노출·예외 message 누출 차단 (FR-API-03 PR2)

package com.bts.search.webhook.web

import com.bts.search.jooq.tables.references.OUTBOUND_WEBHOOKS
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/**
 * 아웃바운드 webhook 구독 CRUD 컨트롤러 풀스택 통합테스트.
 *
 * 컨트롤러 → 서비스 → jOOQ Repository → **실 PostgreSQL(Testcontainers)** end-to-end.
 * cross-BC 포트([com.bts.shared.permission.SystemPermissionResolver])는 search test-boot 에 실
 * 구현이 없으므로 [WebhookIntegrationConfig] 가 fail-closed stub 으로 대체하고, 테스트가 admin actor
 * UUID 를 명시 등록한다(fail-open 금지).
 *
 * ## 커버 (plan Task 7)
 * - **SYSTEM_ADMIN 게이트** — 비-admin 은 전 엔드포인트 403 (리소스 존재 여부 무관, 존재 probe 차단).
 * - **secret 위생** — POST 응답에 secret 원문 필드 없음, `hasSecret` 만. DB 에는 암호문(평문 아님) 저장.
 * - **예외 message 누출 차단** — 404/409 응답 detail 에 대상 식별자(UUID) 미포함(일반 메시지 치환).
 * - CRUD 라이프사이클(생성/목록/단건/전체교체/소프트삭제) · OCC 409 · 존재X 404 · SSRF 400 ·
 *   eventFilter 빈/미지 400 · version 누락 400 · 미인증 401.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [WebhookIntegrationConfig::class])
@WebAppConfiguration
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
    properties = [
        "bts.webhook-encryption.key=integration-test-webhook-encryption-key",
        "bts.webhook-encryption.salt=deadbeefcafef00d",
    ],
)
class OutboundWebhookControllerIntegrationTest {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var dsl: DSLContext

    @Autowired
    private lateinit var permissionResolver: WebhookIntegrationConfig.StubSystemPermissionResolver

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val admin = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000ad")
    private val nonAdmin = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b0")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        dsl.deleteFrom(OUTBOUND_WEBHOOKS).execute()
        permissionResolver.admins.clear()
        permissionResolver.admins.add(admin)
        authenticate(admin)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── SYSTEM_ADMIN 게이트 ─────────────────────────────────────────────────────

    @Test
    fun `비-SYSTEM_ADMIN 은 모든 엔드포인트에서 403`() {
        authenticate(nonAdmin) // admins 집합에 없음 → 전부 거부
        val someId = UUID.randomUUID()

        mockMvc
            .perform(post("/api/v1/webhooks").contentType(JSON).content(validCreateBody))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/api/v1/webhooks")).andExpect(status().isForbidden)
        mockMvc.perform(get("/api/v1/webhooks/$someId")).andExpect(status().isForbidden)
        mockMvc
            .perform(put("/api/v1/webhooks/$someId").contentType(JSON).content(validUpdateBody(0)))
            .andExpect(status().isForbidden)
        mockMvc.perform(delete("/api/v1/webhooks/$someId")).andExpect(status().isForbidden)
    }

    // ── secret 위생 + 생성 ──────────────────────────────────────────────────────

    @Test
    fun `POST 201 — 응답에 secret 원문 없음, hasSecret true, DB 에는 암호문 저장`() {
        val secret = "super-secret-signing-value-xyz"
        val body =
            """{"name":"주문 웹훅","url":"https://example.com/hook","eventFilter":["issue.created"],"secret":"$secret"}"""

        val json =
            mockMvc
                .perform(post("/api/v1/webhooks").contentType(JSON).content(body))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value("주문 웹훅"))
                .andExpect(jsonPath("$.hasSecret").value(true))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.secretEncrypted").doesNotExist())
                .andReturn()
                .response.contentAsString

        // 응답 본문 어디에도 평문 secret 이 노출되지 않는다.
        assertThat(json).doesNotContain(secret)

        // DB 에는 암호문이 저장되고 평문은 저장되지 않는다.
        val id = mapper.readValue<Map<String, Any?>>(json)["id"].toString()
        val stored = storedSecret(id)
        assertThat(stored).isNotNull()
        assertThat(stored).isNotEqualTo(secret)
    }

    @Test
    fun `GET 목록과 단건 조회`() {
        val id = createWebhook("조회 웹훅")

        mockMvc
            .perform(get("/api/v1/webhooks"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))

        mockMvc
            .perform(get("/api/v1/webhooks/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.hasSecret").value(false))
    }

    @Test
    fun `PUT 전체 교체 성공 — version 증가, secret 생략 시 기존 유지`() {
        val secret = "keep-me-secret-value"
        val id = createWebhookWithSecret("교체대상", secret)

        // secret 필드 생략 → 기존 암호문 유지, 나머지 전체 교체
        val body =
            """{"name":"교체됨","url":"https://example.com/hook2","eventFilter":["issue.transitioned"],"version":0,"enabled":false}"""

        mockMvc
            .perform(put("/api/v1/webhooks/$id").contentType(JSON).content(body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("교체됨"))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.hasSecret").value(true))

        // secret 을 생략했으므로 DB 암호문은 그대로 유지되고 평문으로 덮이지 않는다.
        val stored = storedSecret(id)
        assertThat(stored).isNotNull()
        assertThat(stored).isNotEqualTo(secret)
    }

    // ── OCC / 존재하지 않는 리소스 + message 누출 차단 ─────────────────────────────

    @Test
    fun `PUT stale version 409 — 응답 detail 에 식별자 미노출`() {
        val id = createWebhook("OCC 웹훅")

        val json =
            mockMvc
                .perform(put("/api/v1/webhooks/$id").contentType(JSON).content(validUpdateBody(99)))
                .andExpect(status().isConflict)
                .andReturn()
                .response.contentAsString

        // 서비스 예외 message 는 디버그용 UUID 를 포함하지만, 핸들러가 일반 메시지로 치환해
        // detail 에는 식별자가 노출되지 않는다(instance=요청 경로는 클라이언트 자신의 입력이라 별개).
        assertThat(errorDetail(json)).doesNotContain(id)
    }

    @Test
    fun `PUT 존재하지 않는 id 404 — 응답 detail 에 식별자 미노출`() {
        val missing = UUID.randomUUID()

        val json =
            mockMvc
                .perform(put("/api/v1/webhooks/$missing").contentType(JSON).content(validUpdateBody(0)))
                .andExpect(status().isNotFound)
                .andReturn()
                .response.contentAsString

        assertThat(errorDetail(json)).doesNotContain(missing.toString())
    }

    @Test
    fun `GET 존재하지 않는 id 404 — 응답 detail 에 식별자 미노출`() {
        val missing = UUID.randomUUID()

        val json =
            mockMvc
                .perform(get("/api/v1/webhooks/$missing"))
                .andExpect(status().isNotFound)
                .andReturn()
                .response.contentAsString

        assertThat(errorDetail(json)).doesNotContain(missing.toString())
    }

    @Test
    fun `DELETE 204 후 조회 404`() {
        val id = createWebhook("삭제 웹훅")

        mockMvc.perform(delete("/api/v1/webhooks/$id")).andExpect(status().isNoContent)
        mockMvc.perform(get("/api/v1/webhooks/$id")).andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE 존재하지 않는 id 404`() {
        mockMvc.perform(delete("/api/v1/webhooks/${UUID.randomUUID()}")).andExpect(status().isNotFound)
    }

    // ── 입력 검증 (SSRF / eventFilter / version / 인증) ──────────────────────────

    @Test
    fun `SSRF 내부망 url 400`() {
        val body =
            """{"name":"내부","url":"http://127.0.0.1/hook","eventFilter":["issue.created"]}"""
        mockMvc
            .perform(post("/api/v1/webhooks").contentType(JSON).content(body))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `eventFilter 빈 배열 400`() {
        val body = """{"name":"x","url":"https://example.com/hook","eventFilter":[]}"""
        mockMvc
            .perform(post("/api/v1/webhooks").contentType(JSON).content(body))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `eventFilter 미지 이벤트 400`() {
        val body =
            """{"name":"x","url":"https://example.com/hook","eventFilter":["issue.exploded"]}"""
        mockMvc
            .perform(post("/api/v1/webhooks").contentType(JSON).content(body))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `name 공백 400`() {
        val body =
            """{"name":"   ","url":"https://example.com/hook","eventFilter":["issue.created"]}"""
        mockMvc
            .perform(post("/api/v1/webhooks").contentType(JSON).content(body))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `PUT version 누락 400`() {
        val id = createWebhook("버전없음")
        val body =
            """{"name":"x","url":"https://example.com/hook","eventFilter":["issue.created"]}"""
        mockMvc
            .perform(put("/api/v1/webhooks/$id").contentType(JSON).content(body))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `미인증 401`() {
        SecurityContextHolder.clearContext()
        mockMvc.perform(get("/api/v1/webhooks")).andExpect(status().isUnauthorized)
    }

    // ── private helpers ─────────────────────────────────────────────────────────

    private fun createWebhook(name: String): String {
        val body =
            """{"name":"$name","url":"https://example.com/hook","eventFilter":["issue.created"]}"""
        val json =
            mockMvc
                .perform(post("/api/v1/webhooks").contentType(JSON).content(body))
                .andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return mapper.readValue<Map<String, Any?>>(json)["id"].toString()
    }

    private fun createWebhookWithSecret(
        name: String,
        secret: String,
    ): String {
        val body =
            """{"name":"$name","url":"https://example.com/hook","eventFilter":["issue.created"],"secret":"$secret"}"""
        val json =
            mockMvc
                .perform(post("/api/v1/webhooks").contentType(JSON).content(body))
                .andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return mapper.readValue<Map<String, Any?>>(json)["id"].toString()
    }

    private fun errorDetail(json: String): String = mapper.readValue<Map<String, Any?>>(json)["detail"].toString()

    private fun storedSecret(id: String): String? =
        dsl
            .select(OUTBOUND_WEBHOOKS.SECRET_ENCRYPTED)
            .from(OUTBOUND_WEBHOOKS)
            .where(OUTBOUND_WEBHOOKS.ID.eq(UUID.fromString(id)))
            .fetchOne(OUTBOUND_WEBHOOKS.SECRET_ENCRYPTED)

    private fun authenticate(userId: UUID) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    private companion object {
        val JSON: MediaType = MediaType.APPLICATION_JSON

        const val VALID_URL = "https://example.com/hook"

        val validCreateBody =
            """{"name":"권한테스트","url":"$VALID_URL","eventFilter":["issue.created"]}"""

        fun validUpdateBody(version: Long) =
            """{"name":"권한테스트","url":"$VALID_URL","eventFilter":["issue.created"],"version":$version}"""
    }
}
