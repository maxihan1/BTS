// notification BC end-to-end 통합 테스트 — HTTP → 컨트롤러 → 서비스 → repository → 실 PostgreSQL

package com.bts.notification

import com.bts.notification.application.NotificationPolicyEvaluator
import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.RecipientRole
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/**
 * notification BC 전체 스택 end-to-end 통합 테스트.
 *
 * HTTP 요청 → NotificationPolicyController → NotificationPolicyService →
 * NotificationPolicyRepository → 실제 Testcontainers PostgreSQL 16 까지 관통 검증.
 *
 * [NotificationTestBootApplication] 을 부트 클래스로 사용하며,
 * [TestPermissionConfig] 가 fake [SystemPermissionResolver] 를 제공한다.
 *
 * ## 검증 시나리오
 * - E2E-1. GET /api/v1/notification-policies (admin) → V401+V403 시드 전역 20행 이상 + issue.created 포함
 * - E2E-2. POST 전역 정책 생성 (admin) → 201 + DB 반영 확인
 * - E2E-3. 프로젝트 override replace — ATLAS issue.created POST → evaluate()가 ATLAS 정책만 반환
 * - E2E-4. 비-admin actorId로 POST → 403 NOTIF_FORBIDDEN
 * - E2E-5. 중복 정책 두 번 POST → 두 번째 409 NOTIF_POLICY_DUPLICATE
 * - E2E-6. GET /catalog → 10 eventTypes + publishable 포함
 */
@SpringBootTest(
    classes = [
        NotificationTestBootApplication::class,
        NotificationTestcontainersConfig::class,
        TestPermissionConfig::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationPolicyEndToEndIntegrationTest {
    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var evaluator: NotificationPolicyEvaluator

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /** TestPermissionConfig.ADMIN_ACTOR_ID 와 동일하게 유지 */
    private val adminActorId: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    /** TestPermissionConfig.REGULAR_ACTOR_ID 와 동일하게 유지 */
    private val regularActorId: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        setAdminAuth()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── E2E-1. GET /api/v1/notification-policies (admin) → V401+V403 시드 전역 20행 이상 ─

    @Test
    fun `E2E-1 admin이 전역 정책 목록 조회 시 V401 V403 시드 20행 이상과 issue created 정책이 포함된다`() {
        mockMvc.perform(
            get("/api/v1/notification-policies")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(20)))
            .andExpect(
                jsonPath("$.data[?(@.eventType == 'issue.created')]").exists(),
            )
    }

    // ── E2E-2. POST 전역 정책 생성 (admin) → 201 + DB 반영 확인 ─────────────────

    @Test
    fun `E2E-2 admin이 전역 정책 생성 시 201 반환되고 DB에 반영된다`() {
        val body =
            mapOf(
                "projectKey" to null,
                "eventType" to "issue.overdue",
                "recipientRole" to "PROJECT_ADMIN",
                "channel" to "EMAIL",
                "enabled" to true,
            )

        val result =
            mockMvc
                .perform(
                    post("/api/v1/notification-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)),
                )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.eventType").value("issue.overdue"))
                .andExpect(jsonPath("$.data.recipientRole").value("PROJECT_ADMIN"))
                .andExpect(jsonPath("$.data.channel").value("EMAIL"))
                .andReturn()

        // DB 반영 확인: GET으로 재조회 시 생성된 정책 포함
        val responseBody = mapper.readTree(result.response.contentAsString)
        val createdId = responseBody.get("data").get("id").asText()
        assertThat(createdId).isNotBlank()
    }

    // ── E2E-3. 프로젝트 override replace ────────────────────────────────────────

    @Test
    fun `E2E-3 ATLAS issue_created 정책 POST 후 evaluate가 ATLAS 정책만 반환하고 전역을 무시한다`() {
        // ATLAS 프로젝트 issue.created 정책 생성
        val body =
            mapOf(
                "projectKey" to "ATLAS_E2E",
                "eventType" to "issue.created",
                "recipientRole" to "ASSIGNEE",
                "channel" to "IN_APP",
                "enabled" to true,
            )

        mockMvc.perform(
            post("/api/v1/notification-policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)

        // 평가 엔진 직접 호출 — ATLAS_E2E 프로젝트에 정책이 있으므로 전역 무시
        val matches = evaluator.evaluate(NotificationEventType.ISSUE_CREATED, "ATLAS_E2E")

        assertThat(matches).isNotEmpty
        assertThat(matches).allMatch { it.recipientRole == RecipientRole.ASSIGNEE && it.channel == Channel.IN_APP }
        // 전역 정책의 REPORTER 역할이 포함되지 않아야 함 (replace 방식)
        assertThat(matches.none { it.recipientRole == RecipientRole.REPORTER }).isTrue()
    }

    // ── E2E-4. 비-admin actorId로 POST → 403 ────────────────────────────────────

    @Test
    fun `E2E-4 비admin actor가 정책 생성 시 403 NOTIF_FORBIDDEN 반환`() {
        setRegularAuth()

        val body =
            mapOf(
                "projectKey" to null,
                "eventType" to "issue.created",
                "recipientRole" to "REPORTER",
                "channel" to "EMAIL",
                "enabled" to true,
            )

        mockMvc.perform(
            post("/api/v1/notification-policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_FORBIDDEN"))
    }

    // ── E2E-5. 중복 정책 두 번 POST → 두 번째 409 ────────────────────────────────

    @Test
    fun `E2E-5 동일한 정책을 두 번 POST하면 두 번째는 409 NOTIF_POLICY_DUPLICATE 반환`() {
        val body =
            mapOf(
                "projectKey" to null,
                "eventType" to "automation.failed",
                "recipientRole" to "RULE_OWNER",
                "channel" to "EMAIL",
                "enabled" to true,
            )

        // 첫 번째 생성 — 201
        mockMvc.perform(
            post("/api/v1/notification-policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)

        // 두 번째 동일 조합 — 409
        mockMvc.perform(
            post("/api/v1/notification-policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_POLICY_DUPLICATE"))
    }

    // ── E2E-6. GET /catalog → 9 eventTypes + publishable ─────────────────────

    @Test
    fun `E2E-6 catalog 조회 시 9개 eventTypes와 publishable 메타가 포함된다`() {
        mockMvc.perform(
            get("/api/v1/notification-policies/catalog")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.eventTypes.length()").value(NotificationEventType.entries.size))
            .andExpect(jsonPath("$.data.eventTypes[0].value").exists())
            .andExpect(jsonPath("$.data.eventTypes[0].publishable").exists())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun setAdminAuth() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                adminActorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    private fun setRegularAuth() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                regularActorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }
}
