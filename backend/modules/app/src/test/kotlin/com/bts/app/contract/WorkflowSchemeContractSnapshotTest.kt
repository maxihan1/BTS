// 워크플로우 스킴 8 endpoint 응답을 prod 조립에서 계약 스냅샷으로 고정해 프론트 Zod drift 를 차단하는 테스트

package com.bts.app.contract

import com.bts.app.ProdAssemblyHttpTestBase
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode
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
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 워크플로우 스킴 **8 endpoint 의 응답 형태**를 `docs/contracts/workflow-schemes.snapshot.json` 한 장에
 * 고정하고, 조립 응답이 그 계약에서 벗어나면 실패시키는 테스트 (plan Task 1).
 *
 * 이 스냅샷이 이 PR 의 **유일한 계약 정본**이다. 백엔드는 이 테스트로 stale 을 검출하고, 프론트는
 * 같은 파일을 Zod `.strict()` 로 파싱한다(Task 2) — 양방향이 한 파일에서 닫힌다.
 *
 * ## ★ 왜 슬라이스가 아니라 조립인가 (리뷰 발견 1)
 * `WorkflowSchemeControllerTest` 는 `@EnableWebMvc` + `ObjectMapper().registerKotlinModule()`
 * (JavaTimeModule 부재) 구성이고, 여기서 다루는 [com.bts.workflow.scheme.web.AssignmentResponse] 는
 * `assignedAt: Instant` 를 갖는다. 슬라이스에서 스냅샷을 뜨면 `Instant` 가 숫자·배열로 직렬화된 채
 * 계약으로 **박제**되고, 프론트가 그 틀린 계약에 맞춰지면 프로덕션이 깨진다
 * ([[enablewebmvc-slice-localdate-array-serialization]] 가 이 저장소의 동일 사고를 기록).
 * 봉인 장치가 오염원이 되지 않도록 **실 Tomcat + prod 프로파일 조립**에서만 계약을 뜬다.
 *
 * ## 인증 — PAT Bearer (JWT 아님)
 * `Authorization: Bearer pat_…` 는 중앙 `SecurityConfig` 의 patBearerMatcher 로 CSRF-ignore 되고,
 * 비-Jwt principal 이라 `MfaEnrollmentGateFilter` 를 우회한다. SYSTEM_ADMIN 은 MFA 미등록 시 그
 * 게이트에서 403 이므로 JWT 로 짜면 8 endpoint 가 전부 403 으로 나와 **시드 오류를 계약 파손으로 오진**한다
 * (선례 `ProjectCreatePermissionProdBootTest` KDoc "인증 — PAT").
 *
 * ## [HttpClient] 를 쓰는 이유
 * 베이스의 `rest`(TestRestTemplate)는 :modules:app 에 Apache HttpComponents 5 가 없어
 * `HttpURLConnection` 으로 떨어지고 본문 있는 요청이 401/403 을 받으면 **본문을 못 읽는다**.
 * 이 테스트는 응답 **본문**을 모으는 것이 목적이라 그 함정에 정면으로 걸리므로 JDK 내장 클라이언트를 쓴다.
 *
 * ## 사전 조건
 * dev postgres 기동 — `docker compose -f infra/docker-compose.dev.yml up -d postgres` (5433).
 * 컨텍스트 캐시 공유를 위해 [ProdAssemblyHttpTestBase] 를 **상속만** 하고 `@SpringBootTest`·
 * `@ActiveProfiles`·`@DynamicPropertySource` 를 자체 선언하지 않는다(베이스 KDoc "webEnvironment는
 * 컨텍스트 캐시 키의 일부다" — 갈리면 9-BC prod 컨텍스트가 2회 부팅되고 `@Scheduled` 워커 2벌이
 * 같은 pgmq 큐를 동시 폴링한다).
 *
 * ## 스냅샷 값 규칙 (plan D-6)
 * leaf 값은 [canonical] 로 **타입별 표준값**에 치환된다(숫자 0 · 불리언 true · UUID/instant/그 외
 * 문자열 각각 고정값 · null 은 보존 · 배열은 중복제거+정렬 · 객체 키는 사전순). 자동증가 id·실행
 * 시각·환경별 행 수에 무관하게 바이트 동일해지므로 문자열 동등 비교가 성립한다. 타입은 보존되므로
 * 프론트 Zod 파싱은 그대로 유효하고, 필드명 오타·누락·추가는 그대로 검출된다.
 */
class WorkflowSchemeContractSnapshotTest : ProdAssemblyHttpTestBase() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val mapper = ObjectMapper()

    /** SYSTEM_ADMIN(Global 축) + PROJECT_ADMIN 멤버십(Project 축)을 한 행위자에 몰아 8 endpoint 를 전부 연다. */
    @BeforeEach
    fun cleanupAndSeed() {
        cleanup()
        jdbc.update("INSERT INTO users (id, username, display_name) VALUES (?, ?, ?)", USER_ID, USERNAME, USERNAME)
        jdbc.update(
            "INSERT INTO personal_access_tokens (id, user_id, name, token_hash, expires_at) " +
                "VALUES (?, ?, ?, ?, NOW() + INTERVAL '1 hour')",
            UUID.randomUUID(),
            USER_ID,
            "wfscheme-contract-snapshot-pat",
            sha256Hex(RAW_TOKEN),
        )
        jdbc.update("INSERT INTO system_role_assignments (user_id, role) VALUES (?, 'SYSTEM_ADMIN')", USER_ID)
        jdbc.update("INSERT INTO projects (id, key, name) VALUES (?, ?, ?)", PROJECT_ID, PROJECT_KEY, "계약 스냅샷 프로젝트")
        jdbc.update(
            "INSERT INTO project_memberships (project_id, user_id, role) VALUES (?, ?, 'PROJECT_ADMIN')",
            PROJECT_ID,
            USER_ID,
        )
    }

    @AfterEach
    fun tearDown() {
        cleanup()
    }

    @Test
    fun `계약 스냅샷이 실제 조립 응답과 일치한다`() {
        val pretty = prettyPrint(canonical(collectRawResponses()))

        if (System.getProperty(UPDATE_FLAG) == "true") {
            SNAPSHOT_PATH.parent.createDirectories()
            SNAPSHOT_PATH.writeText(pretty)
        }

        assertThat(SNAPSHOT_PATH)
            .`as`("계약 스냅샷 부재. -D$UPDATE_FLAG=true 로 생성할 것 — $SNAPSHOT_PATH")
            .exists()
        assertThat(SNAPSHOT_PATH.readText())
            .`as`("조립 응답이 계약 스냅샷과 다르다. 의도한 변경이면 -D$UPDATE_FLAG=true 로 재생성하고 프론트 Zod 도 함께 고칠 것")
            .isEqualTo(pretty)
    }

    @Test
    fun `isStandard 극성이 뒤집히지 않는다 (정규화가 지우는 축의 가드)`() {
        // D-6 정규화는 모든 불리언을 true 로 만든다. 그래서 `isStandard = !scheme.isDefault` 로
        // 뒤집어도 스냅샷은 바이트 동일이고 프론트 계약 테스트도 초록이다(독립 리뷰 M4).
        // 값 축은 스냅샷이 못 지키므로 여기서 원본 응답으로 직접 못박는다.
        // ★ 조립 지점이 3개다 — 목록은 WorkflowSchemeApplicationService.toListItemResponse,
        // 생성·수정은 WorkflowSchemeResponse.from, 상세는 WorkflowSchemeDetailResponse.from.
        // 목록만 보면 나머지 두 곳의 극성 뒤집힘을 놓친다(자체 뮤테이션으로 실증).
        val createdBody = send("POST", SCHEMES, CREATE_BODY, 201).path("data")
        val list = send("GET", SCHEMES, null, 200).path("data")
        val createdDetail = send("GET", "$SCHEMES/$SCHEME_KEY", null, 200).path("data")
        val standardDetail = send("GET", "$SCHEMES/$STANDARD_SCHEME_KEY", null, 200).path("data")

        // 사용자가 만든 스킴은 표준이 아니고, V201 이 시드한 스킴은 표준이다.
        assertPolarity(createdBody, expected = false, where = "생성 응답")
        assertPolarity(list.first { it.path("key").textValue() == SCHEME_KEY }, expected = false, where = "목록(생성 스킴)")
        assertPolarity(
            list.first { it.path("key").textValue() == STANDARD_SCHEME_KEY },
            expected = true,
            where = "목록(표준 스킴)",
        )
        assertPolarity(createdDetail, expected = false, where = "상세(생성 스킴)")
        assertPolarity(standardDetail, expected = true, where = "상세(표준 스킴)")
    }

    private fun assertPolarity(
        node: JsonNode,
        expected: Boolean,
        where: String,
    ) {
        assertThat(node.path("isStandard").booleanValue())
            .`as`("$where 의 isStandard 극성이 뒤집혔다 — 정규화된 스냅샷은 이 회귀를 못 잡는다")
            .isEqualTo(expected)
    }

    @Test
    fun `배정 응답의 assignedAt 이 ISO-8601 문자열이다 (리뷰 발견 1 회귀 가드)`() {
        val body = """{"schemeKey":"$STANDARD_SCHEME_KEY"}"""
        val assignResult = send("PUT", "$PROJECTS/$PROJECT_KEY/workflow-scheme", body, 200)

        val assignedAt = assignResult.path("data").path("assignedAt")

        // 숫자·배열이면 조립 ObjectMapper 가 JavaTimeModule 없이 직렬화했다는 뜻 —
        // 그 상태로 스냅샷을 뜨면 틀린 계약이 박제되고 프론트가 그쪽에 맞춰져 프로덕션이 깨진다.
        assertThat(assignedAt.isTextual)
            .`as`("assignedAt 이 JSON 문자열이 아니다(실제: $assignedAt). 조립 직렬화 설정이 깨졌다 — 중단하고 보고할 것")
            .isTrue()
        assertThat(assignedAt.textValue()).matches(INSTANT_REGEX.pattern)
    }

    // ── endpoint 수집 ────────────────────────────────────────────────────────────

    /**
     * 8 endpoint 를 실 HTTP 로 왕복해 endpoint 라벨 → 원본 응답 본문 맵을 만든다.
     *
     * 라벨은 Task 2 프론트 테스트의 `CASES` 키와 **문자열이 같아야** 한다. 각 호출은 기대 상태코드를
     * 선단정하므로, 시드 누락으로 403 이 나면 그 본문이 계약으로 박제되지 않고 그 자리에서 실패한다.
     */
    private fun collectRawResponses(): Map<String, JsonNode> {
        val scheme = "$SCHEMES/$SCHEME_KEY"
        val project = "$PROJECTS/$PROJECT_KEY"

        val created = send("POST", SCHEMES, CREATE_BODY, 201)
        val mappingCreated = send("POST", "$scheme/mappings", TYPED_MAPPING_BODY, 200)
        // 기본 매핑(issueTypeKey=null) 도 하나 심어 mappings[] 의 nullable 변형을 계약에 남긴다.
        send("POST", "$scheme/mappings", DEFAULT_MAPPING_BODY, 200)
        val detail = send("GET", scheme, null, 200)
        val updated = send("PUT", scheme, UPDATE_BODY, 200)
        val list = send("GET", SCHEMES, null, 200)
        val assignResult = send("PUT", "$project/workflow-scheme", ASSIGN_BODY, 200)
        val assignedScheme = send("GET", "$project/workflow-scheme", null, 200)
        val assignable = send("GET", "$project/assignable-workflow-schemes", null, 200)

        return mapOf(
            "POST /api/v1/workflow-schemes" to created,
            "GET /api/v1/workflow-schemes" to list,
            "GET /api/v1/workflow-schemes/{key}" to detail,
            "PUT /api/v1/workflow-schemes/{key}" to updated,
            "POST /api/v1/workflow-schemes/{key}/mappings" to mappingCreated,
            "PUT /api/v1/projects/{k}/workflow-scheme" to assignResult,
            "GET /api/v1/projects/{k}/workflow-scheme" to assignedScheme,
            "GET /api/v1/projects/{k}/assignable-workflow-schemes" to assignable,
        )
    }

    /** PAT Bearer 로 1회 왕복하고 기대 상태코드를 단정한 뒤 본문을 JSON 으로 판다. */
    private fun send(
        method: String,
        path: String,
        body: String?,
        expectedStatus: Int,
    ): JsonNode {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Authorization", "Bearer $RAW_TOKEN")
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build()
        val response =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
                .send(request, HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode())
            .`as`("$method $path 가 $expectedStatus 이 아니다. 본문: ${response.body()}")
            .isEqualTo(expectedStatus)
        return mapper.readTree(response.body())
    }

    // ── 스냅샷 정규화 (plan D-6) ───────────────────────────────────────────────────

    /**
     * endpoint 라벨 맵 전체를 정규화한다. 라벨도 사전순으로 정렬해 삽입 순서에 의존하지 않게 만든다.
     *
     * 선두에 `$comment` 를 박는다 — JSON 은 주석을 못 달아서, 파일만 열어본 사람이 실패를 보고
     * 값을 손으로 고쳐 **계약을 조용히 위조**하는 것을 막을 안내가 파일 안에 없기 때문이다.
     * Task 2 는 endpoint 라벨별로 파싱하므로 이 루트 키는 프론트 `.strict()` 검증에 영향을 주지 않는다.
     */
    private fun canonical(raw: Map<String, JsonNode>): JsonNode {
        val root = JsonNodeFactory.instance.objectNode()
        root.put("\$comment", SNAPSHOT_NOTE)
        raw.keys.sorted().forEach { root.set<JsonNode>(it, canonicalNode(raw.getValue(it))) }
        return root
    }

    /** leaf 를 타입별 표준값으로 치환한다 — 값 변동은 지우고 **타입·필드명·nullability 는 보존**한다. */
    private fun canonicalNode(node: JsonNode): JsonNode =
        when {
            node.isObject -> canonicalObject(node)
            node.isArray -> canonicalArray(node)
            node.isNull -> NullNode.instance
            node.isBoolean -> BooleanNode.TRUE
            node.isNumber -> IntNode(CANONICAL_NUMBER)
            node.isTextual -> canonicalText(node.textValue())
            else -> TextNode(CANONICAL_STRING)
        }

    private fun canonicalObject(node: JsonNode): JsonNode {
        val out = JsonNodeFactory.instance.objectNode()
        node.fieldNames().asSequence().sorted().forEach { out.set<JsonNode>(it, canonicalNode(node.get(it))) }
        return out
    }

    /** 원소를 정규화한 뒤 중복 제거 + 정렬 — 행 수·정렬 순서가 환경마다 달라도 스냅샷이 흔들리지 않는다. */
    private fun canonicalArray(node: JsonNode): JsonNode {
        val out = JsonNodeFactory.instance.arrayNode()
        node
            .map { canonicalNode(it) }
            .distinctBy { it.toString() }
            .sortedBy { it.toString() }
            .forEach { out.add(it) }
        return out
    }

    private fun canonicalText(value: String): TextNode =
        when {
            UUID_REGEX.matches(value) -> TextNode(CANONICAL_UUID)
            INSTANT_REGEX.matches(value) -> TextNode(CANONICAL_INSTANT)
            else -> TextNode(CANONICAL_STRING)
        }

    /** 들여쓰기를 OS 무관하게 고정한다 — 시스템 개행을 쓰면 플랫폼마다 파일이 달라진다. */
    private fun prettyPrint(node: JsonNode): String {
        val indenter = DefaultIndenter("  ", "\n")
        val printer = DefaultPrettyPrinter().withObjectIndenter(indenter).withArrayIndenter(indenter)
        return mapper.writer(printer).writeValueAsString(node) + "\n"
    }

    // ── 정리 ────────────────────────────────────────────────────────────────────

    /**
     * 이 테스트가 심은 것만 지운다. 배정 → 스킴 → 프로젝트 → 사용자 **순서가 강제**다 —
     * `project_workflow_scheme_assignments` 가 `workflow_schemes` 를 `ON DELETE RESTRICT` 로 잡는다.
     * users 삭제가 PAT·system_role_assignments·project_memberships 를 CASCADE 로 함께 지운다.
     */
    private fun cleanup() {
        jdbc.update("DELETE FROM project_workflow_scheme_assignments WHERE project_id = ?", PROJECT_ID)
        jdbc.update("DELETE FROM workflow_schemes WHERE key = ?", SCHEME_KEY)
        jdbc.update("DELETE FROM projects WHERE key = ?", PROJECT_KEY)
        jdbc.update("DELETE FROM users WHERE id = ?", USER_ID)
    }

    /** raw PAT → SHA-256 소문자 hex 64자 — PersonalAccessTokenService 와 동일 알고리즘(prefix 포함 전체). */
    private fun sha256Hex(raw: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SCHEMES = "/api/v1/workflow-schemes"
        const val PROJECTS = "/api/v1/projects"

        /** 스냅샷 재생성 스위치 — 손으로 편집하지 말고 이 시스템 프로퍼티로 재생성한다. */
        const val UPDATE_FLAG = "contract.snapshot.update"

        /** 스냅샷 파일 선두에 박히는 안내 — JSON 에 주석을 달 수 없어 데이터로 넣는다. */
        const val SNAPSHOT_NOTE =
            "자동 생성 파일 — 손으로 편집하지 말 것. " +
                "재생성: cd backend && ./gradlew :modules:app:test " +
                "--tests '*WorkflowSchemeContractSnapshotTest*' -Dcontract.snapshot.update=true. " +
                "값은 타입별 표준값으로 정규화돼 있다(plan D-6) — 계약은 필드명·타입·nullability 이지 값이 아니다."

        val USER_ID: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val PROJECT_ID: UUID = UUID.fromString("55555555-5555-4555-8555-555555555555")
        const val USERNAME = "wfscheme-contract-snapshot-admin"

        /** PAT prefix·본문 길이는 실 발급 형상과 맞춘다(중앙 patBearerMatcher 가 `Bearer pat_` 로 CSRF-ignore). */
        val RAW_TOKEN = "pat_" + "wfsnap".padEnd(48, '0')

        /** `^[A-Z][A-Z0-9]{1,9}$` 를 만족하는 전용 키 — 다른 조립 테스트와 섞이지 않게 좁힌다. */
        const val PROJECT_KEY = "WFSNAP"

        /** `^[a-z][a-z0-9-]{1,29}$` 를 만족하는 전용 스킴 키. */
        const val SCHEME_KEY = "contract-snapshot-scheme"

        /** V201 이 시드하는 표준 스킴 — 회귀 가드 테스트가 스킴 생성 없이 배정만 할 때 쓴다. */
        const val STANDARD_SCHEME_KEY = "software-scheme"

        // 요청 본문 — 호출부 줄 길이를 detekt MaxLineLength(120) 아래로 유지하려고 상수로 뺀다.
        const val CREATE_BODY = """{"key":"$SCHEME_KEY","name":"계약 스냅샷 스킴","description":"contract"}"""
        const val TYPED_MAPPING_BODY = """{"issueTypeKey":"task","workflowKey":"simple"}"""
        const val DEFAULT_MAPPING_BODY = """{"issueTypeKey":null,"workflowKey":"software-default"}"""
        const val UPDATE_BODY = """{"name":"계약 스냅샷 스킴 v2","description":null}"""
        const val ASSIGN_BODY = """{"schemeKey":"$SCHEME_KEY"}"""

        val UUID_REGEX = Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")
        val INSTANT_REGEX = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$""")

        /**
         * 숫자 표준값 — **0 이 아니라 1** 이다.
         *
         * id·schemeId 는 BIGSERIAL 이라 실값이 1 부터 시작하고, 프론트 Zod 가 그 불변식을
         * `z.number().int().positive()` 로 못박고 있다. 0 을 쓰면 계약 테스트가 어휘 불일치가 아니라
         * **값 제약**으로 실패해, 형태 검증에 값 검증이 섞인다. 1 은 positive·nonnegative·nullable
         * 제약을 모두 만족하는 중립값이면서 실제 데이터에 더 가깝다.
         */
        const val CANONICAL_NUMBER = 1

        const val CANONICAL_STRING = "string"
        const val CANONICAL_UUID = "00000000-0000-4000-8000-000000000000"
        const val CANONICAL_INSTANT = "2026-01-01T00:00:00Z"

        /**
         * repo 루트의 계약 정본. Gradle 테스트 CWD 는 `backend/modules/app` 이라 `..` 상대경로는
         * repo 루트가 아니다 — `docs/` 와 `CLAUDE.md` 를 함께 가진 상위 디렉토리를 찾아 기준으로 삼는다.
         */
        val SNAPSHOT_PATH: Path = repoRoot().resolve("docs/contracts/workflow-schemes.snapshot.json")

        private fun repoRoot(): Path {
            val start = Path.of("").toAbsolutePath()
            var dir: Path? = start
            while (dir != null) {
                if (Files.isDirectory(dir.resolve("docs")) && Files.isRegularFile(dir.resolve("CLAUDE.md"))) return dir
                dir = dir.parent
            }
            error("repo 루트를 찾지 못했다(docs/ + CLAUDE.md 기준). 시작 경로: $start")
        }
    }
}
