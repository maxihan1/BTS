// FR-PJ-01 DoD-9 — 비-SYSTEM_ADMIN CREATE_PROJECT grant 보유자의 프로젝트 생성을 prod 9-BC 조립 부팅으로 검증

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
 * FR-PJ-01 **DoD-9** — `CREATE_PROJECT` 전역권한 grant 를 가진 **비-SYSTEM_ADMIN** 이 프로젝트를 만들 수
 * 있는지, prod **9-BC 조립 부팅**(실 Tomcat RANDOM_PORT + `@ActiveProfiles("prod")`)에서 **실 HTTP** 로
 * 검증한다. 판정식 `hasGlobalPermission = hasGrant OR isSystemAdmin`(ADR global-permission-grants D-2)의
 * 세 통로를 각각 못박는다.
 *
 * ## 왜 issue-tracking 격리가 아니라 조립 부팅인가 (plan Task 8 CRITICAL)
 * `@ActiveProfiles("prod")` 면 issue-tracking 의 `NonProdAllowSystemAdminResolver`(@Profile("!prod"),
 * 항상 true 스텁)가 꺼지고, 실제 [com.atlas.bts.identity.permission.IdentityAccessSystemPermissionResolver]
 * (identity-access)는 issue-tracking 클래스패스에 없어 `SystemPermissionResolver` 빈이 부재 → 부팅 실패다.
 * grant 시드가 필요로 하는 `global_permission_grants`(identity V036)도 issue-tracking 마이그레이션엔 없다.
 * 그래서 실제 resolver + `global_permission_grants` + `system_role_assignments` + `ProjectMembershipWriteAdapter`
 * 가 **모두 존재하는** :modules:app 조립 부팅으로만 이 DoD 를 실증할 수 있다.
 *
 * ## ★ C1 — @ActiveProfiles("prod") 필수
 * [ProdAssemblyHttpTestBase] 상속으로 prod 프로파일이 강제된다. 기본(비-prod) 프로파일이면
 * `NonProdAllowSystemAdminResolver`(isSystemAdmin 항상 true)가 살아나 세 시나리오가 전부 201 로
 * **무의미하게 통과**한다(grant/role 이 판정에 아무 영향을 못 준다). 이 상속을 떼면 그 순간 DoD 가 공허해진다.
 *
 * ## ★ B8 판별자 — 이 테스트만이 잡는 회귀
 * prod 어댑터가 [com.bts.shared.permission.SystemPermissionResolver.hasGlobalPermission] override 를 잊으면
 * 인터페이스 default(`= isSystemAdmin` 뿐, grant 무시)가 살아나 `CREATE_PROJECT` 가 SYSTEM_ADMIN 전용으로
 * 되돌아간다 — **비-admin grant 보유자가 403**. fail-closed 라 장애로도 안 드러난다.
 * [비_SYSTEM_ADMIN 이 CREATE_PROJECT grant 를 가지면 201 로 프로젝트를 만든다 (DoD-9)] 만이 그 회귀를 잡는다.
 * (실증: grant 행 하나만 있고 없고가 403↔201 을 가른다 — RED 는 grant/role 미시드로 두 양성 케이스가 403 이었고,
 * GREEN 이 grant/role 두 행을 넣자 201 로 뒤집혔다. 델타가 정확히 `hasGrant`·`isSystemAdmin` 두 통로다.)
 *
 * ## 인증 — PAT (JWT 아님)
 * 조립 HTTP 라 실제 인증 주체가 [com.bts.issue.adapter.inbound.rest.CurrentActor] 로 읽혀야 한다. PAT 를 쓰는
 * 이유는 (1) `PatAuthenticationFilter` 가 principal name 을 userId(UUID)로 심어 CurrentActor 가 그대로 읽고,
 * (2) `Authorization: Bearer pat_…` 는 중앙 `SecurityConfig` 의 patBearerMatcher 로 **CSRF-ignore** 되며,
 * (3) 비-Jwt principal 이라 `MfaEnrollmentGateFilter`(SYSTEM_ADMIN 은 MFA 미등록 시 강제 대상이라 gate 403)를
 * 통과하고, (4) JWT 전용 `SidRevokeJwtConverter`(sid→활성 세션 조회)도 타지 않아 세션 시드가 불필요하기
 * 때문이다. 컨트롤러 게이트는 `@PreAuthorize hasRole` 이 아니라 `hasGlobalPermission` 이라 PAT 가 탈락하지
 * 않는다(그 컨트롤러 KDoc "PAT 경로에 role claim 이 없어…"). 판정은 어느 인증 방식이든 DB(`system_role_assignments`
 * / `global_permission_grants`)를 UUID 로 조회하므로 PAT/JWT 와 무관하게 동일하다.
 *
 * ## ★ C2 — 403 은 본문 판별자로 vacuous 회피
 * "여전히 403" 만으로는 컨트롤러 게이트가 없어도(다른 필터가 잘라도) 통과한다. 컨트롤러가 응답한 403 은
 * ProblemDetail 본문에 [ERROR_CODE_FORBIDDEN] 을 싣는다 — 이 errorCode 로만 "게이트가 살아서 거부했다"를
 * 확정한다. PAT 가 MFA gate 를 우회하므로 이 경로의 유일한 403 출처가 컨트롤러 게이트임도 함께 보장된다.
 *
 * ## 사전 조건
 * dev postgres 기동 — `docker compose -f infra/docker-compose.dev.yml up -d postgres` (5433).
 * 컨텍스트 캐시 공유를 위해 [ProdAssemblyHttpTestBase] 를 상속만 하고 `@SpringBootTest`·`@ActiveProfiles`·
 * `@DynamicPropertySource` 를 자체 선언하지 않는다(베이스 KDoc "webEnvironment는 컨텍스트 캐시 키의 일부다").
 *
 * ## ★ [java.net.http.HttpClient] 를 쓰는 이유
 * `TestRestTemplate` 은 :modules:app 에 Apache HttpComponents 5 가 없어 `HttpURLConnection` 으로 떨어지고,
 * 본문 있는 요청이 401/403 을 받으면 본문을 못 읽는 함정이 있다([GitWebhookInboundPermitAllTest] KDoc 실측).
 * 403 본문이 판별자인 이 테스트에선 치명적이라 JDK 내장 [HttpClient] 로 원 응답을 그대로 관측한다.
 */
class ProjectCreatePermissionProdBootTest : ProdAssemblyHttpTestBase() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    /**
     * 세 행위자와 그 PAT 를 심는다. grant/role 은 GREEN 에서만 심어 판정 통로를 델타로 분리한다(B8).
     * 매 테스트 앞뒤로 정리해 공유 dev postgres(5433)에 잔여물을 남기지 않는다.
     */
    @BeforeEach
    fun cleanupAndSeed() {
        cleanup()
        seedUser(USER_GRANT_ID, USERNAME_GRANT)
        seedPat(USER_GRANT_ID, RAW_TOKEN_GRANT)
        seedUser(USER_NOGRANT_ID, USERNAME_NOGRANT)
        seedPat(USER_NOGRANT_ID, RAW_TOKEN_NOGRANT)
        seedUser(USER_ADMIN_ID, USERNAME_ADMIN)
        seedPat(USER_ADMIN_ID, RAW_TOKEN_ADMIN)
    }

    @AfterEach
    fun tearDown() {
        cleanup()
    }

    @Test
    fun `비-SYSTEM_ADMIN 이 CREATE_PROJECT grant 를 가지면 201 로 프로젝트를 만든다 (DoD-9)`() {
        val response = createProject(RAW_TOKEN_GRANT, KEY_GRANT, "권한 부여 프로젝트")

        // 201 = 조립 앱이 grant 축(hasGrant)으로 CREATE_PROJECT 를 통과시켰다는 증명.
        assertThat(response.statusCode()).isEqualTo(HTTP_CREATED)
        // 상태만 보면 vacuous — 본문에 생성 key 가 실려야 컨트롤러 파이프라인이 실제로 돌았다는 증거.
        assertThat(response.body()).contains(KEY_GRANT)
        // 프로젝트 1행 + 생성자 PROJECT_ADMIN 멤버십 1행 = cross-BC(issue-tracking projects +
        // identity-access project_memberships)가 같은 조립 트랜잭션으로 실제로 써졌다는 증거.
        assertThat(projectExists(KEY_GRANT)).isTrue()
        assertThat(adminMembershipExists(KEY_GRANT, USER_GRANT_ID)).isTrue()
    }

    @Test
    fun `grant 도 SYSTEM_ADMIN 도 아닌 인증 사용자는 403 이고 본문에 게이트 errorCode 가 실린다 (S2 · C2)`() {
        val response = createProject(RAW_TOKEN_NOGRANT, KEY_GRANT, "권한 없는 사용자")

        assertThat(response.statusCode()).isEqualTo(HTTP_FORBIDDEN)
        // ★ C2 — 상태코드만으론 공허하다. 컨트롤러 게이트가 응답한 403 은 본문에 이 errorCode 를 싣는다.
        // 필터가 잘랐다면(예: 빈 401/403) 이 문자열이 없다 — 이 단언이 곧 "게이트가 살아서 거부했다"의 증명이다.
        assertThat(response.body()).contains(ERROR_CODE_FORBIDDEN)
        // 거부됐으니 프로젝트가 만들어지지 않았다(부수효과 0).
        assertThat(projectExists(KEY_GRANT)).isFalse()
    }

    @Test
    fun `SYSTEM_ADMIN 은 grant 없이도 201 로 프로젝트를 만든다 (판정식 뒷항 isSystemAdmin)`() {
        val response = createProject(RAW_TOKEN_ADMIN, KEY_ADMIN, "관리자 프로젝트")

        // 201 = grant 행이 하나도 없어도 판정식 뒷항(isSystemAdmin)이 통과시킨다는 증명(FR-PM-10 부트스트랩 공백 방지).
        assertThat(response.statusCode()).isEqualTo(HTTP_CREATED)
        assertThat(response.body()).contains(KEY_ADMIN)
        assertThat(projectExists(KEY_ADMIN)).isTrue()
        assertThat(adminMembershipExists(KEY_ADMIN, USER_ADMIN_ID)).isTrue()
    }

    // ── HTTP ────────────────────────────────────────────────────────────────────

    /** `POST /api/v1/projects` 를 PAT Bearer 로 1회 왕복한다. CSRF 토큰 불요(patBearerMatcher ignore). */
    private fun createProject(
        rawToken: String,
        key: String,
        name: String,
    ): HttpResponse<String> {
        val body = """{"key":"$key","name":"$name"}"""
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/projects"))
                .header(HEADER_AUTHORIZATION, "$BEARER_PREFIX$rawToken")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString())
    }

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
            "pjpr2-dod9-pat",
            sha256Hex(rawToken),
        )
    }

    /**
     * 이 테스트가 심은 데이터만 지운다. users 삭제가 PAT·system_role_assignments·project_memberships 를
     * ON DELETE CASCADE 로 함께 지운다(각 V006/V012/V007). grant 는 FK 가 없어(다형 참조) 명시 삭제한다.
     */
    private fun cleanup() {
        jdbc.update("DELETE FROM projects WHERE key IN (?, ?)", KEY_GRANT, KEY_ADMIN)
        for (id in listOf(USER_GRANT_ID, USER_NOGRANT_ID, USER_ADMIN_ID)) {
            jdbc.update("DELETE FROM global_permission_grants WHERE grantee_id = ?", id)
            jdbc.update("DELETE FROM users WHERE id = ?", id)
        }
    }

    // ── DB 조회 (양성 파이프라인 실증) ──────────────────────────────────────────────

    private fun projectExists(key: String): Boolean =
        jdbc.queryForObject(
            "SELECT count(*) FROM projects WHERE key = ? AND deleted_at IS NULL",
            Int::class.java,
            key,
        )!! > 0

    private fun adminMembershipExists(
        key: String,
        userId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT count(*) FROM project_memberships m JOIN projects p ON p.id = m.project_id " +
                "WHERE p.key = ? AND m.user_id = ? AND m.role = 'PROJECT_ADMIN'",
            Int::class.java,
            key,
            userId,
        )!! > 0

    /** raw PAT → SHA-256 소문자 hex 64자 — PersonalAccessTokenService.sha256Hex 와 동일 알고리즘(prefix 포함 전체). */
    private fun sha256Hex(raw: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** grant 보유 비-admin. */
        val USER_GRANT_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")

        /** grant·role 없는 인증 사용자(403 케이스). */
        val USER_NOGRANT_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")

        /** SYSTEM_ADMIN(grant 없이 201). */
        val USER_ADMIN_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")

        const val USERNAME_GRANT = "pjpr2-dod9-nonadmin-grant"
        const val USERNAME_NOGRANT = "pjpr2-dod9-nonadmin-nogrant"
        const val USERNAME_ADMIN = "pjpr2-dod9-system-admin"

        /**
         * PAT raw token prefix — PersonalAccessToken.TOKEN_PREFIX 와 같은 값을 미러링한다(그 companion 이
         * `internal` 이라 app 모듈에서 참조 불가). 중앙 SecurityConfig 의 patBearerMatcher(`Bearer pat_`)가
         * 이 prefix 로 CSRF-ignore 를 건다.
         */
        const val PAT_PREFIX = "pat_"

        /** PAT body 길이(base62) — PersonalAccessToken.TOKEN_BODY_LENGTH 와 동일(실 발급과 형상 정합). */
        const val PAT_BODY_LENGTH = 48

        /** 테스트 전용 raw PAT — 실 토큰 아님. 서로 다른 원문이라 token_hash UNIQUE 를 만족한다. */
        val RAW_TOKEN_GRANT = PAT_PREFIX + "grant".padEnd(PAT_BODY_LENGTH, '0')
        val RAW_TOKEN_NOGRANT = PAT_PREFIX + "nogrant".padEnd(PAT_BODY_LENGTH, '0')
        val RAW_TOKEN_ADMIN = PAT_PREFIX + "admin".padEnd(PAT_BODY_LENGTH, '0')

        /** 테스트 전용 프로젝트 key(2~10자, `^[A-Z][A-Z0-9]{1,9}$`) — 다른 조립 테스트와 섞이지 않게 좁힌다. */
        const val KEY_GRANT = "PJPRA"
        const val KEY_ADMIN = "PJPRB"

        /**
         * ProjectCreateExceptionHandler(ProjectCreateErrorCodes.FORBIDDEN)가 403 ProblemDetail 에 싣는 errorCode.
         * 그 상수는 issue-tracking `internal` 이라 여기서 값을 직접 미러링한다(변경 시 동기화 대상).
         */
        const val ERROR_CODE_FORBIDDEN = "ISSUE_CREATE_PROJECT_FORBIDDEN"

        const val HEADER_AUTHORIZATION = "Authorization"
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val CONTENT_TYPE_JSON = "application/json"
        const val BEARER_PREFIX = "Bearer "

        const val HTTP_CREATED = 201
        const val HTTP_FORBIDDEN = 403
    }
}
