// GlobalPermissionGrantController 슬라이스 테스트 — grant/revoke/list 라우팅·SYSTEM_ADMIN 가드·PAT actor·에러코드 (FR-PM-10 Task 6)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.permission.DuplicateGrantException
import com.atlas.bts.identity.permission.GlobalPermissionGrant
import com.atlas.bts.identity.permission.GlobalPermissionGrantService
import com.atlas.bts.identity.permission.GrantNotFoundException
import com.atlas.bts.identity.permission.GranteeNotFoundException
import com.atlas.bts.identity.permission.GranteeType
import com.atlas.bts.identity.permission.UnknownPermissionException
import com.atlas.bts.identity.session.SessionService
import com.bts.shared.permission.SystemPermissionResolver
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * GlobalPermissionGrantController 슬라이스 테스트 (FR-PM-10 Task 6).
 *
 * 검증 범위 (13).
 * - 3 엔드포인트 HTTP 상태 (201/200/204)
 * - SYSTEM_ADMIN 가드 — isSystemAdmin=false → 403 forbidden (부여·목록 양쪽)
 * - **403 본문 내부구조 0** — 권한 Guard 예외 message 누출 회귀 방지
 * - **PAT actor 양성 경로** — 아래 §PAT 참조
 * - 미인증 401(필터 체인) / 비-UUID subject 401(resolveActorId 실패 경로)
 * - 도메인 예외 → 404/409/400 + snake_case error 키 매핑
 *
 * ## ★ PAT 양성 테스트가 이 파일의 핵심이다
 * ADR D-4(패턴 결정)는 *"PAT 를 지원하려고 `@PreAuthorize hasRole` 대신 명시 호출을 쓴다"* 로
 * 게이트 방식을 정했다. 그런데 **JWT 테스트만 있으면 누군가 `@PreAuthorize("hasRole('SYSTEM_ADMIN')")`
 * 로 바꿔도 전부 초록이다** — `PatAuthenticationFilter.kt:48` 이 `ROLE_PAT` 만 부여하므로 PAT 는 그
 * 순간부터 403 인데 아무도 모른다. 즉 **결정의 근거 자체가 미검증으로 남는다.**
 *
 * [`POST grant PAT actor 201`][POST grant PAT actor 201 — SecurityContext principal 추출] 은
 * `Authorization: Bearer pat_...` 헤더로 **실제 [PatAuthenticationFilter] 를 태워** 그 축을 잠근다
 * (선례 `UserGroupControllerTest:605` 는 게이트 없는 GET 에 있어 이 축을 못 잠근다).
 * mock 인증(`jwt().authorities(ROLE_PAT)`)이 아니라 실 필터 경로라 `resolveActorId` 의
 * SecurityContext principal(String) 가지까지 함께 실증된다.
 *
 * OAuth2ClientAutoConfiguration 제외 — Keycloak issuer-uri 네트워크 접속 차단.
 */
@WebMvcTest(
    controllers = [GlobalPermissionGrantController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, GlobalPermissionGrantControllerTest.MockBeans::class)
class GlobalPermissionGrantControllerTest {
    companion object {
        private val ADMIN_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        private val PLAIN_USER_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        private val GRANTEE_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        private val GRANT_ID: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")

        private val NOW: Instant = Instant.parse("2026-07-17T10:00:00Z")

        private const val BASE = "/api/v1/admin/global-permissions"
        private const val CREATE_PROJECT = "CREATE_PROJECT"

        /** "pat_" prefix 포함 PAT raw token — CSRF-ignore(patBearerMatcher) 대상이기도 하다. */
        private const val RAW_PAT = "pat_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        private val ALLOWED_ORIGINS = listOf("http://localhost:5173")

        private const val GRANT_BODY = """{"permission":"CREATE_PROJECT","granteeType":"USER","granteeId":"22222222-2222-4222-8222-222222222222"}"""

        private fun grant(
            granteeType: GranteeType = GranteeType.USER,
            granteeId: UUID = GRANTEE_ID,
        ) = GlobalPermissionGrant(
            id = GRANT_ID,
            permission = CREATE_PROJECT,
            granteeType = granteeType,
            granteeId = granteeId,
            grantedBy = ADMIN_ID,
            createdAt = NOW,
        )

        private fun activePat(userId: UUID = ADMIN_ID) =
            PersonalAccessToken(
                id = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
                userId = userId,
                name = "ci-token",
                tokenHash = "irrelevant-hash",
                scopes = listOf("*"),
                expiresAt = null,
                lastUsedAt = null,
                revokedAt = null,
                createdAt = NOW,
            )
    }

    @TestConfiguration
    class MockBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            val clock = Clock.fixed(Instant.parse("2026-07-17T10:00:00Z"), ZoneOffset.UTC)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource = CorsConfig().corsConfigurationSource(ALLOWED_ORIGINS)

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun globalPermissionGrantService(): GlobalPermissionGrantService = mockk()

        @Bean
        fun systemPermissionResolver(): SystemPermissionResolver = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var grantService: GlobalPermissionGrantService

    @Autowired
    lateinit var systemPermissionResolver: SystemPermissionResolver

    @Autowired
    lateinit var personalAccessTokenService: PersonalAccessTokenService

    /**
     * mock @Bean 은 Spring 컨텍스트 캐싱 때문에 **테스트 메서드 간 공유**되며 호출 기록이 누적된다.
     * 초기화하지 않으면 `verify(exactly = 0)` 이 다른 테스트의 호출을 보고 실패하고, 반대로
     * `verify(exactly = 1)` 이 누적 호출로 통과해 **판별력을 잃는다**. 선례 `PersonalAccessTokenControllerTest:106`.
     */
    @BeforeEach
    fun resetMocks() {
        clearMocks(grantService, systemPermissionResolver, personalAccessTokenService)
    }

    private fun grantAdmin(actorId: UUID = ADMIN_ID) {
        every { systemPermissionResolver.isSystemAdmin(actorId) } returns true
    }

    // ── POST — 부여 ──────────────────────────────────────────────────────────

    @Test
    fun `POST grant 관리자 201 — GlobalPermissionGrantResponse 반환`() {
        grantAdmin()
        // grantedBy 를 ADMIN_ID 로 고정한 stub — 컨트롤러가 인증 actor 가 아닌 값을 넘기면
        // non-relaxed mockk 가 매칭 실패로 터진다 (ADR D-5 감사 흔적 배선의 판별자).
        every { grantService.grant(CREATE_PROJECT, GranteeType.USER, GRANTEE_ID, ADMIN_ID) } returns grant()

        mockMvc.perform(
            post(BASE)
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(GRANT_BODY),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(GRANT_ID.toString()))
            .andExpect(jsonPath("$.permission").value(CREATE_PROJECT))
            .andExpect(jsonPath("$.granteeType").value("USER"))
            .andExpect(jsonPath("$.granteeId").value(GRANTEE_ID.toString()))
            .andExpect(jsonPath("$.grantedBy").value(ADMIN_ID.toString()))

        verify(exactly = 1) { grantService.grant(CREATE_PROJECT, GranteeType.USER, GRANTEE_ID, ADMIN_ID) }
    }

    @Test
    fun `POST grant 비-SYSTEM_ADMIN 403 forbidden`() {
        every { systemPermissionResolver.isSystemAdmin(PLAIN_USER_ID) } returns false

        mockMvc.perform(
            post(BASE)
                .with(jwt().jwt { it.subject(PLAIN_USER_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(GRANT_BODY),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("forbidden"))

        // 가드가 서비스 도달 전에 끊는다 — 이중 가드의 두 번째 겹이 실제로 동작함을 잠근다.
        verify(exactly = 0) { grantService.grant(any(), any(), any(), any()) }
    }

    @Test
    fun `GET grants 비-SYSTEM_ADMIN 403 forbidden`() {
        every { systemPermissionResolver.isSystemAdmin(PLAIN_USER_ID) } returns false

        mockMvc.perform(
            get(BASE).with(jwt().jwt { it.subject(PLAIN_USER_ID.toString()) }),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("forbidden"))

        verify(exactly = 0) { grantService.list() }
    }

    @Test
    fun `403 응답 본문에 권한 내부 구조가 없다`() {
        every { systemPermissionResolver.isSystemAdmin(PLAIN_USER_ID) } returns false

        // [[fr-pm-04-guard-exception-message-http-leak]] 회귀 방지 — Guard 예외 message 를 그대로
        // detail 로 내보내면 내부 사정(권한코드/정책/존재 여부)이 샌다. 본문은 error 키 하나뿐이어야 한다.
        mockMvc.perform(
            post(BASE)
                .with(jwt().jwt { it.subject(PLAIN_USER_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(GRANT_BODY),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$.error").value("forbidden"))
            .andExpect(jsonPath("$.message").doesNotExist())
            .andExpect(jsonPath("$.detail").doesNotExist())
            .andExpect(jsonPath("$.permission").doesNotExist())
            .andExpect(jsonPath("$.trace").doesNotExist())
    }

    @Test
    fun `POST grant 존재하지 않는 grantee 404 grantee_not_found`() {
        grantAdmin()
        every {
            grantService.grant(any(), any(), any(), any())
        } throws GranteeNotFoundException(GranteeType.USER, GRANTEE_ID)

        mockMvc.perform(
            post(BASE)
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(GRANT_BODY),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("grantee_not_found"))
    }

    @Test
    fun `POST grant 중복 부여 409 grant_already_exists`() {
        grantAdmin()
        every {
            grantService.grant(any(), any(), any(), any())
        } throws DuplicateGrantException(CREATE_PROJECT, GranteeType.USER, GRANTEE_ID)

        mockMvc.perform(
            post(BASE)
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(GRANT_BODY),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("grant_already_exists"))
    }

    @Test
    fun `POST grant 미지 permission 400 unknown_permission`() {
        grantAdmin()
        every { grantService.grant(any(), any(), any(), any()) } throws UnknownPermissionException("DROP_EVERYTHING")

        mockMvc.perform(
            post(BASE)
                .with(jwt().jwt { it.subject(ADMIN_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"permission":"DROP_EVERYTHING","granteeType":"USER","granteeId":"$GRANTEE_ID"}""",
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("unknown_permission"))
    }

    // ── GET — 목록 ───────────────────────────────────────────────────────────

    @Test
    fun `GET grants 관리자 200 목록 반환`() {
        grantAdmin()
        every { grantService.list() } returns listOf(grant(), grant(granteeType = GranteeType.GROUP))

        mockMvc.perform(
            get(BASE).with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].permission").value(CREATE_PROJECT))
            .andExpect(jsonPath("$[0].granteeType").value("USER"))
            .andExpect(jsonPath("$[1].granteeType").value("GROUP"))
    }

    // ── DELETE — 회수 ────────────────────────────────────────────────────────

    @Test
    fun `DELETE grant 관리자 204`() {
        grantAdmin()
        every { grantService.revoke(GRANT_ID) } just runs

        mockMvc.perform(
            delete("$BASE/$GRANT_ID").with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNoContent)

        verify(exactly = 1) { grantService.revoke(GRANT_ID) }
    }

    @Test
    fun `DELETE 존재하지 않는 grant 404 grant_not_found`() {
        grantAdmin()
        // ADR D-5 — "지웠다고 믿었는데 대상이 없었다"를 조용히 204 로 만들지 않는다.
        every { grantService.revoke(GRANT_ID) } throws GrantNotFoundException(GRANT_ID)

        mockMvc.perform(
            delete("$BASE/$GRANT_ID").with(jwt().jwt { it.subject(ADMIN_ID.toString()) }),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("grant_not_found"))
    }

    // ── ★ PAT actor 양성 경로 (D4 의 근거를 잠근다) ──────────────────────────

    @Test
    fun `POST grant PAT actor 201 — SecurityContext principal 추출`() {
        // 실제 PatAuthenticationFilter 를 태운다 — verify 성공 → principal=String(userId) + ROLE_PAT.
        // ROLE_SYSTEM_ADMIN authority 는 없으므로, 게이트를 @PreAuthorize("hasRole('SYSTEM_ADMIN')")
        // 로 바꾸면 이 테스트가 403 으로 fail 한다. 그게 이 테스트의 존재 이유다.
        every { personalAccessTokenService.verify(RAW_PAT) } returns Result.success(activePat())
        grantAdmin()
        every { grantService.grant(CREATE_PROJECT, GranteeType.USER, GRANTEE_ID, ADMIN_ID) } returns grant()

        mockMvc.perform(
            post(BASE)
                .header("Authorization", "Bearer $RAW_PAT")
                .contentType(MediaType.APPLICATION_JSON)
                .content(GRANT_BODY),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.grantedBy").value(ADMIN_ID.toString()))

        // PAT 경로에서도 grantedBy 가 PAT 소유자로 배선된다 (ADR D-5).
        verify(exactly = 1) { grantService.grant(CREATE_PROJECT, GranteeType.USER, GRANTEE_ID, ADMIN_ID) }
    }

    // ── 인증 실패 경로 ───────────────────────────────────────────────────────

    @Test
    fun `GET grants 미인증 401`() {
        // 필터 체인 (/api/** authenticated) — SecurityConfig 무변경으로 커버됨을 잠근다.
        mockMvc.perform(get(BASE))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST grant 비-UUID subject 401 unauthorized`() {
        // resolveActorId 실패 경로 — 미인증 401(필터 체인)과 다른 코드 경로다.
        mockMvc.perform(
            post(BASE)
                .with(jwt().jwt { it.subject("not-a-uuid") })
                .contentType(MediaType.APPLICATION_JSON)
                .content(GRANT_BODY),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("unauthorized"))
    }
}
