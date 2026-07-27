// 프론트가 광범위하게 소비하는 저-시드 읽기 endpoint 의 계약 스냅샷 — 조립 실응답과 문자열 동등 단정

package com.bts.app.contract

import com.bts.app.ProdAssemblyHttpTestBase
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
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
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID

/**
 * **핵심 읽기 endpoint 계약 스냅샷 — 커버리지 확대 1차분.**
 *
 * ## 왜 이 세 endpoint 인가
 * 계약 스냅샷은 endpoint 마다 시드·인증 비용이 든다. 전부를 한 번에 덮을 수 없으므로
 * **「소비 폭이 넓고 시드가 싼」** 축을 먼저 골랐다.
 *
 * | endpoint | 소비 폭 | 시드 |
 * |---|---|---|
 * | `GET /api/v1/users/me/whoami` | **모든 화면** — 로그인 직후 세션·권한 게이팅의 진입점 | 사용자 + PAT |
 * | `GET /api/v1/issue-types` | 이슈 생성·필터·보드 — 타입 드롭다운 전역 | 없음(V003 시드) |
 * | `GET /api/v1/projects` | 사이드바 프로젝트 트리·프로젝트 선택 전역 | 프로젝트 1개 |
 *
 * `whoami` 가 특히 중요하다 — 이 응답의 필드 하나가 바뀌면 **로그인 이후 전 화면**이 영향받는데,
 * 지금까지 그것을 감시하는 계약이 없었다.
 *
 * ## 형제 [WorkflowSchemeContractSnapshotTest] 와 같은 기전
 * 조립 부팅(prod 프로파일 + 실 Tomcat)에서 **실제 응답**을 받아 정규화하고 파일과 문자열 동등을
 * 단정한다. 프론트는 같은 파일을 `.strict()` 로 파싱한다 — 한쪽이 어긋나면 그 지점에서 빨간불이 켜진다.
 * 정규화는 [ContractSnapshotCanonicalizer] 를 공유하므로 **값 변동은 지우고 타입·필드명·nullability 는
 * 보존**된다(정수/실수 구분 포함).
 *
 * ## 재생성
 * 손으로 편집하지 말 것. `-Dcontract.snapshot.update=true` 로 재생성한다.
 * ⚠️ 재생성 PR 은 diff 의 `null` 증감을 눈으로 확인할 것 — 프론트 Zod 의 `.nullable()` 은
 * `null` 과 `"string"` 을 둘 다 통과시켜, 그 축의 감시자는 이 스냅샷 한 곳뿐이다.
 */
class CoreReadContractSnapshotTest : ProdAssemblyHttpTestBase() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val mapper = ObjectMapper()

    @BeforeEach
    fun cleanupAndSeed() {
        cleanup()
        jdbc.update("INSERT INTO users (id, username, display_name) VALUES (?, ?, ?)", USER_ID, USERNAME, USERNAME)
        jdbc.update(
            "INSERT INTO personal_access_tokens (id, user_id, name, token_hash, expires_at) " +
                "VALUES (?, ?, ?, ?, NOW() + INTERVAL '1 hour')",
            UUID.randomUUID(),
            USER_ID,
            "core-read-contract-snapshot-pat",
            sha256Hex(RAW_TOKEN),
        )
        jdbc.update("INSERT INTO projects (id, key, name) VALUES (?, ?, ?)", PROJECT_ID, PROJECT_KEY, "계약 스냅샷 코어")
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
    fun `핵심 읽기 계약 스냅샷이 실제 조립 응답과 일치한다`() {
        val actual = prettyPrint(canonical(collectRawResponses()))

        if (System.getProperty(UPDATE_FLAG) == "true") {
            Files.createDirectories(SNAPSHOT_PATH.parent)
            Files.writeString(SNAPSHOT_PATH, actual)
            return
        }

        assertThat(Files.exists(SNAPSHOT_PATH))
            .`as`("스냅샷 파일이 없다. $REGENERATE_HINT")
            .isTrue()
        assertThat(actual)
            .`as`("조립 응답이 계약 스냅샷과 다르다. 의도한 계약 변경이면 $REGENERATE_HINT")
            .isEqualTo(Files.readString(SNAPSHOT_PATH))
    }

    /**
     * 정규화가 지우는 축의 가드 — `whoami` 의 **불리언 극성**은 값이 표준화되지 않으므로
     * 스냅샷이 잡지 못한다. 뒤집히면 로그인 직후 게이팅이 통째로 반대가 된다.
     */
    @Test
    fun `whoami 의 불리언 극성이 뒤집히지 않는다`() {
        val whoami = send("GET", "/api/v1/users/me/whoami", 200)

        assertThat(whoami.path("mustChangePassword").asBoolean(true))
            .`as`("시드 사용자는 비밀번호 강제 변경 대상이 아니다")
            .isFalse()
        assertThat(whoami.path("isSystemAdmin").asBoolean(true))
            .`as`("시드 사용자에게 SYSTEM_ADMIN 을 부여하지 않았다")
            .isFalse()
    }

    private fun collectRawResponses(): Map<String, JsonNode> =
        mapOf(
            "GET /api/v1/users/me/whoami" to send("GET", "/api/v1/users/me/whoami", 200),
            "GET /api/v1/issue-types" to send("GET", "/api/v1/issue-types", 200),
            "GET /api/v1/projects" to send("GET", "/api/v1/projects", 200),
        )

    private fun send(
        method: String,
        path: String,
        expectedStatus: Int,
    ): JsonNode {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Authorization", "Bearer $RAW_TOKEN")
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build()
        val response =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
                .send(request, HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode())
            .`as`("$method $path 가 $expectedStatus 이 아니다. 본문: ${response.body()}")
            .isEqualTo(expectedStatus)
        return mapper.readTree(response.body())
    }

    private fun canonical(raw: Map<String, JsonNode>): JsonNode {
        val root = JsonNodeFactory.instance.objectNode()
        root.put("\$comment", SNAPSHOT_NOTE)
        raw.keys.sorted().forEach {
            root.set<JsonNode>(it, ContractSnapshotCanonicalizer.canonicalNode(raw.getValue(it)))
        }
        return root
    }

    private fun prettyPrint(node: JsonNode): String {
        val indenter = DefaultIndenter("  ", "\n")
        val printer = DefaultPrettyPrinter().withObjectIndenter(indenter).withArrayIndenter(indenter)
        return mapper.writer(printer).writeValueAsString(node) + "\n"
    }

    /** 이 테스트가 심은 것만 지운다. users 삭제가 PAT·멤버십을 CASCADE 로 함께 지운다. */
    private fun cleanup() {
        jdbc.update("DELETE FROM projects WHERE key = ?", PROJECT_KEY)
        jdbc.update("DELETE FROM users WHERE id = ?", USER_ID)
    }

    private fun sha256Hex(raw: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val UPDATE_FLAG = "contract.snapshot.update"
        const val REGENERATE_HINT =
            "./gradlew :modules:app:test --tests '*CoreReadContractSnapshotTest*' " +
                "-Dcontract.snapshot.update=true 로 재생성하라."
        const val SNAPSHOT_NOTE =
            "자동 생성 파일 — 손으로 고치지 말 것. 조립 실응답에서 재생성한다(CoreReadContractSnapshotTest)."

        const val USERNAME = "core-read-contract-snapshot-admin"
        val RAW_TOKEN = "pat_" + "coreread".padEnd(48, '0')
        const val PROJECT_KEY = "CORECT"

        val USER_ID: UUID = UUID.fromString("cccccccc-1111-4111-8111-000000000001")
        val PROJECT_ID: UUID = UUID.fromString("cccccccc-1111-4111-8111-000000000002")

        val SNAPSHOT_PATH: Path = repoRoot().resolve("docs/contracts/core-read.snapshot.json")

        fun repoRoot(): Path {
            var dir = Paths.get("").toAbsolutePath()
            while (!Files.isDirectory(dir.resolve("docs/contracts")) && dir.parent != null) {
                dir = dir.parent
            }
            return dir
        }
    }
}
