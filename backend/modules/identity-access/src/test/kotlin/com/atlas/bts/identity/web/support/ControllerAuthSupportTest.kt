// 컨트롤러 권한 게이트 공용 지원(ControllerAuthSupport) 단위 테스트 — 401/403 상수·actor 추출 6분기·SYSTEM_ADMIN 게이트 3분기

package com.atlas.bts.identity.web.support

import com.bts.shared.permission.SystemPermissionResolver
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

/**
 * [ControllerAuthSupport](web/support/ControllerAuthSupport.kt) 단위 테스트.
 *
 * 6개 컨트롤러(FieldPermission · GlobalPermissionGrant · IssueSecurityScheme · ProjectMember ·
 * ProjectSecurityScheme · UserGroup)가 중복 보유하던 게이트 3종을 단일 지점으로 추출했으므로,
 * **그 단일 지점 자체를 직접 잠근다** — 슬라이스 테스트는 컨트롤러 경유라 분기 일부만 밟는다.
 *
 * ## 잠그는 축
 * 1. 응답 상수 2종의 status + body (프론트가 `error` 키로 분기하므로 코드 문자열이 계약이다)
 * 2. [resolveActorId] 6분기 — JWT 정상/비-UUID · PAT principal 정상/비-UUID/비-String/미인증
 * 3. [requireSystemAdmin] 3분기 — 관리자 통과(null) · 비관리자 403 · **actor 추출 실패 시 401 이고
 *    resolver 를 아예 호출하지 않음**(auth-extraction-before-resource-lookup — strict mockk 가
 *    미stub 호출을 예외로 잡으므로 이 테스트 자체가 음성 가드다)
 */
class ControllerAuthSupportTest {
    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── 응답 상수 ────────────────────────────────────────────────────────────

    @Test
    fun `UNAUTHORIZED_RESPONSE 는 401 unauthorized 단일 error 키다`() {
        assertThat(UNAUTHORIZED_RESPONSE.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(UNAUTHORIZED_RESPONSE.body).isEqualTo(mapOf("error" to "unauthorized"))
    }

    @Test
    fun `FORBIDDEN_RESPONSE 는 403 forbidden 단일 error 키다`() {
        assertThat(FORBIDDEN_RESPONSE.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(FORBIDDEN_RESPONSE.body).isEqualTo(mapOf("error" to "forbidden"))
    }

    // ── resolveActorId ──────────────────────────────────────────────────────

    @Test
    fun `JWT subject 가 UUID 면 그 UUID 를 반환한다`() {
        assertThat(resolveActorId(jwtWithSubject(ACTOR_ID.toString()))).isEqualTo(ACTOR_ID)
    }

    @Test
    fun `JWT subject 가 비-UUID 면 null 이다`() {
        assertThat(resolveActorId(jwtWithSubject("not-a-uuid"))).isNull()
    }

    @Test
    fun `jwt 가 null 이면 SecurityContext principal 문자열을 UUID 로 파싱한다`() {
        setPatAuthentication(ACTOR_ID.toString())

        assertThat(resolveActorId(null)).isEqualTo(ACTOR_ID)
    }

    @Test
    fun `PAT principal 이 비-UUID 문자열이면 null 이다`() {
        setPatAuthentication("not-a-uuid")

        assertThat(resolveActorId(null)).isNull()
    }

    @Test
    fun `PAT principal 이 String 이 아니면 null 이다`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(ACTOR_ID, null, emptyList())

        assertThat(resolveActorId(null)).isNull()
    }

    @Test
    fun `jwt 가 null 이고 인증이 아예 없으면 null 이다`() {
        assertThat(resolveActorId(null)).isNull()
    }

    // ── requireSystemAdmin ──────────────────────────────────────────────────

    @Test
    fun `SYSTEM_ADMIN 이면 null 을 반환해 핸들러를 통과시킨다`() {
        val resolver = mockk<SystemPermissionResolver>()
        every { resolver.isSystemAdmin(ACTOR_ID) } returns true

        assertThat(resolver.requireSystemAdmin(jwtWithSubject(ACTOR_ID.toString()))).isNull()
    }

    @Test
    fun `SYSTEM_ADMIN 이 아니면 403 forbidden 을 반환한다`() {
        val resolver = mockk<SystemPermissionResolver>()
        every { resolver.isSystemAdmin(ACTOR_ID) } returns false

        val response = resolver.requireSystemAdmin(jwtWithSubject(ACTOR_ID.toString()))

        assertThat(response?.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response?.body).isEqualTo(mapOf("error" to "forbidden"))
    }

    @Test
    fun `actor 추출 실패는 resolver 를 호출하지 않고 401 이다`() {
        // strict mockk — isSystemAdmin 을 stub 하지 않았으므로 호출되면 테스트가 예외로 실패한다.
        val resolver = mockk<SystemPermissionResolver>()

        val response = resolver.requireSystemAdmin(jwtWithSubject("not-a-uuid"))

        assertThat(response?.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response?.body).isEqualTo(mapOf("error" to "unauthorized"))
    }

    private fun jwtWithSubject(subject: String): Jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject(subject)
            .build()

    /** PatAuthenticationFilter 와 동형 — principal 은 userId 문자열, 권한은 ROLE_PAT. */
    private fun setPatAuthentication(principal: String) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                principal,
                null,
                listOf(SimpleGrantedAuthority("ROLE_PAT")),
            )
    }

    private companion object {
        val ACTOR_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    }
}
