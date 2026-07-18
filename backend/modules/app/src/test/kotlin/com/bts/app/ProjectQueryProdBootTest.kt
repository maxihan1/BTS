// FR-PJ PR-3 Task 6 — 목록/단건 조회·설정변경 REST 를 prod 9-BC 조립 부팅으로 실 HTTP 검증

package com.bts.app

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
import java.security.MessageDigest
import java.util.UUID

/**
 * FR-PJ PR-3 Task 6 — `GET /api/v1/projects`·`GET /api/v1/projects/{key}`·
 * `PATCH /api/v1/projects/{key}`(T3~T5)를 prod **9-BC 조립 부팅**(실 Tomcat RANDOM_PORT +
 * `@ActiveProfiles("prod")`)에서 **실 HTTP** 로 검증한다.
 *
 * ## 왜 조립 부팅인가 — 실 permission resolver + 실 membership 포트가 필요
 * 목록 조회는 [com.bts.shared.membership.ProjectMembershipPort](identity-access
 * `ProjectMembershipAdapter`), 단건 BROWSE 는 [com.bts.shared.permission.IssuePermissionResolver]
 * (identity-access `IdentityAccessIssuePermissionResolver`, `@Profile("prod")`), 설정변경은
 * [com.bts.shared.permission.ComponentPermissionResolver](identity-access
 * `IdentityAccessComponentPermissionResolver`, `@Profile("prod")`)를 각각 cross-BC 포트로 호출한다.
 * 이 실제 구현들은 issue-tracking 단독 컨텍스트에는 없고([ProjectCreatePermissionProdBootTest] KDoc
 * 과 동일 사유 — 비-prod 는 `@Profile("!prod")` 스텁으로 대체), `role_permissions`/`project_memberships`
 * 시드도 identity-access 마이그레이션 소유라 :modules:app 조립 부팅으로만 실증 가능하다.
 *
 * ## ★ @ActiveProfiles("prod") 필수 — 왜 vacuous 를 피하는가
 * [ProdAssemblyHttpTestBase] 상속으로 prod 프로파일이 강제된다. 비-prod 프로파일이면 issue-tracking
 * 의 `AlwaysAllowIssuePermissionResolver`/`AlwaysAllowComponentPermissionResolver`(둘 다
 * `@Profile("!prod")`, 항상 true)가 살아나 BROWSE·컴포넌트 UPDATE 판정이 멤버십과 무관하게 전부 통과해
 * 시나리오 4(비관리자 403)가 무의미하게 사라진다.
 *
 * ## 검증 시나리오 4종 (plan)
 * 1. GET 목록 — actor 가 멤버인 프로젝트만 반환, 비멤버 프로젝트는 목록에 없음(존재 누설 0, PJ2-4).
 * 2. GET 단건 — BROWSE 통과 → 200 + 프로젝트 반환.
 * 3. PATCH(name 변경) — PROJECT_ADMIN → 204 No Content(T5 채택), 재조회로 name 반영 확인.
 * 4. PATCH — PROJECT_ADMIN 아닌 멤버/비멤버 → 403(두 액터를 각각 별도 테스트로 분리해 회귀 시 원인을 좁힌다).
 *
 * ## 인증 — PAT ([ProjectCreatePermissionProdBootTest] 동형 사유)
 * `PatAuthenticationFilter` 가 principal name 을 userId(UUID)로 심어 `CurrentActor` 가 그대로 읽고,
 * `Bearer pat_…` 는 중앙 `SecurityConfig` 의 patBearerMatcher 로 CSRF-ignore 되며, 비-Jwt principal 이라
 * `MfaEnrollmentGateFilter`/`SidRevokeJwtConverter` 를 타지 않아 세션 시드가 불필요하다.
 *
 * ## 권한 매트릭스 — 기본 스킴(project_permission_scheme 매핑 없이 fallback)
 * `PermissionSchemeRepository.roleHasPermission` 은 프로젝트별 스킴 매핑이 없으면
 * `permission_schemes.is_default = TRUE` 인 기본 스킴으로 fallback 한다. 이 테스트가 시드하는 프로젝트는
 * 별도 스킴을 매핑하지 않으므로 기본 스킴의 시드값을 그대로 쓴다 —
 * `BROWSE_PROJECT`(V014)는 PROJECT_ADMIN·MEMBER 양쪽에, `MANAGE_COMPONENTS`(V009, 설정변경 게이트)는
 * PROJECT_ADMIN 에만 부여돼 있다. 그래서 MEMBER 는 BROWSE 는 통과하지만 PATCH(시나리오 4)는 403 이다.
 *
 * ## 사전 조건
 * dev postgres 기동 — `docker compose -f infra/docker-compose.dev.yml up -d postgres` (5433).
 * [ProdAssemblyHttpTestBase] 를 상속만 하고 `@SpringBootTest`/`@ActiveProfiles`/`@DynamicPropertySource`
 * 를 자체 선언하지 않아(그 베이스 KDoc "webEnvironment는 컨텍스트 캐시 키의 일부다") 같은 JVM 의 다른
 * 조립 HTTP 테스트와 컨텍스트를 공유한다.
 *
 * ## [java.net.http.HttpClient] 를 쓰는 이유
 * [ProjectCreatePermissionProdBootTest] KDoc 과 동일 — `TestRestTemplate` 은 본문 있는 요청이 401/403 을
 * 받으면 본문을 못 읽는 함정([GitWebhookInboundPermitAllTest] 실측)이 있어 JDK 내장 [HttpClient] 로
 * 원 응답을 그대로 관측한다.
 */
class ProjectQueryProdBootTest : ProdAssemblyHttpTestBase() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private lateinit var mainProjectId: UUID

    /**
     * 세 액터(PROJECT_ADMIN·MEMBER·비멤버) + 두 프로젝트(멤버십 대상 KEY_MAIN, 존재 누설 검증용
     * KEY_OTHER)를 심는다. 매 테스트 앞뒤로 정리해 공유 dev postgres(5433)에 잔여물을 남기지 않는다.
     */
    @BeforeEach
    fun cleanupAndSeed() {
        cleanup()

        seedUser(USER_ADMIN_ID, USERNAME_ADMIN)
        seedPat(USER_ADMIN_ID, RAW_TOKEN_ADMIN)
        seedUser(USER_MEMBER_ID, USERNAME_MEMBER)
        seedPat(USER_MEMBER_ID, RAW_TOKEN_MEMBER)
        seedUser(USER_NONMEMBER_ID, USERNAME_NONMEMBER)
        seedPat(USER_NONMEMBER_ID, RAW_TOKEN_NONMEMBER)

        mainProjectId = seedProject(KEY_MAIN, PROJECT_NAME_INITIAL)
        // 존재 누설 검증용 — USER_ADMIN/USER_MEMBER 둘 다 이 프로젝트의 멤버가 아니다.
        seedProject(KEY_OTHER, "다른 팀 프로젝트")

        // GREEN — RED 는 이 두 멤버십 시드가 없어 목록/BROWSE/PATCH 성공 3개 시나리오가 실패했다
        // ([ProjectCreatePermissionProdBootTest] T8 선례와 동형 델타).
        seedMembership(mainProjectId, USER_ADMIN_ID, ROLE_PROJECT_ADMIN)
        seedMembership(mainProjectId, USER_MEMBER_ID, ROLE_MEMBER)
    }

    @AfterEach
    fun tearDown() {
        cleanup()
    }

    // ── 시나리오 1 — GET 목록: 멤버 프로젝트만, 비멤버 프로젝트 존재 누설 0 (PJ2-4) ──────

    @Test
    fun `GET projects 는 actor 가 멤버인 프로젝트만 반환하고 비멤버 프로젝트는 누설하지 않는다 (PJ2-4)`() {
        val response = getProjects(RAW_TOKEN_ADMIN)

        assertThat(response.statusCode()).isEqualTo(HTTP_OK)
        assertThat(response.body()).contains(KEY_MAIN)
        // ★ 존재 누설 0 — USER_ADMIN 은 KEY_OTHER 의 멤버가 아니므로 응답 본문에 그 key 가 없어야 한다.
        assertThat(response.body()).doesNotContain(KEY_OTHER)
    }

    // ── 시나리오 2 — GET 단건: BROWSE 통과 → 200 + 프로젝트 반환 ────────────────────

    @Test
    fun `GET projects key 는 BROWSE 를 통과하면 200 과 함께 프로젝트를 반환한다`() {
        val response = getProject(RAW_TOKEN_ADMIN, KEY_MAIN)

        assertThat(response.statusCode()).isEqualTo(HTTP_OK)
        assertThat(response.body()).contains(KEY_MAIN)
        assertThat(response.body()).contains(mainProjectId.toString())
        assertThat(response.body()).contains(PROJECT_NAME_INITIAL)
    }

    // ── 시나리오 3 — PATCH(PROJECT_ADMIN): 204 + 재조회로 name 반영 확인 ─────────────

    @Test
    fun `PATCH projects key 는 PROJECT_ADMIN 이면 204 이고 재조회 시 name 이 반영된다`() {
        val patchResponse = patchProjectName(RAW_TOKEN_ADMIN, KEY_MAIN, PROJECT_NAME_UPDATED)

        // 204 No Content — T5 가 채택한 응답 형태(재조회 없이 echo 도 하지 않는다).
        assertThat(patchResponse.statusCode()).isEqualTo(HTTP_NO_CONTENT)
        assertThat(patchResponse.body()).isEmpty()

        val getResponse = getProject(RAW_TOKEN_ADMIN, KEY_MAIN)
        assertThat(getResponse.statusCode()).isEqualTo(HTTP_OK)
        assertThat(getResponse.body()).contains(PROJECT_NAME_UPDATED)
    }

    // ── 시나리오 4 — PATCH 비관리자: PROJECT_ADMIN 아닌 멤버/비멤버 → 403 ─────────────

    @Test
    fun `PATCH projects key 는 PROJECT_ADMIN 이 아닌 멤버는 403 이다`() {
        val response = patchProjectName(RAW_TOKEN_MEMBER, KEY_MAIN, PROJECT_NAME_UPDATED)

        assertThat(response.statusCode()).isEqualTo(HTTP_FORBIDDEN)
        // ★ 본문 판별자 — "여전히 403" 만으론 vacuous. 컨트롤러 게이트가 응답한 403 은
        // ProblemDetail 본문에 이 errorCode 를 싣는다(catch-all handler 가 아니라 이 게이트가 거부했다는 증거).
        assertThat(response.body()).contains(ERROR_CODE_SETTINGS_FORBIDDEN)
        // 거부됐으니 name 은 그대로다(부수효과 0).
        assertThat(projectName(KEY_MAIN)).isEqualTo(PROJECT_NAME_INITIAL)
    }

    @Test
    fun `PATCH projects key 는 비멤버는 403 이다`() {
        val response = patchProjectName(RAW_TOKEN_NONMEMBER, KEY_MAIN, PROJECT_NAME_UPDATED)

        assertThat(response.statusCode()).isEqualTo(HTTP_FORBIDDEN)
        assertThat(response.body()).contains(ERROR_CODE_SETTINGS_FORBIDDEN)
        assertThat(projectName(KEY_MAIN)).isEqualTo(PROJECT_NAME_INITIAL)
    }

    // ── HTTP ────────────────────────────────────────────────────────────────────

    private fun getProjects(rawToken: String): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/projects"))
                .header(HEADER_AUTHORIZATION, "$BEARER_PREFIX$rawToken")
                .GET()
                .build(),
        )

    private fun getProject(
        rawToken: String,
        key: String,
    ): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/projects/$key"))
                .header(HEADER_AUTHORIZATION, "$BEARER_PREFIX$rawToken")
                .GET()
                .build(),
        )

    /** `PATCH /api/v1/projects/{key}` 를 PAT Bearer 로 1회 왕복한다. CSRF 토큰 불요(patBearerMatcher ignore). */
    private fun patchProjectName(
        rawToken: String,
        key: String,
        name: String,
    ): HttpResponse<String> {
        val body = """{"name":"$name"}"""
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/projects/$key"))
                .header(HEADER_AUTHORIZATION, "$BEARER_PREFIX$rawToken")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build()
        return send(request)
    }

    private fun send(request: HttpRequest): HttpResponse<String> =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString())

    // ── 시드 / 정리 ───────────────────────────────────────────────────────────────

    private fun seedUser(
        id: UUID,
        username: String,
    ) {
        jdbc.update("INSERT INTO users (id, username) VALUES (?, ?)", id, username)
    }

    /** 활성 PAT 를 심는다 — token_hash 는 SHA-256(raw) 로 저장하는 서비스 계약과 동일하게 계산한다(EC-26). */
    private fun seedPat(
        userId: UUID,
        rawToken: String,
    ) {
        jdbc.update(
            "INSERT INTO personal_access_tokens (id, user_id, name, token_hash, expires_at) " +
                "VALUES (?, ?, ?, ?, NOW() + INTERVAL '1 hour')",
            UUID.randomUUID(),
            userId,
            "pjpr3-t6-pat",
            sha256Hex(rawToken),
        )
    }

    /** 프로젝트를 심고 생성된 UUID 를 반환한다. */
    private fun seedProject(
        key: String,
        name: String,
    ): UUID =
        requireNotNull(
            jdbc.queryForObject(
                "INSERT INTO projects (key, name) VALUES (?, ?) RETURNING id",
                UUID::class.java,
                key,
                name,
            ),
        ) { "INSERT RETURNING 결과 없음 — key=$key" }

    private fun seedMembership(
        projectId: UUID,
        userId: UUID,
        role: String,
    ) {
        jdbc.update(
            "INSERT INTO project_memberships (project_id, user_id, role) VALUES (?, ?, ?)",
            projectId,
            userId,
            role,
        )
    }

    /**
     * 이 테스트가 심은 데이터만 지운다. `projects`→`project_memberships` 는 cross-BC 라 DB FK 가 없어
     * (ADR D2) 순서 무관하지만, `users` 삭제가 PAT·project_memberships 를 ON DELETE CASCADE 로 함께
     * 지운다(각 V006/V007).
     */
    private fun cleanup() {
        jdbc.update("DELETE FROM projects WHERE key IN (?, ?)", KEY_MAIN, KEY_OTHER)
        for (id in listOf(USER_ADMIN_ID, USER_MEMBER_ID, USER_NONMEMBER_ID)) {
            jdbc.update("DELETE FROM users WHERE id = ?", id)
        }
    }

    private fun projectName(key: String): String =
        requireNotNull(
            jdbc.queryForObject("SELECT name FROM projects WHERE key = ?", String::class.java, key),
        ) { "프로젝트를 찾을 수 없음 — key=$key" }

    /** raw PAT → SHA-256 소문자 hex 64자 — PersonalAccessTokenService.sha256Hex 와 동일 알고리즘(prefix 포함 전체). */
    private fun sha256Hex(raw: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** PROJECT_ADMIN 멤버(GET 목록/단건/PATCH 성공 경로). */
        val USER_ADMIN_ID: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")

        /** MEMBER(PROJECT_ADMIN 아님) — BROWSE 는 통과하나 PATCH 는 403. */
        val USER_MEMBER_ID: UUID = UUID.fromString("55555555-5555-4555-8555-555555555555")

        /** 어느 프로젝트의 멤버도 아닌 인증 사용자 — PATCH 403(멤버 게이트 자체에서 거부). */
        val USER_NONMEMBER_ID: UUID = UUID.fromString("66666666-6666-4666-8666-666666666666")

        const val USERNAME_ADMIN = "pjpr3-t6-admin"
        const val USERNAME_MEMBER = "pjpr3-t6-member"
        const val USERNAME_NONMEMBER = "pjpr3-t6-nonmember"

        /**
         * PAT raw token prefix — PersonalAccessToken.TOKEN_PREFIX 미러링([ProjectCreatePermissionProdBootTest]
         * 동형 사유 — 그 companion 이 `internal` 이라 app 모듈에서 참조 불가).
         */
        const val PAT_PREFIX = "pat_"

        /** PAT body 길이(base62) — PersonalAccessToken.TOKEN_BODY_LENGTH 와 동일. */
        const val PAT_BODY_LENGTH = 48

        /** 테스트 전용 raw PAT — 실 토큰 아님. 서로 다른 원문이라 token_hash UNIQUE 를 만족한다. */
        val RAW_TOKEN_ADMIN = PAT_PREFIX + "t6admin".padEnd(PAT_BODY_LENGTH, '0')
        val RAW_TOKEN_MEMBER = PAT_PREFIX + "t6member".padEnd(PAT_BODY_LENGTH, '0')
        val RAW_TOKEN_NONMEMBER = PAT_PREFIX + "t6nonmember".padEnd(PAT_BODY_LENGTH, '0')

        /** 테스트 전용 프로젝트 key(2~10자, `^[A-Z][A-Z0-9]{1,9}$`) — 다른 조립 테스트와 섞이지 않게 좁힌다. */
        const val KEY_MAIN = "PJQA"
        const val KEY_OTHER = "PJQB"

        const val PROJECT_NAME_INITIAL = "PR-3 T6 초기 이름"
        const val PROJECT_NAME_UPDATED = "PR-3 T6 변경된 이름"

        const val ROLE_PROJECT_ADMIN = "PROJECT_ADMIN"
        const val ROLE_MEMBER = "MEMBER"

        /**
         * [com.bts.issue.project.web.ProjectSettingsErrorCodes.FORBIDDEN] 미러 — 그 상수는
         * issue-tracking `internal` 이라 여기서 값을 직접 미러링한다(변경 시 동기화 대상).
         */
        const val ERROR_CODE_SETTINGS_FORBIDDEN = "ISSUE_PROJECT_SETTINGS_FORBIDDEN"

        const val HEADER_AUTHORIZATION = "Authorization"
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val CONTENT_TYPE_JSON = "application/json"
        const val BEARER_PREFIX = "Bearer "

        const val HTTP_OK = 200
        const val HTTP_NO_CONTENT = 204
        const val HTTP_FORBIDDEN = 403
    }
}
