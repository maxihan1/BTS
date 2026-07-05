// UserProfileController 슬라이스 테스트 — 프로필 조회/3-state PATCH/아바타 업로드-다운로드-삭제 (FR-PR-01 Task 5)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.profile.ProfilePatch
import com.atlas.bts.identity.profile.ProfilePatchField
import com.atlas.bts.identity.profile.ProfileUserNotFoundException
import com.atlas.bts.identity.profile.ProfileValidationException
import com.atlas.bts.identity.profile.ProfileView
import com.atlas.bts.identity.profile.UserProfileService
import com.atlas.bts.identity.profile.avatar.AvatarObject
import com.atlas.bts.identity.profile.avatar.AvatarObjectNotFoundException
import com.atlas.bts.identity.profile.avatar.AvatarValidationException
import com.atlas.bts.identity.session.SessionService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [UserProfileController] WebMvcTest 슬라이스 테스트 (FR-PR-01 Task 5).
 *
 * ## 검증 시나리오
 * - GET  /me/profile  — 200 + avatarUrl 파생(키 있음/null) / 미인증 401.
 * - PATCH /me/profile — displayName 만 명시 / department 명시 null(삭제) / timezone 무효 400 / displayName 공백 400.
 * - POST /me/profile/avatar — 200 + avatarUrl / 무효 MIME·크기 400.
 * - GET  /{userId}/avatar — 200 + Content-Type/nosniff/inline 헤더 / 미존재 404.
 * - DELETE /me/profile/avatar — 204.
 *
 * ## 의존성 모킹 전략 ([PersonalAccessTokenControllerTest] 선례)
 * [UserProfileService] 는 companion object 가 없는 평범한 클래스라 MockK 로 안전하게 Spring
 * `@Bean` 등록이 가능하다(ByteBuddy `$Companion` 문제 없음). SecurityConfig 필수 Bean 은
 * `@TestConfiguration` + MockK 로 공급한다.
 */
@WebMvcTest(
    controllers = [UserProfileController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, UserProfileControllerTest.SecurityBeans::class)
class UserProfileControllerTest {
    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC)

        @Bean
        fun sidRevokeJwtConverter(clock: Clock): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource =
            CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))

        @Bean
        fun userProfileService(): UserProfileService = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var userProfileService: UserProfileService

    private val userId = UUID.fromString("11111111-1111-4111-8111-111111111111")

    @BeforeEach
    fun resetMock() {
        clearMocks(userProfileService)
    }

    private fun jwtFor(uid: UUID) = jwt().jwt { builder -> builder.subject(uid.toString()) }

    private fun profileView(
        avatarObjectKey: String? = null,
        department: String? = "Engineering",
    ): ProfileView =
        ProfileView(
            userId = userId,
            username = "alice",
            email = "alice@bts.local",
            displayName = "Alice",
            avatarObjectKey = avatarObjectKey,
            timezone = "UTC",
            department = department,
        )

    // ── GET /me/profile ───────────────────────────────────────────────────────

    @Test
    fun `GET me profile returns 401 without authentication`() {
        mockMvc.perform(get("/api/v1/users/me/profile"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET me profile returns 200 with derived avatarUrl when avatar set`() {
        every { userProfileService.getProfile(userId) } returns
            profileView(avatarObjectKey = "avatars/$userId/abc.png")

        mockMvc.perform(get("/api/v1/users/me/profile").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(userId.toString()))
            .andExpect(jsonPath("$.username").value("alice"))
            .andExpect(jsonPath("$.email").value("alice@bts.local"))
            .andExpect(jsonPath("$.displayName").value("Alice"))
            .andExpect(jsonPath("$.avatarUrl").value("/api/v1/users/$userId/avatar"))
            .andExpect(jsonPath("$.timezone").value("UTC"))
            .andExpect(jsonPath("$.department").value("Engineering"))
    }

    @Test
    fun `GET me profile returns null avatarUrl when no avatar set`() {
        every { userProfileService.getProfile(userId) } returns profileView(avatarObjectKey = null)

        mockMvc.perform(get("/api/v1/users/me/profile").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.avatarUrl").value(nullValue()))
    }

    // ── PATCH /me/profile ──────────────────────────────────────────────────────

    @Test
    fun `PATCH me profile with displayName only sends Present displayName and Absent others`() {
        val patch = ProfilePatch(displayName = ProfilePatchField.Present("New Name"))
        every { userProfileService.patchProfile(userId, patch) } returns
            profileView().copy(displayName = "New Name")

        mockMvc.perform(
            patch("/api/v1/users/me/profile")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""{"displayName":"New Name"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.displayName").value("New Name"))
    }

    @Test
    fun `PATCH me profile with explicit null department maps to Present null (delete)`() {
        val patch = ProfilePatch(department = ProfilePatchField.Present(null))
        every { userProfileService.patchProfile(userId, patch) } returns
            profileView(department = null)

        mockMvc.perform(
            patch("/api/v1/users/me/profile")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""{"department":null}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.department").value(nullValue()))
    }

    @Test
    fun `PATCH me profile with invalid timezone returns 400`() {
        val patch = ProfilePatch(timezone = ProfilePatchField.Present("Not/AZone"))
        every { userProfileService.patchProfile(userId, patch) } throws
            ProfileValidationException("유효하지 않은 타임존입니다.")

        mockMvc.perform(
            patch("/api/v1/users/me/profile")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""{"timezone":"Not/AZone"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PROFILE_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("유효하지 않은 타임존입니다."))
    }

    @Test
    fun `PATCH me profile with blank displayName returns 400`() {
        val patch = ProfilePatch(displayName = ProfilePatchField.Present(""))
        every { userProfileService.patchProfile(userId, patch) } throws
            ProfileValidationException("표시 이름은 공백일 수 없고 255자를 초과할 수 없습니다.")

        mockMvc.perform(
            patch("/api/v1/users/me/profile")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""{"displayName":""}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PROFILE_VALIDATION_FAILED"))
    }

    @Test
    fun `PATCH me profile propagates 404 when user not found`() {
        val patch = ProfilePatch(displayName = ProfilePatchField.Present("New Name"))
        every { userProfileService.patchProfile(userId, patch) } throws
            ProfileUserNotFoundException(userId)

        mockMvc.perform(
            patch("/api/v1/users/me/profile")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""{"displayName":"New Name"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"))
    }

    // ── POST /me/profile/avatar ────────────────────────────────────────────────

    @Test
    fun `POST avatar with valid image returns 200 with avatarUrl`() {
        every { userProfileService.uploadAvatar(userId, any(), "image/png") } returns
            "avatars/$userId/new.png"

        mockMvc.perform(
            multipart("/api/v1/users/me/profile/avatar")
                .file(MockMultipartFile("file", "avatar.png", "image/png", byteArrayOf(1, 2, 3)))
                .with(jwtFor(userId))
                .with(csrf()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.avatarUrl").value("/api/v1/users/$userId/avatar"))
    }

    @Test
    fun `POST avatar with invalid MIME returns 400`() {
        every { userProfileService.uploadAvatar(userId, any(), "text/plain") } throws
            AvatarValidationException("지원하지 않는 이미지 형식입니다.")

        mockMvc.perform(
            multipart("/api/v1/users/me/profile/avatar")
                .file(MockMultipartFile("file", "evil.txt", "text/plain", byteArrayOf(1, 2, 3)))
                .with(jwtFor(userId))
                .with(csrf()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("AVATAR_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("지원하지 않는 이미지 형식입니다."))
    }

    // ── GET /{userId}/avatar ───────────────────────────────────────────────────

    @Test
    fun `GET avatar by userId returns 200 with nosniff and inline headers`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        every { userProfileService.getAvatar(userId) } returns
            AvatarObject(content = ByteArrayInputStream(bytes), contentType = "image/png")

        mockMvc.perform(get("/api/v1/users/$userId/avatar").with(jwtFor(UUID.randomUUID())))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Disposition", "inline"))
            .andExpect(content().bytes(bytes))
    }

    @Test
    fun `GET avatar by userId returns 404 when avatar not set`() {
        every { userProfileService.getAvatar(userId) } throws
            AvatarObjectNotFoundException("아바타가 설정되어 있지 않습니다.")

        mockMvc.perform(get("/api/v1/users/$userId/avatar").with(jwtFor(UUID.randomUUID())))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("AVATAR_NOT_FOUND"))
    }

    @Test
    fun `GET avatar by userId returns 401 without authentication`() {
        mockMvc.perform(get("/api/v1/users/$userId/avatar"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET avatar by userId with PAT-style non-JWT principal is still permitted (any authenticated user)`() {
        val bytes = byteArrayOf(9)
        every { userProfileService.getAvatar(userId) } returns
            AvatarObject(content = ByteArrayInputStream(bytes), contentType = "image/webp")

        mockMvc.perform(get("/api/v1/users/$userId/avatar").with(user("some-authenticated-principal")))
            .andExpect(status().isOk)
    }

    // ── DELETE /me/profile/avatar ──────────────────────────────────────────────

    @Test
    fun `DELETE avatar returns 204`() {
        every { userProfileService.deleteAvatar(userId) } returns Unit

        mockMvc.perform(
            delete("/api/v1/users/me/profile/avatar")
                .with(jwtFor(userId))
                .with(csrf()),
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE avatar with PAT-style non-JWT principal returns 401`() {
        mockMvc.perform(
            delete("/api/v1/users/me/profile/avatar")
                .with(user("some-authenticated-principal"))
                .with(csrf()),
        )
            .andExpect(status().isUnauthorized)
    }
}
