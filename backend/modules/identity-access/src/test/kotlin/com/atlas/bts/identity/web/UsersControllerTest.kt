// UsersController 슬라이스 테스트 — POST /api/v1/users 가입 엔드포인트 검증 (FR-AU-05 Task 4)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.credential.CreateLocalAccountService
import com.atlas.bts.identity.credential.CreatedAccount
import com.atlas.bts.identity.credential.UsernameTakenException
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * UsersController POST /api/v1/users 슬라이스 테스트 (FR-AU-05 Task 4).
 *
 * 검증 범위.
 * - admin(ROLE_SYSTEM_ADMIN): 201 + {id,username,temporaryPassword}, temporaryPassword 비어있지 않음
 * - 비 admin(일반 JWT 인증): 403 + 응답 본문에 권한 상세("SYSTEM_ADMIN") 미노출 (CONCERN-5)
 * - 중복 username: 409 {code:"USERNAME_TAKEN"}
 * - 검증 위반(username 누락/형식, displayName 누락, email 형식): 400
 * - 미인증: 401 (필터 체인)
 *
 * OAuth2ClientAutoConfiguration 제외 — Keycloak issuer-uri 네트워크 접속 차단.
 */
@WebMvcTest(
    controllers = [UsersController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, UsersControllerTest.MockBeans::class)
class UsersControllerTest {
    companion object {
        private val NEW_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        private val ADMIN_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        private val NOW: Instant = Instant.parse("2026-06-01T10:00:00Z")

        private const val NEW_USERNAME = "new.user_1"
        private const val NEW_EMAIL = "new.user@example.com"
        private const val NEW_DISPLAY_NAME = "New User"
        private const val TEMP_PASSWORD = "Temp-Passw0rd!"
        private const val ALLOWED_ORIGIN = "http://localhost:5173"

        private fun newUser() =
            User(
                id = NEW_USER_ID,
                username = NEW_USERNAME,
                email = NEW_EMAIL,
                displayName = NEW_DISPLAY_NAME,
                createdAt = NOW,
                updatedAt = NOW,
            )
    }

    @TestConfiguration
    class MockBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            val clock = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource {
            return CorsConfig().corsConfigurationSource(listOf(ALLOWED_ORIGIN))
        }

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun createLocalAccountService(): CreateLocalAccountService = mockk()

        @Bean
        fun userRepository(): UserRepository = mockk(relaxed = true)
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var createLocalAccountService: CreateLocalAccountService

    /**
     * ROLE_SYSTEM_ADMIN authority 를 가진 JWT 인증 — hasRole('SYSTEM_ADMIN') 통과용.
     *
     * 운영에서는 SidRevokeJwtConverter 가 roles 클레임을 ROLE_SYSTEM_ADMIN authority 로 변환하지만,
     * jwt() post-processor 는 converter 를 거치지 않고 SecurityContext 를 직접 채우므로 authority 를 명시한다.
     */
    private fun adminJwt() =
        jwt()
            .jwt { it.subject(ADMIN_ID.toString()).claim("roles", listOf("SYSTEM_ADMIN")) }
            .authorities(SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))

    /** SYSTEM_ADMIN authority 없는 일반 사용자 JWT (authority 기본값 SCOPE_*) */
    private fun userJwt() = jwt().jwt { it.subject(ADMIN_ID.toString()) }

    @Test
    fun `admin POST users 201 반환 — id username temporaryPassword 포함`() {
        every {
            createLocalAccountService.create(NEW_USERNAME, NEW_EMAIL, NEW_DISPLAY_NAME)
        } returns CreatedAccount(user = newUser(), temporaryPassword = TEMP_PASSWORD.toCharArray())

        mockMvc.perform(
            post("/api/v1/users")
                .with(adminJwt())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"username":"$NEW_USERNAME","email":"$NEW_EMAIL","displayName":"$NEW_DISPLAY_NAME"}""",
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(NEW_USER_ID.toString()))
            .andExpect(jsonPath("$.username").value(NEW_USERNAME))
            .andExpect(jsonPath("$.temporaryPassword").value(TEMP_PASSWORD))
            .andExpect(jsonPath("$.temporaryPassword").isNotEmpty)
    }

    @Test
    fun `admin POST users email 없이도 201`() {
        every {
            createLocalAccountService.create(NEW_USERNAME, null, NEW_DISPLAY_NAME)
        } returns
            CreatedAccount(
                user = newUser().copy(email = null),
                temporaryPassword = TEMP_PASSWORD.toCharArray(),
            )

        mockMvc.perform(
            post("/api/v1/users")
                .with(adminJwt())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$NEW_USERNAME","displayName":"$NEW_DISPLAY_NAME"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.temporaryPassword").value(TEMP_PASSWORD))
    }

    @Test
    fun `비 admin POST users 403 — 응답 본문에 권한 상세 미노출`() {
        mockMvc.perform(
            post("/api/v1/users")
                .with(userJwt())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"username":"$NEW_USERNAME","email":"$NEW_EMAIL","displayName":"$NEW_DISPLAY_NAME"}""",
                ),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().string(not(containsString("SYSTEM_ADMIN"))))
    }

    @Test
    fun `중복 username POST users 409 USERNAME_TAKEN`() {
        every {
            createLocalAccountService.create(NEW_USERNAME, NEW_EMAIL, NEW_DISPLAY_NAME)
        } throws UsernameTakenException(NEW_USERNAME)

        mockMvc.perform(
            post("/api/v1/users")
                .with(adminJwt())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"username":"$NEW_USERNAME","email":"$NEW_EMAIL","displayName":"$NEW_DISPLAY_NAME"}""",
                ),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"))
    }

    @Test
    fun `username 누락 POST users 400`() {
        mockMvc.perform(
            post("/api/v1/users")
                .with(adminJwt())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$NEW_EMAIL","displayName":"$NEW_DISPLAY_NAME"}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `username 형식 위반 POST users 400`() {
        mockMvc.perform(
            post("/api/v1/users")
                .with(adminJwt())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"bad user!","displayName":"$NEW_DISPLAY_NAME"}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `username 너무 짧음 POST users 400`() {
        mockMvc.perform(
            post("/api/v1/users")
                .with(adminJwt())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"ab","displayName":"$NEW_DISPLAY_NAME"}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `displayName 누락 POST users 400`() {
        mockMvc.perform(
            post("/api/v1/users")
                .with(adminJwt())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$NEW_USERNAME","email":"$NEW_EMAIL"}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `email 형식 위반 POST users 400`() {
        mockMvc.perform(
            post("/api/v1/users")
                .with(adminJwt())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$NEW_USERNAME","email":"not-an-email","displayName":"$NEW_DISPLAY_NAME"}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `미인증 POST users CSRF 없음 403 차단`() {
        mockMvc.perform(
            post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"username":"$NEW_USERNAME","email":"$NEW_EMAIL","displayName":"$NEW_DISPLAY_NAME"}""",
                ),
        )
            .andExpect(status().isForbidden)
    }
}
