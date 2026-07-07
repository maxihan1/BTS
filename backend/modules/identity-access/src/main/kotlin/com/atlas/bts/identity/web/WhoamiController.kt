// JWT 및 PAT Bearer 토큰으로 현재 인증된 사용자 정보를 반환하는 whoami 엔드포인트

package com.atlas.bts.identity.web

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.credential.StoredPasswordCredentialRepository
import com.atlas.bts.identity.dto.WhoamiResponse
import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.ooo.OutOfOfficeRepository
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.preferences.UserPreferences
import com.atlas.bts.identity.preferences.UserPreferencesService
import com.atlas.bts.identity.profile.UserProfileRepository
import com.atlas.bts.identity.status.UserStatusRepository
import com.atlas.bts.identity.user.UserRepository
import com.bts.shared.permission.SystemPermissionResolver
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.util.UUID

/**
 * 현재 인증된 사용자 정보를 반환하는 whoami 엔드포인트
 * (PR #2 기반 + Task 24 PAT 확장 + FR-AU-05 Task 5 + FR-MF-04 Task 6).
 *
 * ## 인증 방식 분기
 *
 * - **JWT**: Spring Security 필터 체인이 검증한 [Jwt] 객체를 [AuthenticationPrincipal] 로 주입받는다.
 *   `authMethod = "jwt"` 를 반환한다. 추가로 강제 비밀번호 변경 필요 여부·시스템 관리자 여부·MFA 강제 등록
 *   필요 여부를 채운다. 강제 변경 플래그는 [StoredPasswordCredentialRepository.findByUserId] 로 조회하며,
 *   local_credentials 행이 없는 SSO(LDAP/OIDC/SAML) 사용자는 false 로 귀결된다. 시스템 관리자 여부는
 *   [SystemPermissionResolver.isSystemAdmin] 판정을 반영한다. MFA 강제 등록 필요 여부(`mfaEnrollmentRequired`)는
 *   access JWT 의 [JwtIssuer.CLAIM_MFA_ENROLLMENT_REQUIRED] 클레임 값(부재=false)을 그대로 읽어 노출한다
 *   (FR-MF-04). 발급 chokepoint([JwtIssuer])가 박은 클레임을 백엔드 게이트 필터와 **단일 출처**로 공유하므로
 *   whoami 노출 값과 게이트 차단 판정이 항상 일치하며, whoami 가 정책을 라이브 재계산하지 않는다(EC7).
 *   또한 프로필 view-layer(FR-PR-01)로 `displayName`(users.display_name)과 `avatarUrl`
 *   (user_profiles.avatar_object_key 파생, [avatarUrlFor])을 노출한다. 부재중 view-layer(FR-PR-03)로
 *   `oooActive`/`oooUntil`(user_ooo 활성 필터 파생, [OutOfOfficeRepository.findActiveByUserId])도 노출한다.
 *   환경설정 view-layer(FR-PF-01)로 `theme`/`locale`/`dateFormat`(user_preferences 파생,
 *   [UserPreferencesService.getPreferences])도 노출한다 — 행이 없어도 서비스가 기본값(system/ko/iso)으로
 *   귀결하므로 항상 값이 채워진다(null 없음).
 *
 * - **PAT**: `Authorization: Bearer pat_xxx` 형식의 요청을 감지하여 [PersonalAccessTokenService.verify] 로
 *   검증한다. 검증 성공 시 `authMethod = "pat"` + `userId` 를 반환하고,
 *   [AuthEventType.PAT_USED] 감사 이벤트를 기록한다.
 *   검증 실패(만료·revoke·미존재) 시 401 을 반환한다.
 *   강제 변경·시스템 관리자·MFA 강제 등록 플래그는 봇 컨텍스트(PAT)와 무관하므로 모두 false 로 고정한다.
 *   프로필 view-layer(displayName/avatarUrl)도 봇 컨텍스트와 무관하므로 둘 다 null 로 고정한다.
 *   환경설정 view-layer(theme/locale/dateFormat)도 봇 컨텍스트와 무관하므로 조회 없이
 *   [UserPreferences] 기본값 상수로 고정한다.
 *
 * ## EC-26 prefix 검사
 *
 * raw token 이 정확히 `pat_` 로 시작하는 경우에만 PAT 흐름으로 진입한다.
 * [PersonalAccessToken.TOKEN_PREFIX] (`"pat_"`) 로 비교한다.
 * 그 외 Bearer 토큰은 JWT 흐름으로 처리된다.
 *
 * ## 필터 체인 연동
 *
 * [PatAuthenticationFilter] 가 JWT 필터보다 먼저 실행되어 `pat_` prefix 토큰을 [SecurityContextHolder] 에
 * 설정한다. SecurityConfig 의 `BearerTokenResolver` 커스텀으로 `pat_` 토큰은 JWT 파싱 대상에서 제외된다.
 */
@RestController
@Suppress("LongParameterList") // whoami view-layer 집약점 — 인증(PAT/JWT)·감사·프로필/상태/부재중 뷰레이어 협력자를 한 곳에서 조립
class WhoamiController(
    private val personalAccessTokenService: PersonalAccessTokenService,
    private val authAuditLogService: AuthAuditLogService,
    private val userRepository: UserRepository,
    private val storedPasswordCredentialRepository: StoredPasswordCredentialRepository,
    private val systemPermissionResolver: SystemPermissionResolver,
    private val userProfileRepository: UserProfileRepository,
    private val userStatusRepository: UserStatusRepository,
    private val outOfOfficeRepository: OutOfOfficeRepository,
    private val userPreferencesService: UserPreferencesService,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * `GET /api/v1/users/me/whoami` — 현재 인증된 사용자 정보 반환.
     *
     * @param request HTTP 요청 (Authorization 헤더 직접 파싱용)
     * @param jwt Spring Security 필터 체인이 주입한 JWT Principal (PAT 요청 시 null)
     * @return 사용자 식별 정보 + authMethod + mustChangePassword + isSystemAdmin + mfaEnrollmentRequired
     */
    @GetMapping("/api/v1/users/me/whoami")
    fun whoami(
        request: HttpServletRequest,
        @AuthenticationPrincipal jwt: Jwt?,
    ): WhoamiResponse {
        val rawToken = extractBearerToken(request)

        // EC-26: "pat_" prefix 검사 — token body 시작 부분이 정확히 "pat_" 인지 확인.
        // PersonalAccessToken.TOKEN_PREFIX = "pat_" (internal companion object)
        if (rawToken != null && rawToken.startsWith(PersonalAccessToken.TOKEN_PREFIX)) {
            return handlePat(rawToken)
        }

        // JWT 흐름 — Spring Security 필터 체인이 이미 검증 완료. sub (UUID) 로 User 조회해 username/email 채움.
        if (jwt != null) {
            val userId =
                runCatching { UUID.fromString(jwt.subject) }.getOrNull()
                    ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
            val user =
                userRepository.findById(userId)
                    ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
            // local_credentials 행이 없는 SSO(LDAP/OIDC/SAML) 사용자는 강제 변경 대상이 아님 → false
            val mustChangePassword =
                storedPasswordCredentialRepository.findByUserId(userId)?.mustChangePassword ?: false
            // FR-MF-04: 발급 chokepoint(JwtIssuer)가 박은 클레임을 그대로 읽음(부재=false). 게이트 필터와 단일 출처 일치 → EC7.
            val mfaEnrollmentRequired =
                jwt.getClaim<Boolean>(JwtIssuer.CLAIM_MFA_ENROLLMENT_REQUIRED) ?: false
            // FR-PR-02: 활성(미만료) 상태만 노출. 만료 필터는 repository 의 findActiveByUserId 책임.
            val status = userStatusRepository.findActiveByUserId(userId)
            // FR-PR-03: 활성(startsAt<=now<endsAt) 부재중만 노출. 활성 필터는 repository 책임, now 는 공유 clock 기준.
            val ooo = outOfOfficeRepository.findActiveByUserId(userId, clock)
            // FR-PF-01: 환경설정(theme/locale/dateFormat) — 행 부재 시 서비스가 기본값(system/ko/iso)으로 귀결.
            val preferences = userPreferencesService.getPreferences(userId)
            return WhoamiResponse(
                username = user.username,
                email = user.email.orEmpty(),
                authMethod = "jwt",
                userId = user.id,
                mustChangePassword = mustChangePassword,
                isSystemAdmin = systemPermissionResolver.isSystemAdmin(userId),
                mfaEnrollmentRequired = mfaEnrollmentRequired,
                displayName = user.displayName,
                avatarUrl = avatarUrlFor(userId),
                statusEmoji = status?.emoji,
                statusText = status?.text,
                oooActive = ooo != null,
                oooUntil = ooo?.endsAt?.toString(),
                theme = preferences.theme,
                locale = preferences.locale,
                dateFormat = preferences.dateFormat,
            )
        }

        throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
    }

    /**
     * PAT raw token 을 검증하고 감사 로그를 기록한 뒤 응답을 반환한다.
     *
     * 검증 실패 시 원인을 외부에 노출하지 않고 401 을 반환한다.
     *
     * @param rawToken `pat_` prefix 포함 raw PAT token
     * @return PAT 인증 성공 응답 (authMethod="pat", userId)
     */
    private fun handlePat(rawToken: String): WhoamiResponse {
        val result = personalAccessTokenService.verify(rawToken)

        if (result.isFailure) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }

        val pat = result.getOrThrow()

        // AuthAuditLog.PAT_USED — PAT 사용 감사 이벤트 기록 (Task 36 enum)
        authAuditLogService.record(
            AuthAuditLog(
                userId = pat.userId,
                eventType = AuthEventType.PAT_USED,
                providerId = "pat",
            ),
        )

        // PAT 분기는 비밀번호 변경 흐름·시스템 역할 컨텍스트를 노출하지 않는다.
        // 강제 변경(mustChangePassword)은 대화형 로그인(JWT) 사용자만의 관심사이며,
        // 시스템 관리자 판정(isSystemAdmin) 또한 JWT 세션에서만 의미가 있으므로 둘 다 false 고정.
        // MFA 강제 등록(mfaEnrollmentRequired)도 봇 컨텍스트(PAT)와 무관하므로 false 고정.
        return WhoamiResponse(
            username = "",
            email = "",
            authMethod = "pat",
            userId = pat.userId,
            mustChangePassword = false,
            isSystemAdmin = false,
            mfaEnrollmentRequired = false,
            // 프로필 view-layer(displayName/avatarUrl)도 봇 컨텍스트(PAT)와 무관하므로 null 고정.
            displayName = null,
            avatarUrl = null,
            // 상태 view-layer(statusEmoji/statusText, FR-PR-02)도 봇 컨텍스트라 null 고정.
            statusEmoji = null,
            statusText = null,
            // 부재중 view-layer(oooActive/oooUntil, FR-PR-03)도 봇 컨텍스트라 고정(repository 미조회).
            oooActive = false,
            oooUntil = null,
            // 환경설정(theme/locale/dateFormat, FR-PF-01)도 봇 컨텍스트라 조회 없이 기본값으로 고정.
            theme = UserPreferences.DEFAULT_THEME,
            locale = UserPreferences.DEFAULT_LOCALE,
            dateFormat = UserPreferences.DEFAULT_DATE_FORMAT,
        )
    }

    /**
     * 사용자 아바타 다운로드 경로를 파생한다 (FR-PR-01, JWT 분기 전용).
     *
     * 파생 근거는 `user_profiles.avatar_object_key` 다 — [UserProfileRepository.findByUserId] 로 조회해
     * 오브젝트 키가 설정돼 있으면 다운로드 경로 `/api/v1/users/{userId}/avatar` 를, 미설정(프로필 행 부재
     * 또는 키가 null)이면 null 을 반환한다([UserProfileController.getAvatar] 와 동일한 경로 형식). MinIO
     * 오브젝트 키 자체는 응답에 노출하지 않고, 인증 필터가 보호하는 다운로드 엔드포인트 경로만 노출한다.
     *
     * PAT(봇) 분기는 이 파생을 호출하지 않고 avatarUrl 을 항상 null 로 고정한다.
     *
     * @param userId JWT subject 로 식별한 현재 사용자 id.
     * @return 아바타가 설정돼 있으면 다운로드 경로, 아니면 null.
     */
    private fun avatarUrlFor(userId: UUID): String? =
        userProfileRepository.findByUserId(userId)?.avatarObjectKey
            ?.let { "/api/v1/users/$userId/avatar" }

    /**
     * `Authorization: Bearer <token>` 헤더에서 raw token 을 추출한다.
     *
     * `Bearer ` prefix 가 없거나 Authorization 헤더가 없으면 null 을 반환한다.
     *
     * @param request HTTP 요청
     * @return raw Bearer token 또는 null
     */
    private fun extractBearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        return header.removePrefix("Bearer ")
    }
}
