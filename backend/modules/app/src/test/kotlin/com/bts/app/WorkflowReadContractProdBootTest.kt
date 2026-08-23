// GET /api/v1/workflows/{key} 읽기 응답의 계약을 9-BC prod 조립 실응답으로 못박는 테스트 (FR-WF-05 N1)

package com.bts.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * `GET /api/v1/workflows/{key}` **읽기 응답 계약 스냅샷** (FR-WF-05 Task 9 · spec N1).
 *
 * ## ★★ 이 테스트가 깨지면 프론트가 깨진다
 * 여기 적힌 필드 이름·개수·nullability 가 곧 `apps/web` 이 파싱하는 형태다. 이 테스트를 초록으로
 * 되돌리려고 **단언을 고치는 것은 계약을 바꾸는 것**이고, 그 순간 프론트는 배포 전까지 아무 경고
 * 없이 어긋난 채로 남는다. 빨간불이 켜지면 순서는 하나뿐이다 —
 * ① 백엔드 변경이 의도된 계약 변경인지 먼저 정한다 ② 의도됐다면 프론트 스키마를 **같은 PR 에서**
 * 함께 고친다 ③ 의도되지 않았다면 백엔드를 되돌린다. 단언을 먼저 고치는 선택지는 없다.
 *
 * ## ★ 왜 MVC 슬라이스가 아니라 `:modules:app` 조립인가
 * 형제 `WorkflowControllerMvcTest` 는 `@EnableWebMvc`(:82)로 MVC 를 직접 구성하고
 * `ObjectMapper().registerKotlinModule()`(:128, **`JavaTimeModule` 없음**)로 본문을 만든다.
 * 그 옆에 계약 테스트를 두면 **조립 앱과 다른 직렬화**를 계약으로 박제하게 되고, 프론트 Zod 가
 * 그 틀린 형식에 맞춰지면 프로덕션이 깨진다(같은 사고가 `LocalDate` 가 배열로 나간 형태로 1회).
 * 그래서 이 계약은 prod 프로파일 + 실 Tomcat 조립 컨텍스트의 **실응답**으로만 만든다.
 *
 * ## 조립 테스트 3규약 ([ProjectCreatePermissionProdBootTest] 가 정본)
 * ① 인증은 **PAT Bearer** — JWT 면 `MfaEnrollmentGateFilter` 가 관리자 endpoint 를 전부 403 으로
 * 잘라 기능 파손으로 오진한다. ② 베이스의 `TestRestTemplate` 대신 JDK [HttpClient] —
 * `:modules:app` 에 HttpComponents5 가 없어 401/403 본문을 못 읽는다. ③ [ProdAssemblyHttpTestBase]
 * 를 **상속만** 한다 — `@SpringBootTest`·`@ActiveProfiles`·`@DynamicPropertySource` 를 자체 선언하면
 * 컨텍스트 캐시 키가 갈려 9-BC 컨텍스트가 2회 부팅되고 `@Scheduled` 워커가 2벌이 된다.
 *
 * ## 픽스처는 쓰기 API 로 만든다 (spec N6)
 * 워크플로우·상태·전환을 원시 SQL 로 심지 않는다. Task 8 이 연 쓰기 API 로 만들고 읽기 API 로
 * 되읽어, 두 API 의 전환 표현이 실제로 같은지까지 한 번에 대조한다(쓰기 응답의 `id` == 읽기 응답의 `id`).
 * prod 프로파일이라 워크플로우 정의 편집은 SYSTEM_ADMIN 만 가능하다
 * (`IdentityAccessWorkflowDefinitionPermissionResolver`).
 *
 * ## 사전 조건
 * dev postgres 기동 — `docker compose -f infra/docker-compose.dev.yml up -d postgres` (5433).
 */
class WorkflowReadContractProdBootTest : ProdAssemblyHttpTestBase() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    /**
     * Spring MVC 가 응답 본문을 쓸 때 실제로 쓰는 컨버터. 이 컨버터의 `objectMapper` 가 곧 위
     * 세 테스트가 관측한 JSON 을 만든 주체라, `timestamp` 가드가 이것을 직접 겨눈다.
     */
    @Autowired
    private lateinit var jacksonConverter: MappingJackson2HttpMessageConverter

    /** 응답 본문 파싱 전용. 계약 판정은 조립 응답 문자열이 하고 이 mapper 는 읽기만 한다. */
    private val parser = ObjectMapper()

    private val client: HttpClient =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    private lateinit var normalTransitionId: String
    private lateinit var globalTransitionId: String

    @BeforeEach
    fun cleanupAndSeed() {
        cleanup()
        jdbc.update("INSERT INTO users (id, username) VALUES (?, ?)", USER_ID, USERNAME)
        jdbc.update(
            "INSERT INTO personal_access_tokens (id, user_id, name, token_hash, expires_at) " +
                "VALUES (?, ?, ?, ?, NOW() + INTERVAL '1 hour')",
            UUID.randomUUID(),
            USER_ID,
            "wf-read-contract-pat",
            sha256Hex(RAW_TOKEN),
        )
        jdbc.update("INSERT INTO system_role_assignments (user_id, role) VALUES (?, 'SYSTEM_ADMIN')", USER_ID)

        val created =
            send(
                "POST",
                "/api/v1/workflows",
                """
                {"key":"$WORKFLOW_KEY","name":"WFRC Contract Workflow","description":"read contract fixture",
                 "statuses":[
                   {"key":"$TODO_KEY","name":"WFRC Contract Todo","category":"TODO","displayOrder":0},
                   {"key":"$DONE_KEY","name":"WFRC Contract Done","category":"DONE","displayOrder":1}]}
                """.trimIndent(),
            )
        assertThat(created.statusCode()).`as`("픽스처 워크플로우 생성 실패: ${created.body()}").isEqualTo(HTTP_CREATED)

        normalTransitionId =
            createTransition(
                """{"fromStatusKey":"$TODO_KEY","toStatusKey":"$DONE_KEY","name":"$NORMAL_NAME","kind":"NORMAL"}""",
            )
        globalTransitionId =
            createTransition("""{"toStatusKey":"$TODO_KEY","name":"$GLOBAL_NAME","kind":"GLOBAL"}""")
    }

    @AfterEach
    fun tearDown() {
        cleanup()
    }

    @Test
    fun `GET workflows-key 응답 최상위 키가 정확히 key name description states transitions 5개다`() {
        val envelope = readWorkflow()

        // 봉투는 data 하나뿐이다 — 성공 응답 포맷(DEVELOPMENT.md §응답 포맷)의 정본.
        assertThat(envelope.fieldNames().asSequence().toList()).containsExactly("data")
        // ★ 「이 키들이 있다」가 아니라 「정확히 이 5개다」. 포함만 보면 필드가 늘어도 통과해
        //   계약이 새는 것을 못 잡는다 — 프론트 Zod 의 .strict() 가 그 순간 런타임에서 터진다.
        assertThat(envelope.path("data").fieldNames().asSequence().toList())
            .`as`("읽기 응답 형태 불변(N1) — 필드 추가·삭제·개명은 전부 계약 변경이다")
            .containsExactlyInAnyOrder("key", "name", "description", "states", "transitions")
    }

    @Test
    fun `transitions 원소에 id 와 kind 가 추가됐고 기존 필드는 그대로다`() {
        val transitions = readWorkflow().path("data").path("transitions")
        val normal = transitions.first { it.path("name").asText() == NORMAL_NAME }

        // 추가된 두 필드 + 종전 네 필드. 쓰기 API(TransitionResponse)와 같은 6개라 프론트가 전환
        // 스키마를 하나만 유지한다.
        assertThat(normal.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("id", "key", "kind", "fromStateKey", "toStateKey", "name")
        // id 는 쓰기 응답이 준 값과 같아야 한다 — 두 API 가 같은 전환을 같은 이름으로 부른다는 증거.
        assertThat(normal.path("id").asText()).isEqualTo(normalTransitionId)
        assertThat(normal.path("kind").asText()).isEqualTo("NORMAL")
        // 기존 4필드는 이름도 형태도 그대로다 (N1 — 삭제·개명 0).
        assertThat(normal.path("key").asText()).isEqualTo("${TODO_KEY}__$DONE_KEY")
        assertThat(normal.path("fromStateKey").asText()).isEqualTo(TODO_KEY)
        assertThat(normal.path("toStateKey").asText()).isEqualTo(DONE_KEY)
        assertThat(normal.path("name").asText()).isEqualTo(NORMAL_NAME)
    }

    @Test
    fun `GLOBAL 전환의 fromStateKey 는 null 로 직렬화된다`() {
        val transitions = readWorkflow().path("data").path("transitions")
        val global = transitions.first { it.path("name").asText() == GLOBAL_NAME }

        assertThat(global.path("id").asText()).isEqualTo(globalTransitionId)
        assertThat(global.path("kind").asText()).isEqualTo("GLOBAL")
        // ★ G4 — 필드가 **빠지는 것**이 아니라 **null 로 실린다**. 프론트 Zod 가 이 필드를
        //   z.string() 으로 강제하고 있으면 깨지므로 다음 PR 이 nullable 로 완화해야 한다.
        //   has() 없이 asText() 만 보면 필드 부재도 통과해 그 구분을 못 한다.
        assertThat(global.has("fromStateKey")).`as`("fromStateKey 는 생략되지 않는다").isTrue()
        assertThat(global.path("fromStateKey").isNull).`as`("출발지 없는 전환은 null 이다").isTrue()
        assertThat(global.path("key").asText()).isEqualTo("GLOBAL__$TODO_KEY")
        assertThat(global.path("toStateKey").asText()).isEqualTo(TODO_KEY)
    }

    /**
     * ### 왜 HTTP 본문이 아니라 컨버터의 mapper 를 겨누나
     * 읽기 계약(`WorkflowDto`)에 **timestamp 필드가 0개**라 본문만 훑으면 아무것도 검사하지 않는
     * 공허한 테스트가 된다. 그래서 그 본문을 실제로 만든 주체 — Spring MVC 가 쓰는
     * [MappingJackson2HttpMessageConverter] 의 mapper — 를 직접 겨눈다. 이 계약에 timestamp 가
     * 처음 붙는 날 이 가드가 이미 서 있게 된다.
     */
    @Test
    fun `timestamp 계열 필드가 배열이 아니라 ISO 문자열이다`() {
        // ★ 이 테스트를 슬라이스가 아니라 조립에 둔 이유 자체의 회귀 가드다.
        //   JavaTimeModule 이 빠지거나 WRITE_DATES_AS_TIMESTAMPS 가 켜지면 LocalDate 가
        //   [2026,1,1] 배열로, Instant 가 숫자로 나간다 — 프론트가 그대로 깨진다.
        //   컨버터가 들고 있는 mapper 를 직접 겨눠, 위 세 테스트가 읽은 본문과 같은 주체임을 못박는다.
        val probe = TimestampProbe(Instant.parse("2026-01-01T00:00:00Z"), LocalDate.of(2026, 1, 1), OFFSET_PROBE)

        val json = parser.readTree(jacksonConverter.objectMapper.writeValueAsString(probe))

        assertThat(json.path("instant").isTextual).`as`("Instant 가 숫자로 나가면 안 된다").isTrue()
        assertThat(json.path("instant").asText()).isEqualTo("2026-01-01T00:00:00Z")
        assertThat(json.path("localDate").isArray).`as`("LocalDate 가 배열로 나간 실사고가 있었다").isFalse()
        assertThat(json.path("localDate").asText()).isEqualTo("2026-01-01")
        assertThat(json.path("offsetDateTime").isTextual).isTrue()
        assertThat(json.path("offsetDateTime").asText()).startsWith("2026-01-01T00:00:00")
    }

    // ── 픽스처 / HTTP ────────────────────────────────────────────────────────────

    /** 전환 정의를 1건 만들고 쓰기 응답이 준 `id` 를 돌려준다. 201 이 아니면 본문째로 드러낸다. */
    private fun createTransition(body: String): String {
        val response = send("POST", "/api/v1/workflows/$WORKFLOW_KEY/transitions", body)
        assertThat(response.statusCode()).`as`("픽스처 전환 생성 실패: ${response.body()}").isEqualTo(HTTP_CREATED)
        return parser.readTree(response.body()).path("data").path("id").asText()
    }

    /** 읽기 응답 본문을 봉투째로 돌려준다. 200 이 아니면 본문째로 드러낸다. */
    private fun readWorkflow(): JsonNode {
        val response = send("GET", "/api/v1/workflows/$WORKFLOW_KEY", null)
        assertThat(response.statusCode()).`as`("읽기 응답이 200 이 아니다: ${response.body()}").isEqualTo(HTTP_OK)
        return parser.readTree(response.body())
    }

    /** PAT Bearer 로 1회 왕복한다. CSRF 토큰 불요(중앙 SecurityConfig 의 patBearerMatcher ignore). */
    private fun send(
        method: String,
        path: String,
        body: String?,
    ): HttpResponse<String> {
        val publisher =
            if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Authorization", "Bearer $RAW_TOKEN")
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
    }

    /**
     * 이 테스트가 심은 것만 지운다. workflows 삭제가 workflow_statuses·workflow_transitions 를,
     * users 삭제가 PAT·system_role_assignments 를 각각 ON DELETE CASCADE 로 함께 지운다.
     * 전역 상태 카탈로그(statuses)는 RESTRICT 라 편성이 사라진 뒤에 지운다.
     */
    private fun cleanup() {
        jdbc.update("DELETE FROM workflows WHERE key = ?", WORKFLOW_KEY)
        jdbc.update("DELETE FROM statuses WHERE key IN (?, ?)", TODO_KEY, DONE_KEY)
        jdbc.update("DELETE FROM users WHERE id = ?", USER_ID)
    }

    /** raw PAT → SHA-256 소문자 hex 64자 — PersonalAccessTokenService.sha256Hex 와 동일 알고리즘. */
    private fun sha256Hex(raw: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 조립 mapper 의 java.time 직렬화 형태만 재는 탐침. 프로덕션 DTO 가 아니다. */
    private data class TimestampProbe(
        val instant: Instant,
        val localDate: LocalDate,
        val offsetDateTime: OffsetDateTime,
    )

    private companion object {
        val USER_ID: UUID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        const val USERNAME = "wf-read-contract-system-admin"

        /** PAT raw token — PersonalAccessToken 의 prefix·body 길이를 미러링한다(그 companion 이 internal). */
        val RAW_TOKEN = "pat_" + "wfreadcontract".padEnd(48, '0')

        const val WORKFLOW_KEY = "wfrc-contract"
        const val TODO_KEY = "wfrc-todo"
        const val DONE_KEY = "wfrc-done"
        const val NORMAL_NAME = "wfrc-normal"
        const val GLOBAL_NAME = "wfrc-global"

        val OFFSET_PROBE: OffsetDateTime = OffsetDateTime.parse("2026-01-01T00:00:00Z")

        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
    }
}
